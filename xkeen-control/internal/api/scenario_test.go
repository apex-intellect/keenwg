package api

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/coordinator"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/scenario"
)

func TestScenarioReviewAllowsViewerButApplyRequiresOperator(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	pairDevice(t, devices, auth.ScopeOwner, "Owner")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	operator := pairFromOwner(t, devices, auth.ScopeOperator, "Operator")
	manager := &fakeScenarioManager{catalog: scenario.Catalog{SchemaVersion: 1, StateVersion: 7, Modules: scenario.Modules{Domains: true, IP: true}, Presets: scenario.DefaultPresets()}, review: scenario.Review{SchemaVersion: 1, PlanID: "0123456789abcdef0123456789abcdef", Plan: scenario.Plan{SchemaVersion: 1, PresetID: "media", StateVersion: 7, Steps: []scenario.Step{}, Skipped: []string{}}}, result: coordinator.Result{Status: coordinator.StatusCommitted, PlanID: "scenario-apply-0001"}}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithScenarios(manager))
	list := httptest.NewRecorder()
	secure.ServeHTTP(list, catalogRequest(http.MethodGet, "/v1/scenarios", viewer.Token, ""))
	if list.Code != http.StatusOK || !bytes.Contains(list.Body.Bytes(), []byte(`"russia-direct"`)) {
		t.Fatalf("list=%d %s", list.Code, list.Body.Bytes())
	}
	review := httptest.NewRecorder()
	secure.ServeHTTP(review, catalogRequest(http.MethodPost, "/v1/scenarios/media/review", viewer.Token, `{"schema_version":1,"state_version":7}`))
	if review.Code != http.StatusOK || !bytes.Contains(review.Body.Bytes(), []byte(`"plan_id":"0123456789abcdef0123456789abcdef"`)) {
		t.Fatalf("review=%d %s", review.Code, review.Body.Bytes())
	}
	body := `{"schema_version":1,"reviewed_state_version":7,"reviewed_plan_id":"0123456789abcdef0123456789abcdef","idempotency_key":"scenario-apply-0001"}`
	forbidden := httptest.NewRecorder()
	secure.ServeHTTP(forbidden, catalogRequest(http.MethodPost, "/v1/scenarios/media/apply", viewer.Token, body))
	if forbidden.Code != http.StatusForbidden {
		t.Fatalf("forbidden=%d", forbidden.Code)
	}
	apply := httptest.NewRecorder()
	secure.ServeHTTP(apply, catalogRequest(http.MethodPost, "/v1/scenarios/media/apply", operator.Token, body))
	if apply.Code != http.StatusOK || manager.apply.PresetID != "media" {
		t.Fatalf("apply=%d %s request=%+v", apply.Code, apply.Body.Bytes(), manager.apply)
	}
}

func TestScenarioAPIRejectsUnknownJSONAndMapsUncertain(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	pairDevice(t, devices, auth.ScopeOwner, "Owner")
	operator := pairFromOwner(t, devices, auth.ScopeOperator, "Operator")
	manager := &fakeScenarioManager{result: coordinator.Result{Status: coordinator.StatusUncertain, ErrorCode: "rollback_failed"}}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithScenarios(manager))
	bad := httptest.NewRecorder()
	secure.ServeHTTP(bad, catalogRequest(http.MethodPost, "/v1/scenarios/media/apply", operator.Token, `{"schema_version":1,"extra":true}`))
	if bad.Code != http.StatusBadRequest || manager.applyCalls != 0 {
		t.Fatalf("bad=%d calls=%d", bad.Code, manager.applyCalls)
	}
	body := `{"schema_version":1,"reviewed_state_version":7,"reviewed_plan_id":"0123456789abcdef0123456789abcdef","idempotency_key":"scenario-apply-0002"}`
	uncertain := httptest.NewRecorder()
	secure.ServeHTTP(uncertain, catalogRequest(http.MethodPost, "/v1/scenarios/media/apply", operator.Token, body))
	if uncertain.Code != http.StatusServiceUnavailable || !bytes.Contains(uncertain.Body.Bytes(), []byte(`"status":"uncertain"`)) {
		t.Fatalf("uncertain=%d %s", uncertain.Code, uncertain.Body.Bytes())
	}
}

type fakeScenarioManager struct {
	catalog    scenario.Catalog
	review     scenario.Review
	reviewErr  error
	result     coordinator.Result
	apply      scenario.ApplyRequest
	applyCalls int
}

func (f *fakeScenarioManager) Catalog(context.Context) (scenario.Catalog, error) {
	return f.catalog, nil
}

func (f *fakeScenarioManager) Review(context.Context, string, uint64) (scenario.Review, error) {
	return f.review, f.reviewErr
}
func (f *fakeScenarioManager) Apply(_ context.Context, request scenario.ApplyRequest) coordinator.Result {
	f.applyCalls++
	f.apply = request
	return f.result
}
