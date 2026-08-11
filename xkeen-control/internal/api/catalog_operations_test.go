package api

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/adapter"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/catalog"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/connection"
)

func TestConnectionOperationRoutesRequireOperatorAndPreserveExactIDs(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	pairDevice(t, devices, auth.ScopeOwner, "Owner")
	operator := pairFromOwner(t, devices, auth.ScopeOperator, "Operator")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	coordinator := &fakeConnectionCoordinator{result: connection.Result{
		Result:  adapter.ResultCommitted,
		Catalog: catalog.Document{SchemaVersion: 1, StateVersion: 9, Groups: []catalog.Group{}, Sources: []catalog.Source{}, Nodes: []catalog.Node{}},
		Test:    &adapter.TestResult{NodeID: "node-exact", Reachable: true, LatencyMS: 35, ObservedAt: time.Unix(100, 0).UTC()},
	}}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithCatalog(newAPICatalogStore(t)), WithConnectionCoordinator(coordinator))
	body := `{"schema_version":1,"state_version":8,"idempotency_key":"operation-key-0001"}`

	forbidden := httptest.NewRecorder()
	secure.ServeHTTP(forbidden, catalogRequest(http.MethodPost, "/v1/connections/nodes/node-exact/test", viewer.Token, body))
	if forbidden.Code != http.StatusForbidden {
		t.Fatalf("viewer status=%d body=%s", forbidden.Code, forbidden.Body.Bytes())
	}

	tests := []struct {
		path string
		kind string
		id   string
	}{
		{"/v1/connections/sources/source-exact/refresh", "refresh", "source-exact"},
		{"/v1/connections/nodes/node-exact/test", "test", "node-exact"},
		{"/v1/connections/nodes/node-exact/activate", "activate", "node-exact"},
	}
	for _, test := range tests {
		recorder := httptest.NewRecorder()
		secure.ServeHTTP(recorder, catalogRequest(http.MethodPost, test.path, operator.Token, body))
		if recorder.Code != http.StatusOK || !bytes.Contains(recorder.Body.Bytes(), []byte(`"result":"committed"`)) {
			t.Fatalf("%s status=%d body=%s", test.kind, recorder.Code, recorder.Body.Bytes())
		}
		call := coordinator.calls[len(coordinator.calls)-1]
		if call.kind != test.kind || call.id != test.id || call.reviewed != 8 || call.key != "operation-key-0001" {
			t.Fatalf("call=%+v", call)
		}
	}
}

func TestConnectionOperationRoutesRejectUnknownJSONAndMapTypedResults(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	pairDevice(t, devices, auth.ScopeOwner, "Owner")
	operator := pairFromOwner(t, devices, auth.ScopeOperator, "Operator")
	coordinator := &fakeConnectionCoordinator{result: connection.Result{Result: adapter.ResultRejected, ErrorCode: "stale_state"}}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithCatalog(newAPICatalogStore(t)), WithConnectionCoordinator(coordinator))

	bad := httptest.NewRecorder()
	secure.ServeHTTP(bad, catalogRequest(http.MethodPost, "/v1/connections/nodes/node-a/activate", operator.Token,
		`{"schema_version":1,"state_version":2,"idempotency_key":"operation-key-0002","extra":true}`))
	if bad.Code != http.StatusBadRequest || len(coordinator.calls) != 0 {
		t.Fatalf("bad status=%d calls=%d body=%s", bad.Code, len(coordinator.calls), bad.Body.Bytes())
	}

	rejected := httptest.NewRecorder()
	secure.ServeHTTP(rejected, catalogRequest(http.MethodPost, "/v1/connections/nodes/node-a/activate", operator.Token,
		`{"schema_version":1,"state_version":2,"idempotency_key":"operation-key-0002"}`))
	if rejected.Code != http.StatusConflict || !bytes.Contains(rejected.Body.Bytes(), []byte(`"error":"stale_state"`)) {
		t.Fatalf("rejected status=%d body=%s", rejected.Code, rejected.Body.Bytes())
	}

	coordinator.result = connection.Result{Result: adapter.ResultUncertain, ErrorCode: "activation_readback_failed"}
	uncertain := httptest.NewRecorder()
	secure.ServeHTTP(uncertain, catalogRequest(http.MethodPost, "/v1/connections/nodes/node-a/activate", operator.Token,
		`{"schema_version":1,"state_version":2,"idempotency_key":"operation-key-0003"}`))
	if uncertain.Code != http.StatusServiceUnavailable {
		t.Fatalf("uncertain status=%d body=%s", uncertain.Code, uncertain.Body.Bytes())
	}
}

type connectionCall struct {
	kind     string
	id       string
	reviewed uint64
	key      string
}

type fakeConnectionCoordinator struct {
	result connection.Result
	calls  []connectionCall
}

func (f *fakeConnectionCoordinator) RefreshSource(_ context.Context, reviewed uint64, key, id string) connection.Result {
	f.calls = append(f.calls, connectionCall{"refresh", id, reviewed, key})
	return f.result
}

func (f *fakeConnectionCoordinator) TestNode(_ context.Context, reviewed uint64, key, id string) connection.Result {
	f.calls = append(f.calls, connectionCall{"test", id, reviewed, key})
	return f.result
}

func (f *fakeConnectionCoordinator) ActivateNode(_ context.Context, reviewed uint64, key, id string) connection.Result {
	f.calls = append(f.calls, connectionCall{"activate", id, reviewed, key})
	return f.result
}
