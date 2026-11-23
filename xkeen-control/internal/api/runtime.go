package api

import (
	"context"
	"errors"
	"net/http"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/transaction"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/xray"
)

type runtimeController interface {
	RuntimeStatus(context.Context) (bool, error)
	SetRuntime(context.Context, bool) (bool, error)
}

func (s *Server) handleRuntime(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodGet, http.MethodPost)
		return
	}
	controller, ok := s.engine.(runtimeController)
	if !ok {
		writeError(w, http.StatusServiceUnavailable, "feature_unavailable")
		return
	}
	var running bool
	var err error
	if r.Method == http.MethodGet {
		running, err = controller.RuntimeStatus(r.Context())
	} else {
		var request struct {
			Enabled *bool `json:"enabled"`
		}
		if !decodeSecureJSONLimit(w, r, &request, maxMutationBody) {
			return
		}
		if request.Enabled == nil {
			writeError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		running, err = controller.SetRuntime(r.Context(), *request.Enabled)
		if err == nil && running != *request.Enabled {
			err = xray.ErrRuntimeVerify
		}
	}
	if err != nil {
		code, status := "runtime_verification_failed", http.StatusServiceUnavailable
		switch {
		case errors.Is(err, transaction.ErrBusy):
			code, status = "busy", http.StatusConflict
		case errors.Is(err, xray.ErrRuntimeUnsupported):
			code = "feature_unavailable"
		case errors.Is(err, xray.ErrRuntimeStatus):
			code = "runtime_status_failed"
		case errors.Is(err, xray.ErrRuntimeStart):
			code = "runtime_start_failed"
		case errors.Is(err, xray.ErrRuntimeStop):
			code = "runtime_stop_failed"
		}
		writeError(w, status, code)
		return
	}
	writeJSON(w, http.StatusOK, struct {
		SchemaVersion int  `json:"schema_version"`
		Running       bool `json:"running"`
		RuntimeOnly   bool `json:"runtime_only"`
	}{1, running, true})
}
