package connection

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"strconv"
	"sync"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/adapter"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/catalog"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/ownedsource"
)

type Registry interface {
	SnapshotAdapter(context.Context, string) (adapter.Projection, error)
	RefreshSource(context.Context, string, string) (adapter.OperationResult, adapter.Projection, error)
	Test(context.Context, string) adapter.TestResult
	PlanActivation(context.Context, string, uint64) (adapter.ActivationPlan, error)
	Activate(context.Context, adapter.ActivationPlan) adapter.OperationResult
}

type Store interface {
	Snapshot(context.Context) (catalog.Document, error)
	ReplaceAdapterProjection(context.Context, uint64, string, string, string, []catalog.Source, []catalog.Node, catalog.RecordedResult) (catalog.Document, error)
	RecordResult(context.Context, uint64, string, string, catalog.RecordedResult) (catalog.Document, error)
	LookupResult(context.Context, string, string) (catalog.RecordedResult, bool, error)
	SourceSecret(context.Context, string) ([]byte, map[string]string, error)
	SourceProjection(context.Context, string) ([]byte, error)
	ReplaceOwnedProjection(context.Context, uint64, string, string, string, []catalog.Node, []byte, *catalog.SubscriptionInfo, catalog.RecordedResult) (catalog.Document, error)
	CommitOwnedActivation(context.Context, uint64, string, string, string, catalog.RecordedResult) (catalog.Document, error)
}

type OwnedProcessor interface {
	Prepare(context.Context, string, catalog.SourceKind, []byte) (ownedsource.Prepared, error)
	Test(context.Context, string, string, []byte) adapter.TestResult
	Activate(context.Context, string, string, []byte) adapter.OperationResult
	Readback(context.Context, string) (bool, uint64, error)
}

type Result struct {
	Result    string
	ErrorCode string
	Catalog   catalog.Document
	Test      *adapter.TestResult
}

type Coordinator struct {
	store    Store
	registry Registry
	owned    OwnedProcessor
	now      func() time.Time
	locksMu  sync.Mutex
	locks    map[string]*sync.Mutex
}

func NewCoordinator(store Store, registry Registry, now func() time.Time, owned ...OwnedProcessor) *Coordinator {
	if now == nil {
		now = time.Now
	}
	var ownedProcessor OwnedProcessor
	if len(owned) > 0 {
		ownedProcessor = owned[0]
	}
	return &Coordinator{store: store, registry: registry, owned: ownedProcessor, now: now, locks: make(map[string]*sync.Mutex)}
}

func (c *Coordinator) SyncAdapter(ctx context.Context, adapterID string) error {
	lock := c.adapterLock(adapterID)
	lock.Lock()
	defer lock.Unlock()
	projection, err := c.registry.SnapshotAdapter(ctx, adapterID)
	if err != nil {
		return err
	}
	document, err := c.store.Snapshot(ctx)
	if err != nil {
		return err
	}
	digest := operationDigest("sync", adapterID, strconv.FormatUint(projection.StateVersion, 10), projectionDigest(projection))
	key := "sync_" + digest[:24]
	_, err = c.store.ReplaceAdapterProjection(ctx, document.StateVersion, key, digest, adapterID, projection.Sources, projection.Nodes,
		catalog.RecordedResult{Kind: "sync", Result: adapter.ResultCommitted, ObservedUnix: c.now().Unix()})
	if errors.Is(err, catalog.ErrOperationConflict) || errors.Is(err, catalog.ErrStaleState) {
		return err
	}
	return err
}

func (c *Coordinator) RefreshSource(ctx context.Context, reviewed uint64, key, sourceID string) Result {
	digest := operationDigest("refresh", sourceID, strconv.FormatUint(reviewed, 10))
	if replay, ok := c.replay(ctx, key, digest); ok {
		return replay
	}
	document, source, rejected := c.reviewSource(ctx, reviewed, sourceID)
	if rejected.Result != "" {
		return c.record(ctx, reviewed, key, digest, rejected)
	}
	if !source.Foreign && (source.AdapterID != "catalog" || c.owned == nil) {
		return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultRejected, ErrorCode: "source_not_refreshable", Catalog: document})
	}
	lock := c.adapterLock(source.AdapterID)
	lock.Lock()
	defer lock.Unlock()
	if replay, ok := c.replay(ctx, key, digest); ok {
		return replay
	}
	if current, _, stale := c.reviewSource(ctx, reviewed, sourceID); stale.Result != "" {
		return c.record(ctx, reviewed, key, digest, stale)
	} else {
		document = current
	}
	if !source.Foreign {
		raw, _, err := c.store.SourceSecret(ctx, sourceID)
		if err != nil {
			return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultRejected, ErrorCode: "source_unavailable", Catalog: document})
		}
		prepared, err := c.owned.Prepare(ctx, sourceID, source.Kind, raw)
		if err != nil {
			return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultRejected, ErrorCode: "source_refresh_failed", Catalog: document})
		}
		defer prepared.Clear()
		recorded := catalog.RecordedResult{Kind: "refresh", Result: adapter.ResultCommitted, ObservedUnix: c.now().Unix()}
		updated, err := c.store.ReplaceOwnedProjection(ctx, reviewed, key, digest, sourceID, prepared.Nodes, prepared.Payload, prepared.Subscription, recorded)
		if err != nil {
			return c.persistFailure(ctx, reviewed, key, digest, err)
		}
		return Result{Result: adapter.ResultCommitted, Catalog: updated}
	}
	operation, projection, err := c.registry.RefreshSource(ctx, source.AdapterID, sourceID)
	if err != nil || operation.Result != adapter.ResultCommitted {
		result := fromOperation(operation, "adapter_refresh_failed")
		if err != nil && operation.Result == adapter.ResultCommitted {
			result = Result{Result: adapter.ResultUncertain, ErrorCode: "adapter_refresh_failed"}
		}
		return c.record(ctx, reviewed, key, digest, result)
	}
	if !projectionContainsSource(projection, sourceID) {
		return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultUncertain, ErrorCode: "adapter_readback_failed", Catalog: document})
	}
	recorded := catalog.RecordedResult{Kind: "refresh", Result: adapter.ResultCommitted, ObservedUnix: c.now().Unix()}
	updated, err := c.store.ReplaceAdapterProjection(ctx, reviewed, key, digest, source.AdapterID, projection.Sources, projection.Nodes, recorded)
	if err != nil {
		return c.persistFailure(ctx, reviewed, key, digest, err)
	}
	return Result{Result: adapter.ResultCommitted, Catalog: updated}
}

func (c *Coordinator) TestNode(ctx context.Context, reviewed uint64, key, nodeID string) Result {
	digest := operationDigest("test", nodeID, strconv.FormatUint(reviewed, 10))
	if replay, ok := c.replay(ctx, key, digest); ok {
		return replay
	}
	document, node, source, rejected := c.reviewNode(ctx, reviewed, nodeID, true, false)
	if rejected.Result != "" {
		return c.record(ctx, reviewed, key, digest, rejected)
	}
	lock := c.adapterLock(source.AdapterID)
	lock.Lock()
	defer lock.Unlock()
	if replay, ok := c.replay(ctx, key, digest); ok {
		return replay
	}
	current, node, source, rejected := c.reviewNode(ctx, reviewed, nodeID, true, false)
	if rejected.Result != "" {
		return c.record(ctx, reviewed, key, digest, rejected)
	}
	document = current
	if !source.Foreign && source.AdapterID == "catalog" && c.owned != nil {
		payload, err := c.store.SourceProjection(ctx, source.ID)
		if err != nil {
			return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultRejected, ErrorCode: "source_not_ready", Catalog: document})
		}
		defer erase(payload)
		test := c.owned.Test(ctx, source.ID, node.ID, payload)
		if test.NodeID != node.ID {
			return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultUncertain, ErrorCode: "adapter_test_invalid", Catalog: document})
		}
		if test.ObservedAt.IsZero() {
			test.ObservedAt = c.now().UTC()
		}
		return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultCommitted, Catalog: document, Test: &test})
	}
	projection, err := c.registry.SnapshotAdapter(ctx, source.AdapterID)
	if err != nil {
		return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultRejected, ErrorCode: "adapter_unavailable", Catalog: document})
	}
	if projection.StateVersion != source.AdapterStateVersion || !projectionContainsNode(projection, node.ID) {
		return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultRejected, ErrorCode: "stale_adapter_state", Catalog: document})
	}
	test := c.registry.Test(ctx, node.ID)
	if test.NodeID != node.ID {
		return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultUncertain, ErrorCode: "adapter_test_invalid", Catalog: document})
	}
	if test.ObservedAt.IsZero() {
		test.ObservedAt = c.now().UTC()
	}
	result := Result{Result: adapter.ResultCommitted, Catalog: document, Test: &test}
	return c.record(ctx, reviewed, key, digest, result)
}

func (c *Coordinator) ActivateNode(ctx context.Context, reviewed uint64, key, nodeID string) Result {
	digest := operationDigest("activate", nodeID, strconv.FormatUint(reviewed, 10))
	if replay, ok := c.replay(ctx, key, digest); ok {
		return replay
	}
	document, _, source, rejected := c.reviewNode(ctx, reviewed, nodeID, false, true)
	if rejected.Result != "" {
		return c.record(ctx, reviewed, key, digest, rejected)
	}
	lock := c.adapterLock(source.AdapterID)
	lock.Lock()
	defer lock.Unlock()
	if replay, ok := c.replay(ctx, key, digest); ok {
		return replay
	}
	current, node, source, rejected := c.reviewNode(ctx, reviewed, nodeID, false, true)
	if rejected.Result != "" {
		return c.record(ctx, reviewed, key, digest, rejected)
	}
	document = current
	if !source.Foreign && source.AdapterID == "catalog" && c.owned != nil {
		payload, err := c.store.SourceProjection(ctx, source.ID)
		if err != nil {
			return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultRejected, ErrorCode: "source_not_ready", Catalog: document})
		}
		defer erase(payload)
		_, controllerVersion, err := c.owned.Readback(ctx, node.ID)
		if err != nil {
			return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultRejected, ErrorCode: "adapter_unavailable", Catalog: document})
		}
		if xkeenVersion(document) != controllerVersion {
			return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultRejected, ErrorCode: "stale_adapter_state", Catalog: document})
		}
		operation := c.owned.Activate(ctx, source.ID, node.ID, payload)
		active, _, readbackErr := c.owned.Readback(ctx, node.ID)
		if readbackErr != nil || !active {
			result := fromOperation(operation, "activation_readback_failed")
			if operation.Result == adapter.ResultCommitted || operation.Result == adapter.ResultUncertain || readbackErr != nil {
				result.Result = adapter.ResultUncertain
				result.ErrorCode = "activation_readback_failed"
			}
			return c.record(ctx, reviewed, key, digest, result)
		}
		recorded := catalog.RecordedResult{Kind: "activate", Result: adapter.ResultCommitted, NodeID: node.ID, ObservedUnix: c.now().Unix()}
		updated, err := c.store.CommitOwnedActivation(ctx, reviewed, key, digest, node.ID, recorded)
		if err != nil {
			return c.persistFailure(ctx, reviewed, key, digest, err)
		}
		return Result{Result: adapter.ResultCommitted, Catalog: updated}
	}
	projection, err := c.registry.SnapshotAdapter(ctx, source.AdapterID)
	if err != nil {
		return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultRejected, ErrorCode: "adapter_unavailable", Catalog: document})
	}
	if projection.StateVersion != source.AdapterStateVersion || !projectionContainsNode(projection, node.ID) {
		return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultRejected, ErrorCode: "stale_adapter_state", Catalog: document})
	}
	plan, err := c.registry.PlanActivation(ctx, node.ID, projection.StateVersion)
	if err != nil || plan.NodeID != node.ID || plan.AdapterID != source.AdapterID {
		return c.record(ctx, reviewed, key, digest, Result{Result: adapter.ResultRejected, ErrorCode: "activation_plan_rejected", Catalog: document})
	}
	operation := c.registry.Activate(ctx, plan)
	readback, readbackErr := c.registry.SnapshotAdapter(ctx, source.AdapterID)
	if readbackErr != nil || !projectionHasActiveNode(readback, node.ID) {
		result := fromOperation(operation, "activation_readback_failed")
		if operation.Result == adapter.ResultCommitted || operation.Result == adapter.ResultUncertain || readbackErr != nil {
			result.Result = adapter.ResultUncertain
			result.ErrorCode = "activation_readback_failed"
		}
		return c.record(ctx, reviewed, key, digest, result)
	}
	recorded := catalog.RecordedResult{Kind: "activate", Result: adapter.ResultCommitted, NodeID: node.ID, ObservedUnix: c.now().Unix()}
	updated, err := c.store.ReplaceAdapterProjection(ctx, reviewed, key, digest, source.AdapterID, readback.Sources, readback.Nodes, recorded)
	if err != nil {
		return c.persistFailure(ctx, reviewed, key, digest, err)
	}
	return Result{Result: adapter.ResultCommitted, Catalog: updated}
}

func (c *Coordinator) reviewSource(ctx context.Context, reviewed uint64, sourceID string) (catalog.Document, catalog.Source, Result) {
	document, err := c.store.Snapshot(ctx)
	if err != nil {
		return catalog.Document{}, catalog.Source{}, Result{Result: adapter.ResultUncertain, ErrorCode: "catalog_unavailable"}
	}
	if document.StateVersion != reviewed {
		return document, catalog.Source{}, Result{Result: adapter.ResultRejected, ErrorCode: "stale_state", Catalog: document}
	}
	for _, source := range document.Sources {
		if source.ID == sourceID {
			return document, source, Result{}
		}
	}
	return document, catalog.Source{}, Result{Result: adapter.ResultRejected, ErrorCode: "source_not_found", Catalog: document}
}

func (c *Coordinator) reviewNode(ctx context.Context, reviewed uint64, nodeID string, requireTest, requireActivate bool) (catalog.Document, catalog.Node, catalog.Source, Result) {
	document, err := c.store.Snapshot(ctx)
	if err != nil {
		return catalog.Document{}, catalog.Node{}, catalog.Source{}, Result{Result: adapter.ResultUncertain, ErrorCode: "catalog_unavailable"}
	}
	if document.StateVersion != reviewed {
		return document, catalog.Node{}, catalog.Source{}, Result{Result: adapter.ResultRejected, ErrorCode: "stale_state", Catalog: document}
	}
	var node catalog.Node
	found := false
	for _, candidate := range document.Nodes {
		if candidate.ID == nodeID {
			node, found = candidate, true
			break
		}
	}
	if !found {
		return document, node, catalog.Source{}, Result{Result: adapter.ResultRejected, ErrorCode: "node_not_found", Catalog: document}
	}
	if requireTest && !node.Testable {
		return document, node, catalog.Source{}, Result{Result: adapter.ResultRejected, ErrorCode: "node_not_testable", Catalog: document}
	}
	if requireActivate && !node.Activatable {
		return document, node, catalog.Source{}, Result{Result: adapter.ResultRejected, ErrorCode: "node_not_activatable", Catalog: document}
	}
	for _, source := range document.Sources {
		if source.ID == node.SourceID {
			return document, node, source, Result{}
		}
	}
	return document, node, catalog.Source{}, Result{Result: adapter.ResultUncertain, ErrorCode: "catalog_invalid", Catalog: document}
}

func (c *Coordinator) replay(ctx context.Context, key, digest string) (Result, bool) {
	recorded, found, err := c.store.LookupResult(ctx, key, digest)
	if err != nil {
		return resultForStoreError(err), true
	}
	if !found {
		return Result{}, false
	}
	document, err := c.store.Snapshot(ctx)
	if err != nil {
		return Result{Result: adapter.ResultUncertain, ErrorCode: "catalog_unavailable"}, true
	}
	result := Result{Result: recorded.Result, ErrorCode: recorded.ErrorCode, Catalog: document}
	if recorded.Kind == "test" {
		observed := time.Unix(recorded.ObservedUnix, 0).UTC()
		result.Test = &adapter.TestResult{NodeID: recorded.NodeID, Reachable: recorded.Reachable, LatencyMS: recorded.LatencyMS, ErrorCode: recorded.ErrorCode, ObservedAt: observed}
		if recorded.Result == adapter.ResultCommitted {
			result.ErrorCode = ""
		}
	}
	return result, true
}

func (c *Coordinator) record(ctx context.Context, reviewed uint64, key, digest string, result Result) Result {
	recorded := catalog.RecordedResult{Kind: digestKind(digest), Result: result.Result, ErrorCode: result.ErrorCode, ObservedUnix: c.now().Unix()}
	if result.Test != nil {
		recorded.Kind = "test"
		recorded.NodeID = result.Test.NodeID
		recorded.Reachable = result.Test.Reachable
		recorded.LatencyMS = result.Test.LatencyMS
		recorded.ErrorCode = result.Test.ErrorCode
		recorded.ObservedUnix = result.Test.ObservedAt.Unix()
	}
	document, err := c.store.RecordResult(ctx, reviewed, key, digest, recorded)
	if err != nil {
		return resultForStoreError(err)
	}
	result.Catalog = document
	return result
}

func (c *Coordinator) persistFailure(ctx context.Context, reviewed uint64, key, digest string, cause error) Result {
	result := Result{Result: adapter.ResultUncertain, ErrorCode: "catalog_persist_failed"}
	if !errors.Is(cause, catalog.ErrStaleState) && !errors.Is(cause, catalog.ErrOperationConflict) {
		if recorded := c.record(ctx, reviewed, key, digest, result); recorded.ErrorCode != "catalog_unavailable" {
			return recorded
		}
	}
	if document, err := c.store.Snapshot(ctx); err == nil {
		result.Catalog = document
	}
	return result
}

func resultForStoreError(err error) Result {
	switch {
	case errors.Is(err, catalog.ErrStaleState):
		return Result{Result: adapter.ResultRejected, ErrorCode: "stale_state"}
	case errors.Is(err, catalog.ErrOperationConflict):
		return Result{Result: adapter.ResultRejected, ErrorCode: "idempotency_conflict"}
	case errors.Is(err, catalog.ErrInvalid), errors.Is(err, catalog.ErrLimit):
		return Result{Result: adapter.ResultRejected, ErrorCode: "invalid_request"}
	default:
		return Result{Result: adapter.ResultUncertain, ErrorCode: "catalog_unavailable"}
	}
}

func fromOperation(operation adapter.OperationResult, fallback string) Result {
	result := operation.Result
	if result == "" {
		result = adapter.ResultUncertain
	}
	code := operation.ErrorCode
	if code == "" && result != adapter.ResultCommitted {
		code = fallback
	}
	return Result{Result: result, ErrorCode: code}
}

func (c *Coordinator) adapterLock(adapterID string) *sync.Mutex {
	c.locksMu.Lock()
	defer c.locksMu.Unlock()
	if lock := c.locks[adapterID]; lock != nil {
		return lock
	}
	lock := &sync.Mutex{}
	c.locks[adapterID] = lock
	return lock
}

func operationDigest(parts ...string) string {
	hash := sha256.New()
	for _, part := range parts {
		_, _ = fmt.Fprintln(hash, part)
	}
	return hex.EncodeToString(hash.Sum(nil))
}

func projectionDigest(projection adapter.Projection) string {
	hash := sha256.New()
	_, _ = fmt.Fprintf(hash, "%s\n%d\n", projection.AdapterID, projection.StateVersion)
	for _, source := range projection.Sources {
		_, _ = fmt.Fprintf(hash, "s:%s:%d\n", source.ID, source.AdapterStateVersion)
	}
	for _, node := range projection.Nodes {
		_, _ = fmt.Fprintf(hash, "n:%s:%t\n", node.ID, node.Active)
	}
	return hex.EncodeToString(hash.Sum(nil))
}

func digestKind(string) string { return "operation" }

func projectionContainsSource(projection adapter.Projection, sourceID string) bool {
	for _, source := range projection.Sources {
		if source.ID == sourceID {
			return true
		}
	}
	return false
}

func projectionContainsNode(projection adapter.Projection, nodeID string) bool {
	for _, node := range projection.Nodes {
		if node.ID == nodeID {
			return true
		}
	}
	return false
}

func projectionHasActiveNode(projection adapter.Projection, nodeID string) bool {
	for _, node := range projection.Nodes {
		if node.ID == nodeID {
			return node.Active
		}
	}
	return false
}

func xkeenVersion(document catalog.Document) uint64 {
	for _, source := range document.Sources {
		if source.Foreign && source.AdapterID == "xkeen" {
			return source.AdapterStateVersion
		}
	}
	return 0
}

func erase(value []byte) {
	for index := range value {
		value[index] = 0
	}
}
