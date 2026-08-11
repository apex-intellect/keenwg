package adapter

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/diagnostics"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/model"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/transaction"
)

func TestXKeenProjectionKeepsDuplicateCountriesExactAndSanitized(t *testing.T) {
	store := &fakeXKeenStore{
		subscription: model.SubscriptionState{RefreshedAt: 1_786_147_200, Nodes: []model.Node{
			xkeenNode("node-one", "Netherlands 1", "NL", "first-private-uuid", "vpn-one.example"),
			xkeenNode("node-two", "Netherlands 2", "NL", "second-private-uuid", "vpn-two.example"),
		}},
		controller: model.ControllerState{StateVersion: 12, Active: &model.ActiveNode{PublicNode: model.PublicNode{ID: "node-two"}}},
	}
	adapter := NewXKeenAdapter(store, &fakeXKeenEngine{}, &fakeXKeenDiagnostics{}, func() string { return "11111111-1111-4111-8111-111111111111" })

	projection, err := adapter.Snapshot(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(projection.Nodes) != 2 || projection.Nodes[0].ID == projection.Nodes[1].ID ||
		projection.Nodes[0].Country != "NL" || projection.Nodes[1].Country != "NL" || !projection.Nodes[1].Active {
		t.Fatalf("projection=%+v", projection)
	}
	body, _ := json.Marshal(projection)
	for _, forbidden := range []string{"first-private-uuid", "second-private-uuid", "canonical_uri", "public_key", "short_id"} {
		if stringContains(string(body), forbidden) {
			t.Fatalf("projection leaked %q: %s", forbidden, body)
		}
	}
}

func TestXKeenTestDoesNotActivateAndActivationUsesReviewedExactNode(t *testing.T) {
	store := &fakeXKeenStore{
		subscription: model.SubscriptionState{Nodes: []model.Node{xkeenNode("native-node", "Germany", "DE", "private", "de.example")}},
		controller:   model.ControllerState{StateVersion: 44},
	}
	engine := &fakeXKeenEngine{operation: model.Operation{State: model.OperationTerminal, Result: model.ResultSuccess}}
	diagnostic := &fakeXKeenDiagnostics{report: diagnostics.Report{CheckedAt: 1_786_147_210, Results: []diagnostics.NodeResult{{
		NodeID: "native-node", ConnectMS: 23, Status: diagnostics.StatusReachable,
	}}}}
	adapter := NewXKeenAdapter(store, engine, diagnostic, func() string { return "22222222-2222-4222-8222-222222222222" })
	projection, err := adapter.Snapshot(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	projectedID := projection.Nodes[0].ID

	result := adapter.Test(context.Background(), projectedID)
	if !result.Reachable || result.LatencyMS != 23 || engine.calls != 0 || diagnostic.nodes[0].ID != "native-node" {
		t.Fatalf("test=%+v engine=%d diagnostics=%+v", result, engine.calls, diagnostic.nodes)
	}
	plan, err := adapter.PlanActivation(context.Background(), projectedID, 44)
	if err != nil {
		t.Fatal(err)
	}
	activated := adapter.Activate(context.Background(), plan)
	if activated.Result != ResultCommitted || engine.nodeID != "native-node" || engine.reviewed != 44 || engine.calls != 1 {
		t.Fatalf("activated=%+v engine=%+v", activated, engine)
	}
	if _, err := adapter.PlanActivation(context.Background(), projectedID, 43); err != ErrStaleState {
		t.Fatalf("stale error=%v", err)
	}
}

type fakeXKeenStore struct {
	subscription model.SubscriptionState
	controller   model.ControllerState
	operation    model.Operation
}

func (f *fakeXKeenStore) LoadSubscription() (model.SubscriptionState, error) {
	return f.subscription, nil
}
func (f *fakeXKeenStore) LoadControllerState() (model.ControllerState, error) {
	return f.controller, nil
}
func (f *fakeXKeenStore) FindOperation(string) (model.Operation, bool, error) {
	return f.operation, f.operation.State != "", nil
}

type fakeXKeenEngine struct {
	calls     int
	nodeID    string
	reviewed  uint64
	operation model.Operation
}

func (f *fakeXKeenEngine) PrepareSelect(_ string, nodeID string, reviewed uint64) (model.Operation, transaction.Job, error) {
	f.calls++
	f.nodeID = nodeID
	f.reviewed = reviewed
	return f.operation, nil, nil
}

func (f *fakeXKeenEngine) PrepareRefresh(_ string, reviewed uint64) (model.Operation, transaction.Job, error) {
	f.calls++
	f.reviewed = reviewed
	return f.operation, nil, nil
}

type fakeXKeenDiagnostics struct {
	nodes  []model.Node
	report diagnostics.Report
}

func (f *fakeXKeenDiagnostics) Check(_ context.Context, nodes []model.Node) diagnostics.Report {
	f.nodes = append([]model.Node(nil), nodes...)
	return f.report
}

func xkeenNode(id, name, country, uuid, host string) model.Node {
	return model.Node{
		ID: id, CanonicalURI: "vless://" + uuid + "@" + host + ":443", DisplayName: name, Country: country,
		Host: host, Port: 443, UUID: uuid, PublicKey: "private-public-key", ShortID: "private-short-id",
		SNI: "cdn.example", Fingerprint: "firefox", Transport: "tcp", Security: "reality", Flow: "xtls-rprx-vision",
	}
}

func stringContains(value, fragment string) bool {
	for index := 0; index+len(fragment) <= len(value); index++ {
		if value[index:index+len(fragment)] == fragment {
			return true
		}
	}
	return false
}
