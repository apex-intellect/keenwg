package api

import (
	"context"
	"net/http"

	"github.com/goldb/keenwg/xkeen-control/internal/support"
)

type SupportReporter interface {
	SupportReport(context.Context) (support.Bundle, error)
}

func (s *SecureServer) handleSupport(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w, http.MethodGet)
		return
	}
	bundle, err := s.support.SupportReport(r.Context())
	if err != nil || !support.ValidBundle(bundle) {
		writeError(w, http.StatusServiceUnavailable, "support_unavailable")
		return
	}
	writeJSON(w, http.StatusOK, bundle)
}
