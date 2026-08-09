package api

import (
	"context"
	"net/http"

	"github.com/goldb/keenwg/xkeen-control/internal/coordinator"
)

type RecoveryManager interface {
	RecoveryStatus(context.Context) (coordinator.RecoveryState, error)
	RecoverReviewed(context.Context, string) coordinator.Result
}

func (s *SecureServer) handleRecovery(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		state, err := s.recovery.RecoveryStatus(r.Context())
		if err != nil {
			writeError(w, http.StatusServiceUnavailable, "recovery_unavailable")
			return
		}
		if state.Modules == nil {
			state.Modules = []string{}
		}
		writeJSON(w, http.StatusOK, state)
	case http.MethodPost:
		var request struct {
			SchemaVersion  int    `json:"schema_version"`
			Action         string `json:"action"`
			ReviewedPlanID string `json:"reviewed_plan_id"`
		}
		if !decodeSecureJSON(w, r, &request) {
			return
		}
		if request.SchemaVersion != 1 || request.Action != "rollback" {
			writeError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		result := s.recovery.RecoverReviewed(r.Context(), request.ReviewedPlanID)
		status := http.StatusOK
		switch result.Status {
		case coordinator.StatusRejected:
			status = http.StatusConflict
			if result.ErrorCode == "invalid_request" {
				status = http.StatusBadRequest
			}
		case coordinator.StatusUncertain:
			status = http.StatusServiceUnavailable
		case coordinator.StatusCommitted, coordinator.StatusRolledBack:
		default:
			status = http.StatusServiceUnavailable
		}
		writeJSON(w, status, result)
	default:
		methodNotAllowed(w, http.MethodGet+", "+http.MethodPost)
	}
}
