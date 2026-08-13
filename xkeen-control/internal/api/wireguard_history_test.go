package api

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/historyproxy"
)

const apiTestPeerID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

func TestSecureWireGuardHistoryAllowsViewerAndReturnsVersionedDocument(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	pairDevice(t, devices, auth.ScopeOwner, "Owner")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	service := &historyServiceStub{history: historyproxy.History{
		PeerID: apiTestPeerID, From: 100, To: 200, Resolution: "raw", Points: []historyproxy.Point{},
	}}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithWireGuardHistory(service))

	request := historyRequest(viewer.Token, `{"schema_version":1,"peer_id":"`+apiTestPeerID+`","from":100,"to":200,"resolution":"raw","limit":100}`)
	recorder := httptest.NewRecorder()
	secure.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.Bytes())
	}
	if service.calls != 1 || service.query.PeerID != apiTestPeerID || service.query.Limit != 100 {
		t.Fatalf("service=%+v", service)
	}
	if body := recorder.Body.String(); !strings.Contains(body, `"schema_version":1`) || !strings.Contains(body, `"history"`) {
		t.Fatalf("body=%s", body)
	}
}

func TestSecureWireGuardHistoryRequiresViewerAndStrictRequest(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	owner := pairDevice(t, devices, auth.ScopeOwner, "Owner")
	service := &historyServiceStub{}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithWireGuardHistory(service))

	tests := []struct {
		name, token, body, method string
		want                      int
	}{
		{"authentication", "", validHistoryRequestBody(), http.MethodPost, http.StatusUnauthorized},
		{"method", owner.Token, validHistoryRequestBody(), http.MethodGet, http.StatusMethodNotAllowed},
		{"unknown field", owner.Token, strings.TrimSuffix(validHistoryRequestBody(), "}") + `,"token":"leak"}`, http.MethodPost, http.StatusBadRequest},
		{"schema", owner.Token, strings.Replace(validHistoryRequestBody(), `"schema_version":1`, `"schema_version":2`, 1), http.MethodPost, http.StatusBadRequest},
		{"peer", owner.Token, strings.Replace(validHistoryRequestBody(), apiTestPeerID, "bad", 1), http.MethodPost, http.StatusBadRequest},
		{"range", owner.Token, strings.Replace(validHistoryRequestBody(), `"to":200`, `"to":100`, 1), http.MethodPost, http.StatusBadRequest},
		{"resolution", owner.Token, strings.Replace(validHistoryRequestBody(), `"raw"`, `"auto"`, 1), http.MethodPost, http.StatusBadRequest},
		{"limit", owner.Token, strings.Replace(validHistoryRequestBody(), `"limit":100`, `"limit":2001`, 1), http.MethodPost, http.StatusBadRequest},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			request := historyRequest(test.token, test.body)
			request.Method = test.method
			recorder := httptest.NewRecorder()
			secure.ServeHTTP(recorder, request)
			if recorder.Code != test.want {
				t.Fatalf("status=%d want=%d body=%s", recorder.Code, test.want, recorder.Body.Bytes())
			}
			if strings.Contains(recorder.Body.String(), "leak") {
				t.Fatalf("request data leaked: %s", recorder.Body.Bytes())
			}
		})
	}
	if service.calls != 0 {
		t.Fatalf("invalid requests reached service: %d", service.calls)
	}
}

func TestSecureWireGuardHistorySanitizesUpstreamFailure(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	owner := pairDevice(t, devices, auth.ScopeOwner, "Owner")
	service := &historyServiceStub{err: errors.New("token=router-secret path=/opt/etc/keenwg/config.json")}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithWireGuardHistory(service))
	recorder := httptest.NewRecorder()
	secure.ServeHTTP(recorder, historyRequest(owner.Token, validHistoryRequestBody()))
	if recorder.Code != http.StatusServiceUnavailable || recorder.Body.String() != "{\"error\":{\"code\":\"history_unavailable\"}}\n" {
		t.Fatalf("status=%d body=%q", recorder.Code, recorder.Body.String())
	}
}

type historyServiceStub struct {
	query   historyproxy.Query
	history historyproxy.History
	err     error
	calls   int
}

func (s *historyServiceStub) History(_ context.Context, query historyproxy.Query) (historyproxy.History, error) {
	s.calls++
	s.query = query
	return s.history, s.err
}

func historyRequest(token, body string) *http.Request {
	request := httptest.NewRequest(http.MethodPost, "/v1/access/wireguard/history/query", strings.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	if token != "" {
		request.Header.Set("Authorization", "Bearer "+token)
	}
	return request
}

func validHistoryRequestBody() string {
	return `{"schema_version":1,"peer_id":"` + apiTestPeerID + `","from":100,"to":200,"resolution":"raw","limit":100}`
}
