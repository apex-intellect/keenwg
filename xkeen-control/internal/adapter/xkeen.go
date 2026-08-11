package adapter

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/catalog"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/diagnostics"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/model"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/transaction"
)

const (
	xkeenAdapterID = "xkeen"
	xkeenSourceID  = "xkeen-subscription"
)

type XKeenStore interface {
	LoadSubscription() (model.SubscriptionState, error)
	LoadControllerState() (model.ControllerState, error)
	FindOperation(string) (model.Operation, bool, error)
}

type XKeenEngine interface {
	PrepareRefresh(string, uint64) (model.Operation, transaction.Job, error)
	PrepareSelect(string, string, uint64) (model.Operation, transaction.Job, error)
}

type XKeenDiagnostics interface {
	Check(context.Context, []model.Node) diagnostics.Report
}

type XKeenAdapter struct {
	store       XKeenStore
	engine      XKeenEngine
	diagnostics XKeenDiagnostics
	keyFactory  func() string
}

func NewXKeenAdapter(store XKeenStore, engine XKeenEngine, checker XKeenDiagnostics, keyFactory func() string) *XKeenAdapter {
	if keyFactory == nil {
		keyFactory = randomOperationKey
	}
	return &XKeenAdapter{store: store, engine: engine, diagnostics: checker, keyFactory: keyFactory}
}

func (x *XKeenAdapter) ID() string { return xkeenAdapterID }

func (x *XKeenAdapter) Discover(ctx context.Context) Discovery {
	if x == nil || x.store == nil || x.engine == nil || x.diagnostics == nil {
		return Discovery{Reason: "xkeen_unconfigured"}
	}
	if err := ctx.Err(); err != nil {
		return Discovery{Reason: "cancelled"}
	}
	if _, err := x.store.LoadControllerState(); err != nil {
		return Discovery{Reason: "xkeen_unavailable"}
	}
	return Discovery{Available: true, Writable: true}
}

func (x *XKeenAdapter) Snapshot(ctx context.Context) (Projection, error) {
	if err := ctx.Err(); err != nil {
		return Projection{}, err
	}
	subscription, err := x.store.LoadSubscription()
	if err != nil {
		return Projection{}, ErrUnavailable
	}
	controller, err := x.store.LoadControllerState()
	if err != nil || controller.StateVersion == 0 {
		return Projection{}, ErrUnavailable
	}
	activeID := ""
	if controller.Active != nil {
		activeID = controller.Active.ID
	}
	nodes := make([]catalog.Node, len(subscription.Nodes))
	for index, node := range subscription.Nodes {
		nodes[index] = projectXKeenNode(node, node.ID == activeID)
	}
	var refreshed *time.Time
	if subscription.RefreshedAt > 0 {
		value := time.Unix(subscription.RefreshedAt, 0).UTC()
		refreshed = &value
	}
	return Projection{
		AdapterID: xkeenAdapterID, StateVersion: controller.StateVersion,
		Sources: []catalog.Source{{
			ID: xkeenSourceID, GroupID: "primary", Kind: catalog.SourceForeign, Label: "XKeen",
			AdapterID: xkeenAdapterID, Status: catalog.SourceReady, NodeCount: len(nodes),
			LastRefresh: refreshed, Warnings: []string{}, Foreign: true, AdapterStateVersion: controller.StateVersion,
		}},
		Nodes: nodes,
	}, nil
}

func (x *XKeenAdapter) Test(ctx context.Context, nodeID string) TestResult {
	node, err := x.nativeNode(ctx, nodeID)
	if err != nil {
		return TestResult{NodeID: nodeID, ErrorCode: "node_not_found"}
	}
	report := x.diagnostics.Check(ctx, []model.Node{node})
	if len(report.Results) != 1 || report.Results[0].NodeID != node.ID {
		return TestResult{NodeID: nodeID, ErrorCode: "diagnostics_unavailable"}
	}
	result := report.Results[0]
	answer := TestResult{
		NodeID: nodeID, Reachable: result.Status == diagnostics.StatusReachable,
		LatencyMS: result.ConnectMS, ObservedAt: time.Unix(report.CheckedAt, 0).UTC(),
	}
	if !answer.Reachable {
		answer.ErrorCode = result.Status
	}
	return answer
}

func (x *XKeenAdapter) Refresh(ctx context.Context, sourceID string) OperationResult {
	if sourceID != xkeenSourceID {
		return OperationResult{Result: ResultRejected, ErrorCode: "source_not_found"}
	}
	controller, err := x.store.LoadControllerState()
	if err != nil {
		return OperationResult{Result: ResultUncertain, ErrorCode: "xkeen_unavailable"}
	}
	key := x.keyFactory()
	if !validXKeenOperationKey(key) {
		return OperationResult{Result: ResultUncertain, ErrorCode: "operation_key_failed"}
	}
	operation, job, err := x.engine.PrepareRefresh(key, controller.StateVersion)
	if err != nil {
		return xkeenPreparationFailure(err)
	}
	if job != nil {
		job(ctx)
		var found bool
		operation, found, err = x.store.FindOperation(key)
		if err != nil || !found {
			return OperationResult{Result: ResultUncertain, ErrorCode: "operation_unavailable"}
		}
	}
	return xkeenOperationResult("", operation)
}

func (x *XKeenAdapter) PlanActivation(ctx context.Context, nodeID string, reviewed uint64) (ActivationPlan, error) {
	if err := ctx.Err(); err != nil {
		return ActivationPlan{}, err
	}
	controller, err := x.store.LoadControllerState()
	if err != nil {
		return ActivationPlan{}, ErrUnavailable
	}
	if controller.StateVersion != reviewed {
		return ActivationPlan{}, ErrStaleState
	}
	node, err := x.nativeNode(ctx, nodeID)
	if err != nil {
		return ActivationPlan{}, err
	}
	key := x.keyFactory()
	if !validXKeenOperationKey(key) {
		return ActivationPlan{}, ErrProjection
	}
	previous := ""
	if controller.Active != nil {
		previous = projectXKeenNodeID(controller.Active.ID)
	}
	return ActivationPlan{
		AdapterID: xkeenAdapterID, NodeID: nodeID, ReviewedStateVersion: reviewed,
		PreviousNodeID: previous, Opaque: node.ID + "\n" + key,
	}, nil
}

func (x *XKeenAdapter) Activate(ctx context.Context, plan ActivationPlan) OperationResult {
	if plan.AdapterID != xkeenAdapterID || plan.NodeID == "" {
		return OperationResult{Result: ResultRejected, ErrorCode: "invalid_plan"}
	}
	parts := strings.SplitN(plan.Opaque, "\n", 2)
	if len(parts) != 2 || parts[0] == "" || !validXKeenOperationKey(parts[1]) {
		return OperationResult{Result: ResultRejected, ErrorCode: "invalid_plan"}
	}
	operation, job, err := x.engine.PrepareSelect(parts[1], parts[0], plan.ReviewedStateVersion)
	if err != nil {
		return xkeenPreparationFailure(err)
	}
	if job != nil {
		job(ctx)
		var found bool
		operation, found, err = x.store.FindOperation(parts[1])
		if err != nil || !found {
			return OperationResult{Result: ResultUncertain, ErrorCode: "operation_unavailable"}
		}
	}
	return xkeenOperationResult(plan.NodeID, operation)
}

func (x *XKeenAdapter) nativeNode(ctx context.Context, projectedID string) (model.Node, error) {
	if err := ctx.Err(); err != nil {
		return model.Node{}, err
	}
	subscription, err := x.store.LoadSubscription()
	if err != nil {
		return model.Node{}, ErrUnavailable
	}
	for _, node := range subscription.Nodes {
		if projectXKeenNodeID(node.ID) == projectedID {
			return node, nil
		}
	}
	return model.Node{}, ErrNodeNotFound
}

func projectXKeenNode(node model.Node, active bool) catalog.Node {
	return catalog.Node{
		ID: projectXKeenNodeID(node.ID), SourceID: xkeenSourceID, GroupID: "primary",
		DisplayName: node.DisplayName, Country: node.Country, Protocol: catalog.ProtocolVLESS,
		Host: node.Host, Port: node.Port, Transport: node.Transport, Security: node.Security,
		ServerName: node.SNI, Flow: node.Flow, VariantFingerprint: node.Fingerprint,
		Active: active, Testable: true, Activatable: true, Warnings: append([]string(nil), node.Warnings...),
	}
}

func projectXKeenNodeID(nativeID string) string {
	digest := sha256.Sum256([]byte("xkeen\x00" + nativeID))
	return "xkeen-" + hex.EncodeToString(digest[:16])
}

func xkeenPreparationFailure(err error) OperationResult {
	switch {
	case errors.Is(err, transaction.ErrStaleState):
		return OperationResult{Result: ResultRejected, ErrorCode: "stale_state"}
	case errors.Is(err, transaction.ErrNodeNotFound):
		return OperationResult{Result: ResultRejected, ErrorCode: "node_not_found"}
	case errors.Is(err, transaction.ErrBusy):
		return OperationResult{Result: ResultRejected, ErrorCode: "busy"}
	default:
		return OperationResult{Result: ResultUncertain, ErrorCode: "xkeen_unavailable"}
	}
}

func xkeenOperationResult(nodeID string, operation model.Operation) OperationResult {
	switch operation.Result {
	case model.ResultSuccess:
		return OperationResult{Result: ResultCommitted, NodeID: nodeID}
	case model.ResultFailedRolledBack:
		return OperationResult{Result: ResultRolledBack, ErrorCode: operation.ErrorCode}
	case model.ResultFailedNoChange:
		return OperationResult{Result: ResultRejected, ErrorCode: operation.ErrorCode}
	default:
		return OperationResult{Result: ResultUncertain, ErrorCode: operation.ErrorCode}
	}
}

func randomOperationKey() string {
	value := make([]byte, 16)
	if _, err := rand.Read(value); err != nil {
		return ""
	}
	value[6] = (value[6] & 0x0f) | 0x40
	value[8] = (value[8] & 0x3f) | 0x80
	encoded := hex.EncodeToString(value)
	return fmt.Sprintf("%s-%s-%s-%s-%s", encoded[:8], encoded[8:12], encoded[12:16], encoded[16:20], encoded[20:])
}

func validXKeenOperationKey(value string) bool {
	if len(value) != 36 {
		return false
	}
	for index, character := range value {
		if index == 8 || index == 13 || index == 18 || index == 23 {
			if character != '-' {
				return false
			}
			continue
		}
		if character < '0' || (character > '9' && character < 'a') || character > 'f' {
			return false
		}
	}
	return true
}
