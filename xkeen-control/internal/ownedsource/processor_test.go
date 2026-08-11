package ownedsource

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/adapter"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/catalog"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/diagnostics"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/model"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/transaction"
)

func TestPrepareProjectsDistinctNodesWithoutSecrets(t *testing.T) {
	first := ownedLink("11111111-2222-4333-8444-555555555555", "NL 1")
	second := ownedLink("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", "NL 2")
	processor := NewProcessor(&fakeFetcher{body: []byte(first + "\n" + second)}, &fakeDiagnostics{}, &fakeController{version: 7}, &fakeEngine{}, func() string { return "11111111-1111-4111-8111-111111111111" })

	prepared, err := processor.Prepare(context.Background(), "source-one", catalog.SourceSubscription, []byte("https://provider.example/private-subscription"))
	if err != nil || len(prepared.Nodes) != 2 || prepared.Nodes[0].ID == prepared.Nodes[1].ID {
		t.Fatalf("prepared=%+v err=%v", prepared, err)
	}
	public, err := json.Marshal(prepared.Nodes)
	if err != nil || bytes.Contains(public, []byte("11111111-2222")) || bytes.Contains(public, []byte("aaaaaaaa-bbbb")) ||
		bytes.Contains(public, []byte("public-key-private")) || bytes.Contains(public, []byte("private-subscription")) {
		t.Fatalf("secret leaked in projection: %s err=%v", public, err)
	}
	if !bytes.Contains(prepared.Payload, []byte("11111111-2222")) {
		t.Fatal("private prepared payload was not retained for atomic persistence")
	}
	prepared.Clear()
	if !allZero(prepared.Payload) {
		t.Fatal("prepared private payload was not erased")
	}
}

func TestTestUsesDiagnosticsOnlyAndActivateUsesExactExternalNode(t *testing.T) {
	payload := []byte(ownedLink("11111111-2222-4333-8444-555555555555", "NL 1"))
	diagnostic := &fakeDiagnostics{report: diagnostics.Report{CheckedAt: 100, Results: []diagnostics.NodeResult{{Status: diagnostics.StatusReachable, ConnectMS: 38}}}}
	controller := &fakeController{version: 7}
	engine := &fakeEngine{controller: controller}
	processor := NewProcessor(&fakeFetcher{}, diagnostic, controller, engine, func() string { return "22222222-2222-4222-8222-222222222222" })
	prepared, err := processor.Prepare(context.Background(), "source-one", catalog.SourceShareLink, append([]byte(nil), payload...))
	if err != nil {
		t.Fatal(err)
	}
	nodeID := prepared.Nodes[0].ID

	testResult := processor.Test(context.Background(), "source-one", nodeID, payload)
	if !testResult.Reachable || testResult.LatencyMS != 38 || engine.calls != 0 {
		t.Fatalf("test=%+v engine calls=%d", testResult, engine.calls)
	}
	result := processor.Activate(context.Background(), "source-one", nodeID, payload)
	if result.Result != adapter.ResultCommitted || engine.calls != 1 || engine.node.ID != nodeID || engine.node.UUID != "11111111-2222-4333-8444-555555555555" {
		t.Fatalf("result=%+v calls=%d node=%+v", result, engine.calls, engine.node)
	}
	active, version, err := processor.Readback(context.Background(), nodeID)
	if err != nil || !active || version != 8 {
		t.Fatalf("active=%v version=%d err=%v", active, version, err)
	}
}

func TestPrepareFailsClosedWhenSubscriptionFetchFails(t *testing.T) {
	processor := NewProcessor(&fakeFetcher{err: errors.New("offline")}, &fakeDiagnostics{}, &fakeController{version: 1}, &fakeEngine{}, nil)
	if _, err := processor.Prepare(context.Background(), "source-one", catalog.SourceSubscription, []byte("https://provider.example/private")); !errors.Is(err, ErrSourceUnavailable) {
		t.Fatalf("error=%v", err)
	}
}

func TestPrepareRejectsOversizedFetcherResponseIndependently(t *testing.T) {
	body := []byte(ownedLink("11111111-2222-4333-8444-555555555555", "NL") + string(bytes.Repeat([]byte{'x'}, maxOwnedSourceBytes)))
	processor := NewProcessor(&fakeFetcher{body: body}, &fakeDiagnostics{}, &fakeController{version: 1}, &fakeEngine{}, nil)

	prepared, err := processor.Prepare(context.Background(), "source-one", catalog.SourceSubscription, []byte("https://provider.example/private"))
	prepared.Clear()
	if !errors.Is(err, ErrSourceUnavailable) {
		t.Fatalf("oversized response error=%v nodes=%d", err, len(prepared.Nodes))
	}
}

func TestPrepareAcceptsBase64SubscriptionAndDNSFailureNeverActivates(t *testing.T) {
	encoded := base64.StdEncoding.EncodeToString([]byte(ownedLink("11111111-2222-4333-8444-555555555555", "NL")))
	diagnostic := &fakeDiagnostics{report: diagnostics.Report{CheckedAt: 100, Results: []diagnostics.NodeResult{{Status: diagnostics.StatusDNSError}}}}
	engine := &fakeEngine{}
	processor := NewProcessor(&fakeFetcher{body: []byte(encoded)}, diagnostic, &fakeController{version: 1}, engine, nil)
	prepared, err := processor.Prepare(context.Background(), "source-one", catalog.SourceSubscription, []byte("https://provider.example/private"))
	if err != nil || len(prepared.Nodes) != 1 {
		t.Fatalf("prepared=%+v err=%v", prepared, err)
	}
	result := processor.Test(context.Background(), "source-one", prepared.Nodes[0].ID, prepared.Payload)
	prepared.Clear()
	if result.Reachable || result.ErrorCode != diagnostics.StatusDNSError || engine.calls != 0 {
		t.Fatalf("result=%+v engine calls=%d", result, engine.calls)
	}
}

type fakeFetcher struct {
	body []byte
	err  error
}

func (f *fakeFetcher) Fetch(context.Context, string, int64) ([]byte, error) {
	return append([]byte(nil), f.body...), f.err
}

type fakeDiagnostics struct{ report diagnostics.Report }

func (f *fakeDiagnostics) Check(_ context.Context, nodes []model.Node) diagnostics.Report {
	result := f.report
	if len(result.Results) == 1 {
		result.Results[0].NodeID = nodes[0].ID
	}
	return result
}

type fakeController struct {
	version uint64
	active  *model.ActiveNode
}

func (f *fakeController) LoadControllerState() (model.ControllerState, error) {
	return model.ControllerState{StateVersion: f.version, Active: f.active}, nil
}
func (f *fakeController) FindOperation(string) (model.Operation, bool, error) {
	return model.Operation{State: model.OperationTerminal, Result: model.ResultSuccess}, true, nil
}

type fakeEngine struct {
	calls      int
	node       model.Node
	controller *fakeController
}

func (f *fakeEngine) PrepareSelectNode(_ string, node model.Node, reviewed uint64) (model.Operation, transaction.Job, error) {
	f.calls++
	f.node = node
	return model.Operation{}, func(context.Context) {
		active := model.SanitizeNode(node, true)
		f.controller.version = reviewed + 1
		f.controller.active = &model.ActiveNode{PublicNode: active}
	}, nil
}

func ownedLink(uuid, name string) string {
	return "vless://" + uuid + "@same.example:443?type=tcp&security=reality&flow=xtls-rprx-vision&fp=chrome&pbk=public-key-private&sid=0123456789abcdef&sni=cdn.example&spx=%2F#" + name
}

func allZero(value []byte) bool {
	for _, item := range value {
		if item != 0 {
			return false
		}
	}
	return true
}
