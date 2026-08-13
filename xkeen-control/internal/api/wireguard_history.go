package api

import (
	"context"
	"errors"
	"net/http"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/historyproxy"
)

const maxHistoryQueryBody = 2 << 10

type WireGuardHistoryService interface {
	History(context.Context, historyproxy.Query) (historyproxy.History, error)
}

func WithWireGuardHistory(service WireGuardHistoryService) SecureOption {
	return func(server *SecureServer) { server.wireGuardHistory = service }
}

func (s *SecureServer) handleWireGuardHistory(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	var request struct {
		SchemaVersion int    `json:"schema_version"`
		PeerID        string `json:"peer_id"`
		From          int64  `json:"from"`
		To            int64  `json:"to"`
		Resolution    string `json:"resolution"`
		Limit         int    `json:"limit"`
	}
	if !decodeSecureJSONLimit(w, r, &request, maxHistoryQueryBody) {
		return
	}
	query := historyproxy.Query{
		PeerID: request.PeerID, From: request.From, To: request.To,
		Resolution: request.Resolution, Limit: request.Limit,
	}
	if request.SchemaVersion != 1 || historyproxy.ValidateQuery(query) != nil {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	history, err := s.wireGuardHistory.History(r.Context(), query)
	if err != nil {
		if errors.Is(err, context.Canceled) {
			return
		}
		writeError(w, http.StatusServiceUnavailable, "history_unavailable")
		return
	}
	if history.Points == nil {
		history.Points = []historyproxy.Point{}
	}
	if historyproxy.ValidateHistory(query, history) != nil {
		writeError(w, http.StatusServiceUnavailable, "history_unavailable")
		return
	}
	writeJSON(w, http.StatusOK, struct {
		SchemaVersion int                  `json:"schema_version"`
		History       historyproxy.History `json:"history"`
	}{SchemaVersion: 1, History: history})
}
