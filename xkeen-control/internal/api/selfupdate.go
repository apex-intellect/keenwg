package api

import (
	"context"
	"errors"
	"io"
	"mime"
	"net/http"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/selfupdate"
)

const (
	selfUpdateMediaType        = "application/vnd.apex-intellect.keenwg-update.v1"
	maximumUpdateEnvelopeBytes = int64(4 + selfupdate.MaximumManifestBytes + selfupdate.MaximumArchiveBytes)
)

type SelfUpdater interface {
	Status(context.Context) (selfupdate.Status, error)
	Stage(context.Context, io.Reader) (selfupdate.AcceptedUpdate, error)
	Launch(selfupdate.AcceptedUpdate) error
}

func WithSelfUpdater(updater SelfUpdater) SecureOption {
	return func(server *SecureServer) { server.selfUpdater = updater }
}

func (s *SecureServer) handleSelfUpdate(w http.ResponseWriter, r *http.Request, principal Principal) {
	switch r.Method {
	case http.MethodGet:
		status, err := s.selfUpdater.Status(r.Context())
		if err != nil {
			writeError(w, http.StatusServiceUnavailable, "update_unavailable")
			return
		}
		if principal.Scope != auth.ScopeOwner {
			writeJSON(w, http.StatusOK, struct {
				SchemaVersion  int    `json:"schema_version"`
				CurrentVersion string `json:"current_version"`
				Supported      bool   `json:"supported"`
			}{1, status.CurrentVersion, status.Supported})
			return
		}
		writeJSON(w, http.StatusOK, status)
	case http.MethodPost:
		if principal.Scope != auth.ScopeOwner {
			writeError(w, http.StatusForbidden, "forbidden")
			return
		}
		mediaType, parameters, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
		if err != nil || mediaType != selfUpdateMediaType || len(parameters) != 0 {
			writeError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		if r.ContentLength > maximumUpdateEnvelopeBytes {
			writeError(w, http.StatusRequestEntityTooLarge, "request_too_large")
			return
		}
		if !s.updateLock.TryLock() {
			writeError(w, http.StatusConflict, "update_busy")
			return
		}
		defer s.updateLock.Unlock()
		r.Body = http.MaxBytesReader(w, r.Body, maximumUpdateEnvelopeBytes)
		accepted, err := s.selfUpdater.Stage(r.Context(), r.Body)
		if err != nil {
			var tooLarge *http.MaxBytesError
			switch {
			case errors.As(err, &tooLarge):
				writeError(w, http.StatusRequestEntityTooLarge, "request_too_large")
			case errors.Is(err, selfupdate.ErrUpdateBusy):
				writeError(w, http.StatusConflict, "update_busy")
			case errors.Is(err, selfupdate.ErrUpdateStorage):
				writeError(w, http.StatusServiceUnavailable, "update_unavailable")
			default:
				writeError(w, http.StatusBadRequest, "invalid_update")
			}
			return
		}
		writeJSON(w, http.StatusAccepted, struct {
			SchemaVersion int    `json:"schema_version"`
			TargetVersion string `json:"target_version"`
		}{1, accepted.TargetVersion})
		go func() { _ = s.selfUpdater.Launch(accepted) }()
	default:
		methodNotAllowed(w, http.MethodGet, http.MethodPost)
	}
}
