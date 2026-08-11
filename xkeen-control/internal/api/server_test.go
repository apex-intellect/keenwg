package api

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/diagnostics"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/domainpolicy"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/exclusions"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/model"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/state"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/transaction"
)

const controlToken = "control-token-0123456789abcdef"

func TestStatusRequiresInjectedDevicePrincipalAndNeverLeaksSecrets(t *testing.T) {
	server, store, _ := newTestServer(t)
	privateNode := model.Node{
		ID: strings.Repeat("ab", 16), CanonicalURI: "vless://private-uri-secret", DisplayName: "Нидерланды 1", Host: "nl1.example.test", Port: 443,
		UUID: "11111111-1111-4111-8111-111111111111", PublicKey: "private-public-key", ShortID: "0123456789abcdef", SNI: "private-sni.example.test", SpiderX: "/private-spider",
		Fingerprint: "firefox", Transport: "tcp", Security: "reality", Flow: "xtls-rprx-vision",
	}
	if _, err := store.SaveSubscription([]model.Node{privateNode}, time.Unix(100, 0)); err != nil {
		t.Fatal(err)
	}
	active := model.SanitizeNode(privateNode, true)
	if err := store.SaveControllerState(model.ControllerState{StateVersion: 7, Active: &model.ActiveNode{PublicNode: active, ResolvedIP: "203.0.113.44", ConfirmedAt: 100}}); err != nil {
		t.Fatal(err)
	}

	for _, test := range []struct {
		name      string
		principal *Principal
		auth      string
		want      int
	}{
		{"missing", nil, "", http.StatusUnauthorized},
		{"raw controller token", nil, "Bearer " + controlToken, http.StatusUnauthorized},
		{"viewer principal", &Principal{DeviceID: "viewer", Scope: "viewer"}, "", http.StatusOK},
	} {
		t.Run(test.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/v1/xkeen/status", nil)
			req.Header.Set("Authorization", test.auth)
			if test.principal != nil {
				req = withPrincipal(req, *test.principal)
			}
			recorder := httptest.NewRecorder()
			server.ServeHTTP(recorder, req)
			body := recorder.Body.Bytes()
			if recorder.Code != test.want {
				t.Fatalf("status=%d body=%s", recorder.Code, body)
			}
			for _, secret := range []string{"11111111-1111", "private-public-key", "0123456789abcdef", "private-sni", "private-uri", "private-spider"} {
				if bytes.Contains(body, []byte(secret)) {
					t.Fatalf("secret response: %s", body)
				}
			}
			if recorder.Header().Get("Cache-Control") != "no-store" || recorder.Header().Get("X-Content-Type-Options") != "nosniff" {
				t.Fatalf("headers=%v", recorder.Header())
			}
			if test.want == http.StatusOK && bytes.Count(body, []byte(`"warnings":[]`)) != 2 {
				t.Fatalf("empty warnings must be arrays: %s", body)
			}
		})
	}
}

func TestSelectReturns202AndOperationCanBePolled(t *testing.T) {
	server, store, engine := newTestServer(t)
	key := "11111111-1111-4111-8111-111111111111"
	body := `{"state_version":7,"idempotency_key":"` + key + `"}`
	req := authenticatedRequest(http.MethodPost, "/v1/xkeen/nodes/"+strings.Repeat("ab", 16)+"/select", strings.NewReader(body))
	recorder := httptest.NewRecorder()
	server.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusAccepted {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.Bytes())
	}
	select {
	case <-engine.completed:
	case <-time.After(2 * time.Second):
		t.Fatal("detached operation did not run")
	}
	poll := authenticatedRequest(http.MethodGet, "/v1/xkeen/operations/"+key, nil)
	pollRecorder := httptest.NewRecorder()
	server.ServeHTTP(pollRecorder, poll)
	if pollRecorder.Code != http.StatusOK || !bytes.Contains(pollRecorder.Body.Bytes(), []byte(`"result":"success"`)) {
		t.Fatalf("status=%d body=%s", pollRecorder.Code, pollRecorder.Body.Bytes())
	}
	op, found, err := store.FindOperation(key)
	if err != nil || !found || op.Result != model.ResultSuccess || engine.selectCalls != 1 {
		t.Fatalf("op=%+v found=%v err=%v calls=%d", op, found, err, engine.selectCalls)
	}
}

func TestDuplicateMutationReturnsSameOperationWithoutSecondJob(t *testing.T) {
	server, _, engine := newTestServer(t)
	key := "22222222-2222-4222-8222-222222222222"
	body := `{"state_version":7,"idempotency_key":"` + key + `"}`
	for i := 0; i < 2; i++ {
		recorder := httptest.NewRecorder()
		server.ServeHTTP(recorder, authenticatedRequest(http.MethodPost, "/v1/xkeen/subscription/refresh", strings.NewReader(body)))
		if recorder.Code != http.StatusAccepted {
			t.Fatalf("attempt=%d status=%d body=%s", i, recorder.Code, recorder.Body.Bytes())
		}
	}
	select {
	case <-engine.completed:
	case <-time.After(2 * time.Second):
		t.Fatal("operation did not complete")
	}
	if engine.refreshCalls != 2 || engine.jobsRun != 1 {
		t.Fatalf("prepare calls=%d jobs=%d", engine.refreshCalls, engine.jobsRun)
	}
}

func TestMutationRejectsUnsafeBodiesAndMethods(t *testing.T) {
	server, _, _ := newTestServer(t)
	valid := `{"state_version":7,"idempotency_key":"11111111-1111-4111-8111-111111111111"}`
	tests := []struct {
		name        string
		method      string
		path        string
		body        string
		contentType string
		want        int
	}{
		{"unknown field", http.MethodPost, "/v1/xkeen/subscription/refresh", strings.TrimSuffix(valid, "}") + `,"uuid":"secret"}`, "application/json", http.StatusBadRequest},
		{"query", http.MethodPost, "/v1/xkeen/subscription/refresh?force=true", valid, "application/json", http.StatusBadRequest},
		{"wrong content type", http.MethodPost, "/v1/xkeen/subscription/refresh", valid, "text/plain", http.StatusBadRequest},
		{"oversize", http.MethodPost, "/v1/xkeen/subscription/refresh", strings.Repeat("x", 4097), "application/json", http.StatusRequestEntityTooLarge},
		{"wrong method", http.MethodPut, "/v1/xkeen/subscription/refresh", valid, "application/json", http.StatusMethodNotAllowed},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			req := authenticatedRequest(test.method, test.path, strings.NewReader(test.body))
			req.Header.Set("Content-Type", test.contentType)
			recorder := httptest.NewRecorder()
			server.ServeHTTP(recorder, req)
			if recorder.Code != test.want || bytes.Contains(recorder.Body.Bytes(), []byte("secret")) {
				t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.Bytes())
			}
		})
	}
}

func TestHealthIsPublicAndContainsNoControllerState(t *testing.T) {
	server, _, _ := newTestServer(t)
	recorder := httptest.NewRecorder()
	server.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/v1/xkeen/health", nil))
	if recorder.Code != http.StatusOK || recorder.Body.String() != "{\"status\":\"ok\",\"storage\":\"ok\",\"version\":\"0.4.0\"}\n" {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
}

func TestDiagnosticsRequiresAuthAndReturnsSanitizedArray(t *testing.T) {
	server, store, _ := newTestServer(t)
	node := model.Node{ID: strings.Repeat("ab", 16), CanonicalURI: "vless://secret", UUID: "private-uuid", Host: "nl.example", Port: 443}
	if _, err := store.SaveSubscription([]model.Node{node}, time.Unix(100, 0)); err != nil {
		t.Fatal(err)
	}
	server.diagnostics = fakeDiagnostics{report: diagnostics.Report{
		SchemaVersion: 1,
		CheckedAt:     100,
		Results:       []diagnostics.NodeResult{{NodeID: node.ID, Host: node.Host, Port: 443, ResolvedIP: "192.0.2.1", ConnectMS: 17, Status: diagnostics.StatusReachable}},
	}}

	unauthorized := httptest.NewRecorder()
	server.ServeHTTP(unauthorized, httptest.NewRequest(http.MethodPost, "/v1/diagnostics/nodes", strings.NewReader(`{}`)))
	if unauthorized.Code != http.StatusUnauthorized {
		t.Fatalf("unauthorized status=%d", unauthorized.Code)
	}

	recorder := httptest.NewRecorder()
	server.ServeHTTP(recorder, authenticatedRequest(http.MethodPost, "/v1/diagnostics/nodes", strings.NewReader(`{}`)))
	if recorder.Code != http.StatusOK || !bytes.Contains(recorder.Body.Bytes(), []byte(`"results":[{`)) {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.Bytes())
	}
	if bytes.Contains(recorder.Body.Bytes(), []byte("secret")) || bytes.Contains(recorder.Body.Bytes(), []byte("private-uuid")) {
		t.Fatalf("secret leaked: %s", recorder.Body.Bytes())
	}
}

func TestDiagnosticsRejectsUnknownInputAndWrongMethod(t *testing.T) {
	server, _, _ := newTestServer(t)
	for _, request := range []*http.Request{
		authenticatedRequest(http.MethodGet, "/v1/diagnostics/nodes", nil),
		authenticatedRequest(http.MethodPost, "/v1/diagnostics/nodes", strings.NewReader(`{"switch":true}`)),
	} {
		recorder := httptest.NewRecorder()
		server.ServeHTTP(recorder, request)
		if recorder.Code < 400 {
			t.Fatalf("unexpected success: %d %s", recorder.Code, recorder.Body.Bytes())
		}
	}
}

func TestExclusionsAPIListsAndMutates(t *testing.T) {
	server, _, _ := newTestServer(t)
	manager := &fakeExclusions{status: exclusions.Status{
		SchemaVersion: 1, StateVersion: 9,
		Entries: []exclusions.Entry{{ID: "a", Value: "203.0.113.10/32", Protected: true}}, Warnings: []string{},
	}}
	server.exclusions = manager
	get := httptest.NewRecorder()
	server.ServeHTTP(get, authenticatedRequest(http.MethodGet, "/v1/network/exclusions", nil))
	if get.Code != http.StatusOK || !bytes.Contains(get.Body.Bytes(), []byte(`"protected":true`)) {
		t.Fatalf("get=%d %s", get.Code, get.Body.Bytes())
	}
	post := httptest.NewRecorder()
	server.ServeHTTP(post, authenticatedRequest(http.MethodPost, "/v1/network/exclusions", strings.NewReader(`{"state_version":9,"action":"add","value":"198.18.0.0/15"}`)))
	if post.Code != http.StatusOK || manager.last.Value != "198.18.0.0/15" {
		t.Fatalf("post=%d body=%s last=%#v", post.Code, post.Body.Bytes(), manager.last)
	}
}

func TestDomainPolicyAPIListsAndMapsRuleMutations(t *testing.T) {
	server, _, _ := newTestServer(t)
	manager := &fakeDomainPolicy{status: domainpolicy.NewStatus(11,
		[]domainpolicy.Rule{{ID: "rule-a", Kind: "domain", Value: "okko.sport", Effect: "direct", Label: "Okko", Enabled: true, Source: "manual"}},
		[]domainpolicy.Preset{{ID: "category-gov-ru", Label: "Госсайты РФ", Matcher: "ext:geosite_v2fly.dat:category-gov-ru", Available: true, Enabled: true}}, nil)}
	server.domains = manager

	get := httptest.NewRecorder()
	server.ServeHTTP(get, authenticatedRequest(http.MethodGet, "/v1/network/domains", nil))
	if get.Code != http.StatusOK || !bytes.Contains(get.Body.Bytes(), []byte(`"rules":[{`)) || !bytes.Contains(get.Body.Bytes(), []byte(`"warnings":[]`)) {
		t.Fatalf("get=%d %s", get.Code, get.Body.Bytes())
	}

	draft := `{"kind":"domain","value":"example.com","effect":"vpn","label":"Example","enabled":true,"source":"manual","protected":false,"id":""}`
	requests := []struct{ method, path, body, action, id string }{
		{http.MethodPost, "/v1/network/domains/rules", `{"state_version":11,"idempotency_key":"create-rule-01","rule":` + draft + `}`, "create", ""},
		{http.MethodPut, "/v1/network/domains/rules/rule-a", `{"state_version":11,"idempotency_key":"update-rule-01","rule":` + draft + `}`, "update", "rule-a"},
		{http.MethodDelete, "/v1/network/domains/rules/rule-a", `{"state_version":11,"idempotency_key":"delete-rule-01"}`, "delete", "rule-a"},
	}
	for _, test := range requests {
		recorder := httptest.NewRecorder()
		server.ServeHTTP(recorder, authenticatedRequest(test.method, test.path, strings.NewReader(test.body)))
		if recorder.Code != http.StatusOK || manager.last.Action != test.action || manager.last.RuleID != test.id {
			t.Fatalf("%s %s status=%d body=%s last=%+v", test.method, test.path, recorder.Code, recorder.Body.Bytes(), manager.last)
		}
	}
}

func TestDomainPolicyAPIRejectsUnsafeInputAndMapsResults(t *testing.T) {
	server, _, _ := newTestServer(t)
	manager := &fakeDomainPolicy{status: domainpolicy.NewStatus(11, nil, nil, nil)}
	server.domains = manager

	bad := httptest.NewRecorder()
	server.ServeHTTP(bad, authenticatedRequest(http.MethodPost, "/v1/network/domains/rules", strings.NewReader(`{"state_version":11,"idempotency_key":"create-rule-01","shell":"reboot"}`)))
	if bad.Code != http.StatusBadRequest {
		t.Fatalf("bad=%d %s", bad.Code, bad.Body.Bytes())
	}

	manager.result = "rejected"
	rejected := httptest.NewRecorder()
	server.ServeHTTP(rejected, authenticatedRequest(http.MethodDelete, "/v1/network/domains/rules/rule-a", strings.NewReader(`{"state_version":11,"idempotency_key":"delete-rule-01"}`)))
	if rejected.Code != http.StatusConflict {
		t.Fatalf("rejected=%d", rejected.Code)
	}

	manager.result = "uncertain"
	uncertain := httptest.NewRecorder()
	server.ServeHTTP(uncertain, authenticatedRequest(http.MethodDelete, "/v1/network/domains/rules/rule-a", strings.NewReader(`{"state_version":11,"idempotency_key":"delete-rule-02"}`)))
	if uncertain.Code != http.StatusServiceUnavailable {
		t.Fatalf("uncertain=%d", uncertain.Code)
	}
}

func newTestServer(t *testing.T) (*Server, *state.Store, *fakeEngine) {
	t.Helper()
	dir := t.TempDir()
	store := state.New(state.Paths{Subscription: filepath.Join(dir, "subscription.json"), State: filepath.Join(dir, "state.json")}, strings.NewReader(strings.Repeat("ab", 512)))
	if err := store.SaveControllerState(model.ControllerState{StateVersion: 7}); err != nil {
		t.Fatal(err)
	}
	engine := &fakeEngine{store: store, completed: make(chan struct{}, 4)}
	server := NewCore("0.4.0", engine, store)
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := server.Shutdown(ctx); err != nil {
			t.Errorf("server shutdown: %v", err)
		}
	})
	return server, store, engine
}

func authenticatedRequest(method, path string, body io.Reader) *http.Request {
	req := httptest.NewRequest(method, path, body)
	req = withPrincipal(req, Principal{DeviceID: "owner", Scope: "owner"})
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	return req
}

type fakeEngine struct {
	store        *state.Store
	completed    chan struct{}
	refreshCalls int
	selectCalls  int
	jobsRun      int
}

type fakeDiagnostics struct{ report diagnostics.Report }

func (f fakeDiagnostics) Check(context.Context, []model.Node) diagnostics.Report { return f.report }

type fakeExclusions struct {
	status exclusions.Status
	last   exclusions.Mutation
}

type fakeDomainPolicy struct {
	status domainpolicy.Status
	last   domainpolicy.Mutation
	result string
}

func (f *fakeDomainPolicy) Status(context.Context) (domainpolicy.Status, error) { return f.status, nil }
func (f *fakeDomainPolicy) Mutate(_ context.Context, mutation domainpolicy.Mutation) domainpolicy.Result {
	f.last = mutation
	result := f.result
	if result == "" {
		result = "committed"
	}
	return domainpolicy.Result{Result: result, Status: f.status}
}

func (f *fakeExclusions) Status() (exclusions.Status, error) { return f.status, nil }
func (f *fakeExclusions) Mutate(_ context.Context, mutation exclusions.Mutation) exclusions.Result {
	f.last = mutation
	return exclusions.Result{Result: "committed", Status: f.status}
}

func (f *fakeEngine) PrepareRefresh(key string, _ uint64) (model.Operation, transaction.Job, error) {
	f.refreshCalls++
	return f.prepare(key, "refresh")
}

func (f *fakeEngine) PrepareSelect(key, _ string, _ uint64) (model.Operation, transaction.Job, error) {
	f.selectCalls++
	return f.prepare(key, "select")
}

func (f *fakeEngine) prepare(key, kind string) (model.Operation, transaction.Job, error) {
	if existing, found, err := f.store.FindOperation(key); err != nil {
		return model.Operation{}, nil, err
	} else if found {
		return existing, nil, nil
	}
	op := model.Operation{IdempotencyKey: key, Kind: kind, State: model.OperationQueued, StartedAt: 100}
	if err := f.store.BeginOperation(op, &model.TransactionSnapshot{OperationKey: key, Kind: kind, Phase: "queued"}); err != nil {
		return model.Operation{}, nil, err
	}
	job := func(context.Context) {
		f.jobsRun++
		finished := int64(101)
		op.State = model.OperationTerminal
		op.Result = model.ResultSuccess
		op.FinishedAt = &finished
		_ = f.store.UpdateOperation(op, nil)
		f.completed <- struct{}{}
	}
	return op, job, nil
}

func decodeBody(t *testing.T, body []byte) map[string]any {
	t.Helper()
	var value map[string]any
	if err := json.Unmarshal(body, &value); err != nil {
		t.Fatal(err)
	}
	return value
}
