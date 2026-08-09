package adapter

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func TestSingBoxSnapshotTestAndActivateUseExactSelectorMembers(t *testing.T) {
	controller := newClashFixture(t)
	defer controller.Close()
	client := controller.Client()
	adapter := NewSingBoxAdapter(SingBoxOptions{
		ControllerURL: controller.URL, Secret: "clash-secret", Selector: "Main Route",
	}, client)

	projection, err := adapter.Snapshot(context.Background())
	if err != nil || len(projection.Nodes) != 2 || projection.Nodes[0].DisplayName != "Germany fast" {
		t.Fatalf("projection=%+v err=%v", projection, err)
	}
	tested := adapter.Test(context.Background(), projection.Nodes[0].ID)
	if !tested.Reachable || tested.LatencyMS != 37 {
		t.Fatalf("test=%+v", tested)
	}
	plan, err := adapter.PlanActivation(context.Background(), projection.Nodes[0].ID, projection.StateVersion)
	if err != nil {
		t.Fatal(err)
	}
	result := adapter.Activate(context.Background(), plan)
	if result.Result != ResultCommitted || result.NodeID != projection.Nodes[0].ID {
		t.Fatalf("activation=%+v", result)
	}

	requests := controller.requests()
	if !containsRequest(requests, "GET /proxies") || !containsRequest(requests, "GET /proxies/Germany%20fast/delay") ||
		!containsRequest(requests, "PUT /proxies/Main%20Route") {
		t.Fatalf("requests=%v", requests)
	}
	for _, header := range controller.authorizations() {
		if header != "Bearer clash-secret" {
			t.Fatalf("authorization=%q", header)
		}
	}
}

func TestSingBoxFailsClosedForUnsafeControllerAndUnknownProxyType(t *testing.T) {
	tests := []SingBoxOptions{
		{ControllerURL: "http://192.0.2.10:9090", Secret: "secret", Selector: "Main"},
		{ControllerURL: "http://0.0.0.0:9090", Secret: "secret", Selector: "Main"},
		{ControllerURL: "http://127.0.0.1:9090", Secret: "", Selector: "Main"},
	}
	for _, options := range tests {
		if discovery := NewSingBoxAdapter(options, nil).Discover(context.Background()); discovery.Available || discovery.Writable {
			t.Fatalf("unsafe options available: %+v => %+v", options, discovery)
		}
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"proxies":{"Main":{"type":"Selector","now":"Mystery","all":["Mystery"]},"Mystery":{"type":"FutureProxy"}}}`)
	}))
	defer server.Close()
	adapter := NewSingBoxAdapter(SingBoxOptions{ControllerURL: server.URL, Secret: "secret", Selector: "Main"}, server.Client())
	if _, err := adapter.Snapshot(context.Background()); !errors.Is(err, ErrUnsupportedSchema) {
		t.Fatalf("unknown type error=%v", err)
	}
}

func TestSingBoxRejectsStaleMembershipWithoutPUT(t *testing.T) {
	controller := newClashFixture(t)
	defer controller.Close()
	adapter := NewSingBoxAdapter(SingBoxOptions{ControllerURL: controller.URL, Secret: "secret", Selector: "Main Route"}, controller.Client())
	projection, err := adapter.Snapshot(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	plan, err := adapter.PlanActivation(context.Background(), projection.Nodes[0].ID, projection.StateVersion)
	if err != nil {
		t.Fatal(err)
	}
	controller.setMembers([]string{"Netherlands 1"})
	result := adapter.Activate(context.Background(), plan)
	if result.Result != ResultRejected || result.ErrorCode != "stale_state" || controller.puts() != 0 {
		t.Fatalf("result=%+v puts=%d", result, controller.puts())
	}
}

func TestSingBoxLostPUTResponseUsesReadback(t *testing.T) {
	controller := newClashFixture(t)
	defer controller.Close()
	baseClient := controller.Client()
	client := &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
		if request.Method == http.MethodPut {
			controller.setNow("Germany fast")
			return nil, io.ErrUnexpectedEOF
		}
		return baseClient.Transport.RoundTrip(request)
	})}
	adapter := NewSingBoxAdapter(SingBoxOptions{ControllerURL: controller.URL, Secret: "secret", Selector: "Main Route"}, client)
	projection, _ := adapter.Snapshot(context.Background())
	plan, _ := adapter.PlanActivation(context.Background(), projection.Nodes[0].ID, projection.StateVersion)
	result := adapter.Activate(context.Background(), plan)
	if result.Result != ResultCommitted {
		t.Fatalf("lost response readback=%+v", result)
	}
}

func TestSingBoxDoesNotFollowControllerRedirects(t *testing.T) {
	redirected := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/evil" {
			redirected++
			w.WriteHeader(http.StatusOK)
			return
		}
		http.Redirect(w, r, "/evil", http.StatusFound)
	}))
	defer server.Close()
	adapter := NewSingBoxAdapter(SingBoxOptions{ControllerURL: server.URL, Secret: "must-not-leak", Selector: "Main"}, server.Client())
	if _, err := adapter.Snapshot(context.Background()); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("redirect error=%v", err)
	}
	if redirected != 0 {
		t.Fatal("controller redirect was followed")
	}
}

type clashFixture struct {
	*httptest.Server
	mu      sync.Mutex
	now     string
	members []string
	log     []string
	auth    []string
	put     int
}

func newClashFixture(t *testing.T) *clashFixture {
	t.Helper()
	fixture := &clashFixture{now: "Netherlands 1", members: []string{"Germany fast", "Netherlands 1"}}
	fixture.Server = httptest.NewServer(http.HandlerFunc(fixture.serveHTTP))
	return fixture
}

func (f *clashFixture) serveHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.log = append(f.log, r.Method+" "+r.URL.EscapedPath())
	f.auth = append(f.auth, r.Header.Get("Authorization"))
	w.Header().Set("Content-Type", "application/json")
	switch {
	case r.Method == http.MethodGet && r.URL.Path == "/proxies":
		_, _ = io.WriteString(w, f.snapshotJSON())
	case r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/delay"):
		_, _ = io.WriteString(w, `{"delay":37}`)
	case r.Method == http.MethodPut && r.URL.Path == "/proxies/Main Route":
		body, _ := io.ReadAll(r.Body)
		if strings.Contains(string(body), `"name":"Germany fast"`) {
			f.now = "Germany fast"
		}
		f.put++
		w.WriteHeader(http.StatusNoContent)
	default:
		http.NotFound(w, r)
	}
}

func (f *clashFixture) snapshotJSON() string {
	members := make([]string, len(f.members))
	for index, member := range f.members {
		members[index] = `"` + member + `"`
	}
	return `{"proxies":{"Main Route":{"type":"Selector","now":"` + f.now + `","all":[` + strings.Join(members, ",") + `]},` +
		`"Germany fast":{"type":"VLESS"},"Netherlands 1":{"type":"Trojan"}}}`
}

func (f *clashFixture) requests() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.log...)
}
func (f *clashFixture) authorizations() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.auth...)
}
func (f *clashFixture) setMembers(values []string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.members = append([]string(nil), values...)
}
func (f *clashFixture) setNow(value string) { f.mu.Lock(); defer f.mu.Unlock(); f.now = value }
func (f *clashFixture) puts() int           { f.mu.Lock(); defer f.mu.Unlock(); return f.put }

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) { return f(request) }

func containsRequest(values []string, prefix string) bool {
	for _, value := range values {
		if strings.HasPrefix(value, prefix) {
			return true
		}
	}
	return false
}
