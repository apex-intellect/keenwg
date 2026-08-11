package api

import (
	"context"
	"errors"
	"net/http"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/routegraph"
)

type RouteExplainer interface {
	Explain(context.Context, routegraph.Request) (routegraph.Explanation, error)
}

func (s *SecureServer) handleRouteExplain(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	var request routegraph.Request
	if !decodeSecureJSON(w, r, &request) {
		return
	}
	explanation, err := s.routes.Explain(r.Context(), request)
	if err != nil {
		if errors.Is(err, routegraph.ErrInvalidRequest) {
			writeError(w, http.StatusBadRequest, "invalid_route_request")
		} else {
			writeError(w, http.StatusServiceUnavailable, "route_evidence_unavailable")
		}
		return
	}
	if explanation.Steps == nil {
		explanation.Steps = []routegraph.Step{}
	}
	if explanation.ShadowedRuleIDs == nil {
		explanation.ShadowedRuleIDs = []string{}
	}
	if explanation.Warnings == nil {
		explanation.Warnings = []string{}
	}
	if explanation.Adapters == nil {
		explanation.Adapters = []routegraph.AdapterObservation{}
	}
	writeJSON(w, http.StatusOK, explanation)
}
