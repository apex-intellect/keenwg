package adapter

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
)

func TestAWGManager216CookieSessionProjectionTestAndExactActivation(t *testing.T) {
	fixture := newAWGFixture(t)
	defer fixture.Close()
	adapter := NewAWGManagerAdapter(AWGManagerOptions{
		BaseURL: fixture.URL, Login: "admin", Password: "router-password",
	}, fixture.Client(), fixture.openapiHash)

	discovery := adapter.Discover(context.Background())
	if !discovery.Available || !discovery.Writable {
		t.Fatalf("discovery=%+v", discovery)
	}
	projection, err := adapter.Snapshot(context.Background())
	if err != nil || len(projection.Sources) != 1 || len(projection.Nodes) != 2 || !projection.Nodes[1].Active {
		t.Fatalf("projection=%+v err=%v", projection, err)
	}
	refreshed := adapter.Refresh(context.Background(), projection.Sources[0].ID)
	if refreshed.Result != ResultCommitted {
		t.Fatalf("refresh=%+v", refreshed)
	}
	body, _ := json.Marshal(projection)
	for _, forbidden := range []string{"router-password", "provider.invalid", "redacted", "awg_session"} {
		if strings.Contains(string(body), forbidden) {
			t.Fatalf("projection leaked %q: %s", forbidden, body)
		}
	}
	result := adapter.Test(context.Background(), projection.Nodes[0].ID)
	if !result.Reachable || result.LatencyMS != 41 {
		t.Fatalf("test=%+v", result)
	}
	plan, err := adapter.PlanActivation(context.Background(), projection.Nodes[0].ID, projection.StateVersion)
	if err != nil {
		t.Fatal(err)
	}
	activated := adapter.Activate(context.Background(), plan)
	if activated.Result != ResultCommitted || activated.NodeID != projection.Nodes[0].ID {
		t.Fatalf("activation=%+v", activated)
	}
	if err := adapter.Close(context.Background()); err != nil {
		t.Fatal(err)
	}

	fixture.assertRequestsUseSessionCookie(t)
	if fixture.loginCount() != 1 || fixture.logoutCount() != 1 || fixture.selectedMember() != "sub-europe-de" {
		t.Fatalf("login=%d logout=%d selected=%q", fixture.loginCount(), fixture.logoutCount(), fixture.selectedMember())
	}
}

func TestAWGManagerExpiredSessionIsDiscardedAndRelogged(t *testing.T) {
	fixture := newAWGFixture(t)
	defer fixture.Close()
	adapter := NewAWGManagerAdapter(AWGManagerOptions{BaseURL: fixture.URL, Login: "admin", Password: "secret"}, fixture.Client(), fixture.openapiHash)
	if _, err := adapter.Snapshot(context.Background()); err != nil {
		t.Fatal(err)
	}
	fixture.expireNextRequest()
	if _, err := adapter.Snapshot(context.Background()); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("expired session error=%v", err)
	}
	if _, err := adapter.Snapshot(context.Background()); err != nil {
		t.Fatalf("re-login snapshot error=%v", err)
	}
	if fixture.loginCount() != 2 {
		t.Fatalf("login count=%d", fixture.loginCount())
	}
}

func TestAWGManagerUnknownOpenAPIFailsClosedWithoutCatalogRead(t *testing.T) {
	fixture := newAWGFixture(t)
	defer fixture.Close()
	adapter := NewAWGManagerAdapter(AWGManagerOptions{BaseURL: fixture.URL, Login: "admin", Password: "secret"}, fixture.Client())
	discovery := adapter.Discover(context.Background())
	if discovery.Available || discovery.Writable || discovery.Reason != "awg_openapi_unsupported" {
		t.Fatalf("discovery=%+v", discovery)
	}
	if fixture.subscriptionReads() != 0 {
		t.Fatal("unsupported OpenAPI was followed by a subscription read")
	}
}

func TestAWGManagerRejectsNonHttpOnlyCookieAndDoesNotFollowRedirect(t *testing.T) {
	redirectedCookie := ""
	target := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) { redirectedCookie = r.Header.Get("Cookie") }))
	defer target.Close()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/auth/login" {
			http.SetCookie(w, &http.Cookie{Name: "awg_session", Value: "unsafe", Path: "/", SameSite: http.SameSiteStrictMode})
			_, _ = io.WriteString(w, `{"success":true,"login":"admin"}`)
			return
		}
		http.Redirect(w, r, target.URL, http.StatusFound)
	}))
	defer server.Close()
	adapter := NewAWGManagerAdapter(AWGManagerOptions{BaseURL: server.URL, Login: "admin", Password: "secret"}, server.Client())
	if discovery := adapter.Discover(context.Background()); discovery.Available || discovery.Reason != "awg_auth_failed" {
		t.Fatalf("discovery=%+v", discovery)
	}
	if redirectedCookie != "" {
		t.Fatalf("cookie escaped origin: %q", redirectedCookie)
	}
}

type awgFixture struct {
	*httptest.Server
	mu                sync.Mutex
	openapi           []byte
	openapiHash       string
	subscriptions     []byte
	session           string
	active            string
	logins, logouts   int
	subscriptionCalls int
	refreshCalls      int
	rejectNext        bool
	seen              []recordedAWGRequest
}

type recordedAWGRequest struct{ path, cookie string }

func newAWGFixture(t *testing.T) *awgFixture {
	t.Helper()
	openapi, err := os.ReadFile("testdata/awgmanager-2.16/openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	subscriptions, err := os.ReadFile("testdata/awgmanager-2.16/subscriptions.json")
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(openapi)
	fixture := &awgFixture{openapi: openapi, openapiHash: hex.EncodeToString(digest[:]), subscriptions: subscriptions, active: "sub-europe-nl"}
	fixture.Server = httptest.NewServer(http.HandlerFunc(fixture.serveHTTP))
	return fixture
}

func (f *awgFixture) serveHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.seen = append(f.seen, recordedAWGRequest{r.URL.Path, r.Header.Get("Cookie")})
	w.Header().Set("Content-Type", "application/json")
	if r.URL.Path == "/api/auth/login" {
		f.logins++
		f.session = "session-token"
		http.SetCookie(w, &http.Cookie{Name: "awg_session", Value: f.session, Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode, MaxAge: 3600})
		_, _ = io.WriteString(w, `{"success":true,"login":"admin"}`)
		return
	}
	if f.rejectNext {
		f.rejectNext = false
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	if r.Header.Get("Cookie") != "awg_session="+f.session {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	switch r.URL.Path {
	case "/api/openapi.yaml":
		w.Header().Set("Content-Type", "application/yaml")
		_, _ = w.Write(f.openapi)
	case "/api/auth/logout":
		f.logouts++
		_, _ = io.WriteString(w, `{"success":true}`)
	case "/api/singbox/subscriptions":
		f.subscriptionCalls++
		_, _ = w.Write(f.subscriptions)
	case "/api/singbox/subscriptions/active-now":
		_, _ = io.WriteString(w, `{"success":true,"data":{"now":"`+f.active+`"}}`)
	case "/api/singbox/router/proxies/test":
		_, _ = io.WriteString(w, `{"success":true,"data":{"delays":{"sub-europe-de":41,"sub-europe-nl":52}}}`)
	case "/api/singbox/subscriptions/active-member":
		body, _ := io.ReadAll(r.Body)
		if strings.Contains(string(body), `"memberTag":"sub-europe-de"`) {
			f.active = "sub-europe-de"
		}
		_, _ = io.WriteString(w, `{"success":true}`)
	case "/api/singbox/subscriptions/refresh":
		f.refreshCalls++
		_, _ = io.WriteString(w, `{"success":true,"data":{"when":"2026-08-09T00:00:00Z","added":0,"updated":2,"orphaned":0,"skippedVmess":0,"skippedOther":0,"skippedDuplicate":0,"parseErrors":[]}}`)
	default:
		http.NotFound(w, r)
	}
}

func (f *awgFixture) assertRequestsUseSessionCookie(t *testing.T) {
	t.Helper()
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, request := range f.seen {
		if request.path == "/api/auth/login" {
			if request.cookie != "" {
				t.Fatalf("login cookie=%q", request.cookie)
			}
			continue
		}
		if request.cookie != "awg_session=session-token" {
			t.Fatalf("%s cookie=%q", request.path, request.cookie)
		}
	}
}
func (f *awgFixture) loginCount() int  { f.mu.Lock(); defer f.mu.Unlock(); return f.logins }
func (f *awgFixture) logoutCount() int { f.mu.Lock(); defer f.mu.Unlock(); return f.logouts }
func (f *awgFixture) subscriptionReads() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.subscriptionCalls
}
func (f *awgFixture) selectedMember() string { f.mu.Lock(); defer f.mu.Unlock(); return f.active }
func (f *awgFixture) expireNextRequest()     { f.mu.Lock(); defer f.mu.Unlock(); f.rejectNext = true }
