package api

import (
	"bytes"
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/goldb/keenwg/xkeen-control/internal/auth"
	"github.com/goldb/keenwg/xkeen-control/internal/routegraph"
)

func TestRouteExplainIsViewerReadOnlyAndStrict(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	pairDevice(t, devices, auth.ScopeOwner, "Owner")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	explainer := &fakeRouteExplainer{result: routegraph.Explanation{
		SchemaVersion: 1,
		Decision:      routegraph.Decision{Outcome: "direct", RuleID: "domain-direct", Confidence: "inferred"},
		Steps:         []routegraph.Step{}, ShadowedRuleIDs: []string{}, Warnings: []string{}, Adapters: []routegraph.AdapterObservation{},
	}}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithRouteExplainer(explainer))

	recorder := httptest.NewRecorder()
	secure.ServeHTTP(recorder, catalogRequest(http.MethodPost, "/v1/routes/explain", viewer.Token,
		`{"schema_version":1,"domain":"example.com","protocol":"tcp","port":443}`))
	if recorder.Code != http.StatusOK || !bytes.Contains(recorder.Body.Bytes(), []byte(`"outcome":"direct"`)) {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.Bytes())
	}
	if explainer.calls != 1 || explainer.request.Domain != "example.com" {
		t.Fatalf("calls=%d request=%+v", explainer.calls, explainer.request)
	}

	bad := httptest.NewRecorder()
	secure.ServeHTTP(bad, catalogRequest(http.MethodPost, "/v1/routes/explain", viewer.Token,
		`{"schema_version":1,"domain":"example.com","activate":true}`))
	if bad.Code != http.StatusBadRequest || explainer.calls != 1 {
		t.Fatalf("bad status=%d calls=%d", bad.Code, explainer.calls)
	}
}

func TestRouteExplainMapsTypedErrors(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	pairDevice(t, devices, auth.ScopeOwner, "Owner")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	explainer := &fakeRouteExplainer{err: routegraph.ErrInvalidRequest}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithRouteExplainer(explainer))

	invalid := httptest.NewRecorder()
	secure.ServeHTTP(invalid, catalogRequest(http.MethodPost, "/v1/routes/explain", viewer.Token, `{"schema_version":1,"domain":"bad"}`))
	if invalid.Code != http.StatusBadRequest {
		t.Fatalf("invalid=%d body=%s", invalid.Code, invalid.Body.Bytes())
	}

	explainer.err = routegraph.ErrEvidenceUnavailable
	unavailable := httptest.NewRecorder()
	secure.ServeHTTP(unavailable, catalogRequest(http.MethodPost, "/v1/routes/explain", viewer.Token, `{"schema_version":1,"domain":"example.com"}`))
	if unavailable.Code != http.StatusServiceUnavailable {
		t.Fatalf("unavailable=%d body=%s", unavailable.Code, unavailable.Body.Bytes())
	}

	explainer.err = errors.New("private backend detail")
	unexpected := httptest.NewRecorder()
	secure.ServeHTTP(unexpected, catalogRequest(http.MethodPost, "/v1/routes/explain", viewer.Token, `{"schema_version":1,"ip":"192.0.2.1"}`))
	if unexpected.Code != http.StatusServiceUnavailable || bytes.Contains(unexpected.Body.Bytes(), []byte("private")) {
		t.Fatalf("unexpected=%d body=%s", unexpected.Code, unexpected.Body.Bytes())
	}
}

type fakeRouteExplainer struct {
	result  routegraph.Explanation
	err     error
	request routegraph.Request
	calls   int
}

func (f *fakeRouteExplainer) Explain(_ context.Context, request routegraph.Request) (routegraph.Explanation, error) {
	f.calls++
	f.request = request
	return f.result, f.err
}
