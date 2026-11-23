package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/capability"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/transaction"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/xray"
)

type runtimeAPIEngine struct {
	*fakeEngine
	running        bool
	err            error
	mutations      int
	ignoreMutation bool
}

func (e *runtimeAPIEngine) RuntimeStatus(context.Context) (bool, error) { return e.running, e.err }
func (e *runtimeAPIEngine) SetRuntime(_ context.Context, enabled bool) (bool, error) {
	e.mutations++
	if e.err == nil && !e.ignoreMutation {
		e.running = enabled
	}
	return e.running, e.err
}

func TestSecureRuntimeOwnerMutationAndReadOnlyCapabilities(t *testing.T) {
	core, _, base := newTestServer(t)
	runtime := &runtimeAPIEngine{fakeEngine: base, running: true}
	core.engine = runtime
	devices := newSecureDeviceStore(t)
	owner := pairDevice(t, devices, auth.ScopeOwner, "Owner")
	operator := pairFromOwner(t, devices, auth.ScopeOperator, "Operator")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	document := staticCapabilities().document
	document.Capabilities = append(document.Capabilities, capability.Capability{ID: capability.XKeenRuntime, SchemaVersion: 1, Access: capability.AccessWrite, Available: true, Transport: "companion"})
	secure := NewSecure(core, devices, fixedCapabilities{document: document})
	for _, test := range []struct {
		token  string
		access capability.Access
		post   int
	}{{owner.Token, capability.AccessWrite, 200}, {operator.Token, capability.AccessRead, 403}, {viewer.Token, capability.AccessRead, 403}} {
		get := httptest.NewRecorder()
		secure.ServeHTTP(get, catalogRequest(http.MethodGet, "/v1/xkeen/runtime", test.token, ""))
		if get.Code != 200 || !strings.Contains(get.Body.String(), `"runtime_only":true`) {
			t.Fatalf("get=%d %s", get.Code, get.Body.String())
		}
		post := httptest.NewRecorder()
		secure.ServeHTTP(post, catalogRequest(http.MethodPost, "/v1/xkeen/runtime", test.token, `{"enabled":false}`))
		if post.Code != test.post {
			t.Fatalf("post=%d want=%d body=%s", post.Code, test.post, post.Body.String())
		}
		caps := httptest.NewRecorder()
		secure.ServeHTTP(caps, catalogRequest(http.MethodGet, "/v1/capabilities", test.token, ""))
		var got capability.Document
		if err := json.Unmarshal(caps.Body.Bytes(), &got); err != nil {
			t.Fatal(err)
		}
		found := false
		for _, entry := range got.Capabilities {
			if entry.ID == capability.XKeenRuntime {
				found = true
				if entry.Access != test.access || !entry.Available {
					t.Fatalf("capability=%+v", entry)
				}
			}
		}
		if !found {
			t.Fatal("runtime capability missing")
		}
	}
	if runtime.mutations != 1 {
		t.Fatalf("unauthorized mutations=%d", runtime.mutations)
	}
	unauthorized := httptest.NewRecorder()
	secure.ServeHTTP(unauthorized, httptest.NewRequest(http.MethodPost, "/v1/xkeen/runtime", strings.NewReader(`{"enabled":false}`)))
	if unauthorized.Code != 401 {
		t.Fatalf("unauthorized=%d", unauthorized.Code)
	}
}

func TestRuntimeRejectsAmbiguousOrUnsafeRequests(t *testing.T) {
	for _, body := range []string{`{}`, `null`, `{"enabled":null}`, `{"enabled":"false"}`, `{"enabled":false,"command":"stop"}`, `{"enabled":false} {}`} {
		t.Run(body, func(t *testing.T) {
			server, _, base := newTestServer(t)
			runtime := &runtimeAPIEngine{fakeEngine: base, running: true}
			server.engine = runtime
			recorder := httptest.NewRecorder()
			server.ServeHTTP(recorder, authenticatedRequest(http.MethodPost, "/v1/xkeen/runtime", strings.NewReader(body)))
			if recorder.Code != 400 || runtime.mutations != 0 {
				t.Fatalf("status=%d calls=%d body=%s", recorder.Code, runtime.mutations, recorder.Body.String())
			}
		})
	}
}

func TestRuntimeMapsSafeErrorsAndNeverOptimisticallyReportsSuccess(t *testing.T) {
	for _, test := range []struct {
		err     error
		code    int
		message string
	}{
		{transaction.ErrBusy, 409, "busy"}, {xray.ErrRuntimeUnsupported, 503, "feature_unavailable"},
		{xray.ErrRuntimeStatus, 503, "runtime_status_failed"}, {xray.ErrRuntimeStart, 503, "runtime_start_failed"},
		{xray.ErrRuntimeStop, 503, "runtime_stop_failed"}, {xray.ErrRuntimeVerify, 503, "runtime_verification_failed"},
		{errors.New("private subscription secret"), 503, "runtime_verification_failed"},
	} {
		server, _, base := newTestServer(t)
		server.engine = &runtimeAPIEngine{fakeEngine: base, err: test.err}
		recorder := httptest.NewRecorder()
		server.ServeHTTP(recorder, authenticatedRequest(http.MethodPost, "/v1/xkeen/runtime", strings.NewReader(`{"enabled":false}`)))
		if recorder.Code != test.code || !strings.Contains(recorder.Body.String(), test.message) || strings.Contains(recorder.Body.String(), "private") {
			t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
		}
	}
	server, _, base := newTestServer(t)
	server.engine = &runtimeAPIEngine{fakeEngine: base, running: true, ignoreMutation: true}
	recorder := httptest.NewRecorder()
	server.ServeHTTP(recorder, authenticatedRequest(http.MethodPost, "/v1/xkeen/runtime", strings.NewReader(`{"enabled":false}`)))
	if recorder.Code != 503 {
		t.Fatalf("optimistic success: %d %s", recorder.Code, recorder.Body.String())
	}
	server.engine = nil
	recorder = httptest.NewRecorder()
	server.ServeHTTP(recorder, authenticatedRequest(http.MethodGet, "/v1/xkeen/runtime", nil))
	if recorder.Code != 503 || !strings.Contains(recorder.Body.String(), "feature_unavailable") {
		t.Fatalf("unsupported=%d %s", recorder.Code, recorder.Body.String())
	}
}
