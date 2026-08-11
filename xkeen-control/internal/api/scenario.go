package api

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/coordinator"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/scenario"
)

type ScenarioManager interface {
	Catalog(context.Context) (scenario.Catalog, error)
	Review(context.Context, string, uint64) (scenario.Review, error)
	Apply(context.Context, scenario.ApplyRequest) coordinator.Result
}

func (s *SecureServer) handleScenarioCatalog(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w, http.MethodGet)
		return
	}
	catalog, err := s.scenarios.Catalog(r.Context())
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "scenario_unavailable")
		return
	}
	if catalog.Presets == nil {
		catalog.Presets = []scenario.Preset{}
	}
	writeJSON(w, http.StatusOK, catalog)
}

func isScenarioPath(path, suffix string) bool {
	_, ok := onePathSegment(path, "/v1/scenarios/", suffix)
	return ok
}

func (s *SecureServer) handleScenarioReview(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	presetID, ok := onePathSegment(r.URL.Path, "/v1/scenarios/", "/review")
	if !ok {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	var request struct {
		SchemaVersion int    `json:"schema_version"`
		StateVersion  uint64 `json:"state_version"`
	}
	if !decodeSecureJSON(w, r, &request) {
		return
	}
	if request.SchemaVersion != 1 || request.StateVersion == 0 {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	review, err := s.scenarios.Review(r.Context(), presetID, request.StateVersion)
	if err != nil {
		switch {
		case errors.Is(err, scenario.ErrStaleState):
			writeError(w, http.StatusConflict, "stale_state")
		case errors.Is(err, scenario.ErrPresetNotFound):
			writeError(w, http.StatusNotFound, "not_found")
		case errors.Is(err, scenario.ErrInvalidPreset):
			writeError(w, http.StatusBadRequest, "invalid_request")
		default:
			writeError(w, http.StatusServiceUnavailable, "scenario_unavailable")
		}
		return
	}
	if review.Plan.Steps == nil {
		review.Plan.Steps = []scenario.Step{}
	}
	if review.Plan.Skipped == nil {
		review.Plan.Skipped = []string{}
	}
	writeJSON(w, http.StatusOK, review)
}

func (s *SecureServer) handleScenarioApply(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	presetID, ok := onePathSegment(r.URL.Path, "/v1/scenarios/", "/apply")
	if !ok {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	var request struct {
		SchemaVersion        int    `json:"schema_version"`
		ReviewedStateVersion uint64 `json:"reviewed_state_version"`
		ReviewedPlanID       string `json:"reviewed_plan_id"`
		IdempotencyKey       string `json:"idempotency_key"`
	}
	if !decodeSecureJSON(w, r, &request) {
		return
	}
	if request.SchemaVersion != 1 {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	result := s.scenarios.Apply(r.Context(), scenario.ApplyRequest{PresetID: presetID, ReviewedStateVersion: request.ReviewedStateVersion, ReviewedPlanID: request.ReviewedPlanID, IdempotencyKey: request.IdempotencyKey})
	status := http.StatusOK
	switch result.Status {
	case coordinator.StatusRejected:
		status = http.StatusConflict
		if result.ErrorCode == "invalid_request" {
			status = http.StatusBadRequest
		}
	case coordinator.StatusRolledBack:
		status = http.StatusConflict
	case coordinator.StatusUncertain:
		status = http.StatusServiceUnavailable
	case coordinator.StatusCommitted:
	default:
		if !strings.EqualFold(result.Status, coordinator.StatusCommitted) {
			status = http.StatusServiceUnavailable
		}
	}
	writeJSON(w, status, result)
}
