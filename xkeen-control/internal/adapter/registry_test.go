package adapter

import (
	"context"
	"errors"
	"reflect"
	"testing"

	"github.com/goldb/keenwg/xkeen-control/internal/catalog"
)

func TestRegistrySortsAdaptersAndRejectsDuplicateNodeIDs(t *testing.T) {
	registry, err := NewRegistry(
		&fakeAdapter{id: "zeta", projection: projection("zeta", 4, "source-z", "node-shared")},
		&fakeAdapter{id: "alpha", projection: projection("alpha", 7, "source-a", "node-a")},
	)
	if err != nil {
		t.Fatal(err)
	}
	if got := registry.AdapterIDs(); !reflect.DeepEqual(got, []string{"alpha", "zeta"}) {
		t.Fatalf("adapter order=%v", got)
	}
	first, err := registry.Snapshot(context.Background())
	if err != nil || len(first.Projection.Nodes) != 2 || first.Projection.Nodes[0].ID != "node-a" {
		t.Fatalf("snapshot=%+v err=%v", first, err)
	}

	duplicate, _ := NewRegistry(
		&fakeAdapter{id: "alpha", projection: projection("alpha", 1, "source-a", "same-node")},
		&fakeAdapter{id: "zeta", projection: projection("zeta", 1, "source-z", "same-node")},
	)
	if _, err := duplicate.Snapshot(context.Background()); !errors.Is(err, ErrDuplicateNode) {
		t.Fatalf("duplicate error=%v", err)
	}
}

func TestRegistryIsolatesOptionalFailureAndRequiresReviewedState(t *testing.T) {
	healthy := &fakeAdapter{id: "healthy", projection: projection("healthy", 9, "source-ok", "node-ok")}
	broken := &fakeAdapter{id: "optional", snapshotErr: errors.New("offline")}
	registry, err := NewRegistry(broken, healthy)
	if err != nil {
		t.Fatal(err)
	}
	snapshot, err := registry.Snapshot(context.Background())
	if err != nil || len(snapshot.Projection.Nodes) != 1 || snapshot.Adapters[1].Discovery.Available {
		t.Fatalf("snapshot=%+v err=%v", snapshot, err)
	}
	if _, err := registry.PlanActivation(context.Background(), "node-ok", 8); !errors.Is(err, ErrStaleState) {
		t.Fatalf("stale error=%v", err)
	}
	plan, err := registry.PlanActivation(context.Background(), "node-ok", 9)
	if err != nil || plan.NodeID != "node-ok" || plan.AdapterID != "healthy" {
		t.Fatalf("plan=%+v err=%v", plan, err)
	}
}

func TestRegistryRejectsDuplicateAdapterAndBoundsProjection(t *testing.T) {
	if _, err := NewRegistry(&fakeAdapter{id: "same"}, &fakeAdapter{id: "same"}); !errors.Is(err, ErrDuplicateAdapter) {
		t.Fatalf("duplicate adapter error=%v", err)
	}
	tooMany := projection("large", 1, "source-large", "node-0")
	tooMany.Nodes = make([]catalog.Node, MaxProjectionNodes+1)
	registry, _ := NewRegistry(&fakeAdapter{id: "large", projection: tooMany})
	if _, err := registry.Snapshot(context.Background()); !errors.Is(err, ErrProjectionLimit) {
		t.Fatalf("limit error=%v", err)
	}
}

func TestSnapshotAdapterKeepsPreviousRoutingOnCollision(t *testing.T) {
	alpha := &fakeAdapter{id: "alpha", projection: projection("alpha", 1, "source-a", "node-a")}
	beta := &fakeAdapter{id: "beta", projection: projection("beta", 1, "source-b", "node-b")}
	registry, err := NewRegistry(alpha, beta)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := registry.Snapshot(context.Background()); err != nil {
		t.Fatal(err)
	}
	alpha.projection = projection("alpha", 2, "source-a", "node-b")
	if _, err := registry.SnapshotAdapter(context.Background(), "alpha"); !errors.Is(err, ErrDuplicateNode) {
		t.Fatalf("collision error=%v", err)
	}
	plan, err := registry.PlanActivation(context.Background(), "node-a", 1)
	if err != nil || plan.AdapterID != "alpha" {
		t.Fatalf("previous routing was lost: plan=%+v err=%v", plan, err)
	}
}

type fakeAdapter struct {
	id          string
	projection  Projection
	snapshotErr error
}

func (f *fakeAdapter) ID() string { return f.id }
func (f *fakeAdapter) Discover(context.Context) Discovery {
	return Discovery{Available: true, Writable: true}
}
func (f *fakeAdapter) Snapshot(context.Context) (Projection, error) {
	return f.projection, f.snapshotErr
}
func (f *fakeAdapter) Test(context.Context, string) TestResult { return TestResult{} }
func (f *fakeAdapter) PlanActivation(_ context.Context, nodeID string, reviewed uint64) (ActivationPlan, error) {
	return ActivationPlan{AdapterID: f.id, NodeID: nodeID, ReviewedStateVersion: reviewed}, nil
}
func (f *fakeAdapter) Activate(context.Context, ActivationPlan) OperationResult {
	return OperationResult{Result: ResultCommitted}
}

func projection(adapterID string, version uint64, sourceID, nodeID string) Projection {
	return Projection{
		AdapterID: adapterID, StateVersion: version,
		Sources: []catalog.Source{{
			ID: sourceID, GroupID: "primary", Kind: catalog.SourceForeign, Label: adapterID,
			AdapterID: adapterID, Status: catalog.SourceReady, NodeCount: 1, Warnings: []string{}, Foreign: true,
			AdapterStateVersion: version,
		}},
		Nodes: []catalog.Node{{
			ID: nodeID, SourceID: sourceID, GroupID: "primary", DisplayName: nodeID,
			Protocol: catalog.ProtocolVLESS, Host: "vpn.example", Port: 443,
			Testable: true, Activatable: true, Warnings: []string{},
		}},
	}
}
