package api

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/coordinator"
)

func TestRecoveryPreviewAllowsViewerAndExactRollbackRequiresOperator(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	pairDevice(t, devices, auth.ScopeOwner, "Owner")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	operator := pairFromOwner(t, devices, auth.ScopeOperator, "Operator")
	manager := &fakeRecoveryManager{state: coordinator.RecoveryState{SchemaVersion: 1, Pending: true, PlanID: "scenario-apply-0001", Modules: []string{"routes"}}, result: coordinator.Result{Status: coordinator.StatusRolledBack, PlanID: "scenario-apply-0001"}}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithRecovery(manager))
	preview := httptest.NewRecorder()
	secure.ServeHTTP(preview, catalogRequest(http.MethodGet, "/v1/recovery", viewer.Token, ""))
	if preview.Code != http.StatusOK || !bytes.Contains(preview.Body.Bytes(), []byte(`"pending":true`)) || bytes.Contains(preview.Body.Bytes(), []byte("before")) {
		t.Fatalf("preview=%d %s", preview.Code, preview.Body.Bytes())
	}
	body := `{"schema_version":1,"action":"rollback","reviewed_plan_id":"scenario-apply-0001"}`
	forbidden := httptest.NewRecorder()
	secure.ServeHTTP(forbidden, catalogRequest(http.MethodPost, "/v1/recovery", viewer.Token, body))
	if forbidden.Code != http.StatusForbidden {
		t.Fatalf("forbidden=%d", forbidden.Code)
	}
	apply := httptest.NewRecorder()
	secure.ServeHTTP(apply, catalogRequest(http.MethodPost, "/v1/recovery", operator.Token, body))
	if apply.Code != http.StatusOK || manager.reviewed != "scenario-apply-0001" {
		t.Fatalf("apply=%d %s reviewed=%s", apply.Code, apply.Body.Bytes(), manager.reviewed)
	}
}

type fakeRecoveryManager struct {
	state    coordinator.RecoveryState
	stateErr error
	result   coordinator.Result
	reviewed string
}

func (f *fakeRecoveryManager) RecoveryStatus(context.Context) (coordinator.RecoveryState, error) {
	return f.state, f.stateErr
}
func (f *fakeRecoveryManager) RecoverReviewed(_ context.Context, id string) coordinator.Result {
	f.reviewed = id
	return f.result
}
