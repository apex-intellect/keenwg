package connection

import (
	"bytes"
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/adapter"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/catalog"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/ownedsource"
)

func TestSyncAdapterSeedsForeignProjection(t *testing.T) {
	store := newCoordinatorStore(t)
	engine := &fakeRegistry{projection: connectionProjection(3, "node-a", false)}
	coordinator := NewCoordinator(store, engine, func() time.Time { return time.Unix(100, 0) })

	if err := coordinator.SyncAdapter(context.Background(), "engine"); err != nil {
		t.Fatal(err)
	}
	document, _ := store.Snapshot(context.Background())
	if document.StateVersion != 2 || len(document.Sources) != 1 || len(document.Nodes) != 1 ||
		document.Sources[0].AdapterStateVersion != 3 {
		t.Fatalf("catalog=%+v", document)
	}
}

func TestRefreshFailurePreservesPreviousNodes(t *testing.T) {
	store := newCoordinatorStore(t)
	engine := &fakeRegistry{projection: connectionProjection(3, "node-old", false)}
	coordinator := NewCoordinator(store, engine, nil)
	if err := coordinator.SyncAdapter(context.Background(), "engine"); err != nil {
		t.Fatal(err)
	}
	document, _ := store.Snapshot(context.Background())
	engine.refreshResult = adapter.OperationResult{Result: adapter.ResultRejected, ErrorCode: "refresh_failed"}
	engine.refreshErr = errors.New("adapter read failed")

	result := coordinator.RefreshSource(context.Background(), document.StateVersion, "refresh-source-0001", "source-engine")
	after, _ := store.Snapshot(context.Background())
	if result.Result != adapter.ResultRejected || result.ErrorCode != "refresh_failed" ||
		len(after.Nodes) != 1 || after.Nodes[0].ID != "node-old" || after.StateVersion != document.StateVersion {
		t.Fatalf("result=%+v catalog=%+v", result, after)
	}
}

func TestRefreshOwnedSourceUsesPrivateProcessorAndPersistsReadyProjection(t *testing.T) {
	store := newCoordinatorStore(t)
	document, err := store.CreateSource(context.Background(), 1, "create-owned-0001", catalog.SourceDraft{
		GroupID: "primary", Kind: catalog.SourceShareLink, Label: "Personal", AdapterID: "catalog",
	}, []byte("vless://private@vpn.example:443"))
	if err != nil {
		t.Fatal(err)
	}
	sourceID := document.Sources[0].ID
	owned := &fakeOwnedProcessor{prepared: ownedsource.Prepared{
		Nodes:   []catalog.Node{{ID: "owned-node", SourceID: sourceID, GroupID: "primary", DisplayName: "NL", Protocol: catalog.ProtocolVLESS, Host: "vpn.example", Port: 443, Testable: true, Activatable: true, Warnings: []string{}}},
		Payload: []byte("vless://private-native@vpn.example:443"),
	}}
	coordinator := NewCoordinator(store, &fakeRegistry{}, nil, owned)

	result := coordinator.RefreshSource(context.Background(), document.StateVersion, "refresh-owned-0001", sourceID)
	after, _ := store.Snapshot(context.Background())
	private, privateErr := store.SourceProjection(context.Background(), sourceID)
	if result.Result != adapter.ResultCommitted || len(after.Nodes) != 1 || after.Nodes[0].ID != "owned-node" ||
		after.Sources[0].Status != catalog.SourceReady || privateErr != nil || !bytes.Contains(private, []byte("private-native")) || owned.prepareCalls != 1 {
		t.Fatalf("result=%+v catalog=%+v private=%q privateErr=%v calls=%d", result, after, private, privateErr, owned.prepareCalls)
	}
}

func TestNodeTestIsReplaySafeAndNeverActivates(t *testing.T) {
	store := newCoordinatorStore(t)
	engine := &fakeRegistry{
		projection: connectionProjection(3, "node-a", false),
		testResult: adapter.TestResult{NodeID: "node-a", Reachable: true, LatencyMS: 41, ObservedAt: time.Unix(200, 0)},
	}
	coordinator := NewCoordinator(store, engine, nil)
	if err := coordinator.SyncAdapter(context.Background(), "engine"); err != nil {
		t.Fatal(err)
	}
	document, _ := store.Snapshot(context.Background())

	first := coordinator.TestNode(context.Background(), document.StateVersion, "test-node-0001", "node-a")
	second := coordinator.TestNode(context.Background(), document.StateVersion, "test-node-0001", "node-a")
	after, _ := store.Snapshot(context.Background())
	if first.Result != adapter.ResultCommitted || second.Test == nil || !second.Test.Reachable || engine.testCalls != 1 ||
		engine.activateCalls != 0 || after.StateVersion != document.StateVersion {
		t.Fatalf("first=%+v second=%+v calls=%d/%d catalog=%+v", first, second, engine.testCalls, engine.activateCalls, after)
	}
}

func TestOwnedNodeTestAndActivationUsePrivateProjectionAndExactReadback(t *testing.T) {
	store := newCoordinatorStore(t)
	projection := connectionProjection(3, "xkeen-node", true)
	projection.AdapterID = "xkeen"
	projection.Sources[0].AdapterID = "xkeen"
	registry := &fakeRegistry{projection: projection}
	owned := &fakeOwnedProcessor{
		testResult:     adapter.TestResult{NodeID: "owned-node", Reachable: true, LatencyMS: 29, ObservedAt: time.Unix(200, 0)},
		activateResult: adapter.OperationResult{Result: adapter.ResultCommitted, NodeID: "owned-node"},
		readActive:     true, readVersion: 4,
	}
	coordinator := NewCoordinator(store, registry, nil, owned)
	if err := coordinator.SyncAdapter(context.Background(), "xkeen"); err != nil {
		t.Fatal(err)
	}
	document, err := store.CreateSource(context.Background(), 2, "create-owned-0003", catalog.SourceDraft{GroupID: "primary", Kind: catalog.SourceShareLink, Label: "Personal", AdapterID: "catalog"}, []byte("private"))
	if err != nil {
		t.Fatal(err)
	}
	sourceID := document.Sources[1].ID
	owned.prepared = ownedsource.Prepared{Nodes: []catalog.Node{{ID: "owned-node", SourceID: sourceID, GroupID: "primary", DisplayName: "Owned", Protocol: catalog.ProtocolVLESS, Host: "owned.example", Port: 443, Testable: true, Activatable: true, Warnings: []string{}}}, Payload: []byte("private-projection")}
	refresh := coordinator.RefreshSource(context.Background(), document.StateVersion, "refresh-owned-0003", sourceID)
	if refresh.Result != adapter.ResultCommitted {
		t.Fatalf("refresh=%+v", refresh)
	}

	tested := coordinator.TestNode(context.Background(), refresh.Catalog.StateVersion, "test-owned-0003", "owned-node")
	if tested.Result != adapter.ResultCommitted || tested.Test == nil || !tested.Test.Reachable || owned.activateCalls != 0 {
		t.Fatalf("tested=%+v activateCalls=%d", tested, owned.activateCalls)
	}
	owned.readVersion = 10
	stale := coordinator.ActivateNode(context.Background(), refresh.Catalog.StateVersion, "activate-owned-stale-0003", "owned-node")
	if stale.Result != adapter.ResultRejected || stale.ErrorCode != "stale_adapter_state" || owned.activateCalls != 0 {
		t.Fatalf("stale=%+v calls=%d", stale, owned.activateCalls)
	}
	owned.readVersion = 4
	owned.activateResult = adapter.OperationResult{Result: adapter.ResultUncertain, ErrorCode: "lost_response", NodeID: "owned-node"}
	activated := coordinator.ActivateNode(context.Background(), refresh.Catalog.StateVersion, "activate-owned-0003", "owned-node")
	if activated.Result != adapter.ResultCommitted || owned.activateCalls != 1 {
		t.Fatalf("activated=%+v calls=%d", activated, owned.activateCalls)
	}
	active := []string{}
	for _, node := range activated.Catalog.Nodes {
		if node.Active {
			active = append(active, node.ID)
		}
	}
	if len(active) != 1 || active[0] != "owned-node" {
		t.Fatalf("active=%v", active)
	}
}

func TestNodeTestRejectsChangedAdapterRevision(t *testing.T) {
	store := newCoordinatorStore(t)
	engine := &fakeRegistry{projection: connectionProjection(3, "node-a", false)}
	coordinator := NewCoordinator(store, engine, nil)
	if err := coordinator.SyncAdapter(context.Background(), "engine"); err != nil {
		t.Fatal(err)
	}
	document, _ := store.Snapshot(context.Background())
	engine.projection = connectionProjection(4, "node-a", false)

	result := coordinator.TestNode(context.Background(), document.StateVersion, "test-node-0002", "node-a")
	if result.Result != adapter.ResultRejected || result.ErrorCode != "stale_adapter_state" || engine.testCalls != 0 {
		t.Fatalf("result=%+v test calls=%d", result, engine.testCalls)
	}
}

func TestActivationUsesExactNodeAndReportsPersistenceFailureAsUncertain(t *testing.T) {
	realStore := newCoordinatorStore(t)
	store := &failingStore{Store: realStore}
	engine := &fakeRegistry{projection: connectionProjection(3, "node-a", false)}
	coordinator := NewCoordinator(store, engine, nil)
	if err := coordinator.SyncAdapter(context.Background(), "engine"); err != nil {
		t.Fatal(err)
	}
	document, _ := store.Snapshot(context.Background())
	engine.afterActivate = connectionProjection(4, "node-a", true)
	store.failReplace = true

	result := coordinator.ActivateNode(context.Background(), document.StateVersion, "activate-node-0001", "node-a")
	after, _ := store.Snapshot(context.Background())
	if result.Result != adapter.ResultUncertain || result.ErrorCode != "catalog_persist_failed" ||
		engine.plannedNode != "node-a" || engine.activateCalls != 1 || after.Nodes[0].Active {
		t.Fatalf("result=%+v planned=%q calls=%d catalog=%+v", result, engine.plannedNode, engine.activateCalls, after)
	}
}

type coordinatorStore interface {
	Snapshot(context.Context) (catalog.Document, error)
	ReplaceAdapterProjection(context.Context, uint64, string, string, string, []catalog.Source, []catalog.Node, catalog.RecordedResult) (catalog.Document, error)
	RecordResult(context.Context, uint64, string, string, catalog.RecordedResult) (catalog.Document, error)
	LookupResult(context.Context, string, string) (catalog.RecordedResult, bool, error)
}

type failingStore struct {
	*catalog.Store
	failReplace bool
}

func (s *failingStore) ReplaceAdapterProjection(ctx context.Context, reviewed uint64, key, digest, adapterID string, sources []catalog.Source, nodes []catalog.Node, result catalog.RecordedResult) (catalog.Document, error) {
	if s.failReplace {
		return catalog.Document{}, catalog.ErrStorage
	}
	return s.Store.ReplaceAdapterProjection(ctx, reviewed, key, digest, adapterID, sources, nodes, result)
}

type fakeRegistry struct {
	projection    adapter.Projection
	refreshResult adapter.OperationResult
	refreshErr    error
	testResult    adapter.TestResult
	afterActivate adapter.Projection
	testCalls     int
	activateCalls int
	plannedNode   string
}

type fakeOwnedProcessor struct {
	prepared       ownedsource.Prepared
	prepareCalls   int
	testResult     adapter.TestResult
	activateResult adapter.OperationResult
	activateCalls  int
	readActive     bool
	readVersion    uint64
}

func (f *fakeOwnedProcessor) Prepare(context.Context, string, catalog.SourceKind, []byte) (ownedsource.Prepared, error) {
	f.prepareCalls++
	return f.prepared, nil
}

func (f *fakeOwnedProcessor) Test(context.Context, string, string, []byte) adapter.TestResult {
	return f.testResult
}
func (f *fakeOwnedProcessor) Activate(context.Context, string, string, []byte) adapter.OperationResult {
	f.activateCalls++
	return f.activateResult
}
func (f *fakeOwnedProcessor) Readback(context.Context, string) (bool, uint64, error) {
	if f.activateCalls == 0 {
		return false, f.readVersion - 1, nil
	}
	return f.readActive, f.readVersion, nil
}

func (f *fakeRegistry) SnapshotAdapter(context.Context, string) (adapter.Projection, error) {
	return f.projection, nil
}

func (f *fakeRegistry) RefreshSource(context.Context, string, string) (adapter.OperationResult, adapter.Projection, error) {
	if f.refreshResult.Result == "" {
		f.refreshResult.Result = adapter.ResultCommitted
	}
	return f.refreshResult, f.projection, f.refreshErr
}

func (f *fakeRegistry) Test(_ context.Context, nodeID string) adapter.TestResult {
	f.testCalls++
	result := f.testResult
	if result.NodeID == "" {
		result.NodeID = nodeID
	}
	return result
}

func (f *fakeRegistry) PlanActivation(_ context.Context, nodeID string, reviewed uint64) (adapter.ActivationPlan, error) {
	f.plannedNode = nodeID
	return adapter.ActivationPlan{AdapterID: "engine", NodeID: nodeID, ReviewedStateVersion: reviewed}, nil
}

func (f *fakeRegistry) Activate(context.Context, adapter.ActivationPlan) adapter.OperationResult {
	f.activateCalls++
	if f.afterActivate.StateVersion != 0 {
		f.projection = f.afterActivate
	}
	return adapter.OperationResult{Result: adapter.ResultCommitted, NodeID: f.plannedNode}
}

func newCoordinatorStore(t *testing.T) *catalog.Store {
	t.Helper()
	directory := t.TempDir()
	store, err := catalog.NewStore(catalog.Paths{Catalog: filepath.Join(directory, "catalog.json"), Secrets: filepath.Join(directory, "secrets.json")}, nil)
	if err != nil {
		t.Fatal(err)
	}
	return store
}

func connectionProjection(version uint64, nodeID string, active bool) adapter.Projection {
	return adapter.Projection{AdapterID: "engine", StateVersion: version,
		Sources: []catalog.Source{{ID: "source-engine", GroupID: "primary", Kind: catalog.SourceForeign, Label: "Engine", AdapterID: "engine", Status: catalog.SourceReady, NodeCount: 1, Warnings: []string{}, Foreign: true, AdapterStateVersion: version}},
		Nodes:   []catalog.Node{{ID: nodeID, SourceID: "source-engine", GroupID: "primary", DisplayName: nodeID, Protocol: catalog.ProtocolVLESS, Host: "vpn.example", Port: 443, Active: active, Testable: true, Activatable: true, Warnings: []string{}}},
	}
}
