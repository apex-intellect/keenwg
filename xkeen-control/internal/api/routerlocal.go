package api

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/routerlocal"
)

type RouterLocalService interface {
	SnapshotHome(context.Context) (routerlocal.HomeDocument, error)
	RecoverHome(context.Context) (routerlocal.HomeDocument, error)
	SnapshotWireGuard(context.Context) (routerlocal.WireGuardDocument, error)
	RecoverWireGuard(context.Context) (routerlocal.WireGuardDocument, error)
	ReviewReservation(context.Context, routerlocal.ReservationReviewRequest) (routerlocal.ReservationPlan, error)
	ApplyReservation(context.Context, routerlocal.ReservationApplyRequest) (routerlocal.MutationResult, error)
	ReviewPeer(context.Context, routerlocal.PeerReviewRequest) (routerlocal.PeerPlan, error)
	ApplyPeer(context.Context, routerlocal.PeerApplyRequest) (routerlocal.MutationResult, error)
}

func WithRouterLocal(service RouterLocalService) SecureOption {
	return func(server *SecureServer) { server.routerLocal = service }
}

func (s *SecureServer) handleHomeDevices(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w, http.MethodGet)
		return
	}
	document, err := s.routerLocal.RecoverHome(r.Context())
	if err != nil {
		writeRouterLocalError(w, err)
		return
	}
	if document.Devices == nil {
		document.Devices = []routerlocal.HomeDevice{}
	}
	writeJSON(w, http.StatusOK, document)
}

func (s *SecureServer) handleReservation(w http.ResponseWriter, r *http.Request, deviceID, action string) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	var request struct {
		SchemaVersion  int     `json:"schema_version"`
		StateVersion   string  `json:"state_version"`
		ReservedIP     *string `json:"reserved_ip"`
		PlanID         string  `json:"plan_id,omitempty"`
		IdempotencyKey string  `json:"idempotency_key,omitempty"`
	}
	if !decodeSecureJSON(w, r, &request) {
		return
	}
	if request.SchemaVersion != 1 {
		writeError(w, http.StatusBadRequest, "unsupported_schema")
		return
	}
	document, err := s.routerLocal.SnapshotHome(r.Context())
	if err != nil {
		writeRouterLocalError(w, err)
		return
	}
	mac := ""
	for _, device := range document.Devices {
		if device.ID == deviceID {
			mac = device.MAC
			break
		}
	}
	if mac == "" {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	if action == "review" {
		if request.PlanID != "" || request.IdempotencyKey != "" {
			writeError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		plan, err := s.routerLocal.ReviewReservation(r.Context(), routerlocal.ReservationReviewRequest{StateVersion: request.StateVersion, MAC: mac, ReservedIP: request.ReservedIP})
		if err != nil {
			writeRouterLocalError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, plan)
		return
	}
	result, err := s.routerLocal.ApplyReservation(r.Context(), routerlocal.ReservationApplyRequest{PlanID: request.PlanID, StateVersion: request.StateVersion, MAC: mac, ReservedIP: request.ReservedIP, IdempotencyKey: request.IdempotencyKey})
	if err != nil {
		writeRouterLocalError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (s *SecureServer) handleWireGuard(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w, http.MethodGet)
		return
	}
	document, err := s.routerLocal.RecoverWireGuard(r.Context())
	if err != nil {
		writeRouterLocalError(w, err)
		return
	}
	if document.Interfaces == nil {
		document.Interfaces = []routerlocal.WireGuardInterface{}
	}
	for index := range document.Interfaces {
		if document.Interfaces[index].Addresses == nil {
			document.Interfaces[index].Addresses = []string{}
		}
		if document.Interfaces[index].Peers == nil {
			document.Interfaces[index].Peers = []routerlocal.WireGuardPeer{}
		}
	}
	writeJSON(w, http.StatusOK, document)
}

func (s *SecureServer) handleWireGuardPeer(w http.ResponseWriter, r *http.Request, action string) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	var request struct {
		SchemaVersion  int    `json:"schema_version"`
		StateVersion   string `json:"state_version"`
		InterfaceID    string `json:"interface_id"`
		Action         string `json:"action"`
		PublicKey      string `json:"public_key,omitempty"`
		NewPublicKey   string `json:"new_public_key,omitempty"`
		Name           string `json:"name,omitempty"`
		AllowedIP      string `json:"allowed_ip,omitempty"`
		Keepalive      int    `json:"keepalive,omitempty"`
		Enabled        *bool  `json:"enabled,omitempty"`
		PlanID         string `json:"plan_id,omitempty"`
		IdempotencyKey string `json:"idempotency_key,omitempty"`
	}
	if !decodeSecureJSON(w, r, &request) {
		return
	}
	if request.SchemaVersion != 1 {
		writeError(w, http.StatusBadRequest, "unsupported_schema")
		return
	}
	review := routerlocal.PeerReviewRequest{StateVersion: request.StateVersion, InterfaceID: request.InterfaceID, Action: request.Action, PublicKey: request.PublicKey, NewPublicKey: request.NewPublicKey, Name: request.Name, AllowedIP: request.AllowedIP, Keepalive: request.Keepalive, Enabled: request.Enabled}
	if action == "review" {
		if request.PlanID != "" || request.IdempotencyKey != "" {
			writeError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		plan, err := s.routerLocal.ReviewPeer(r.Context(), review)
		if err != nil {
			writeRouterLocalError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, plan)
		return
	}
	result, err := s.routerLocal.ApplyPeer(r.Context(), routerlocal.PeerApplyRequest{PeerReviewRequest: review, PlanID: request.PlanID, IdempotencyKey: request.IdempotencyKey})
	if err != nil {
		writeRouterLocalError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func reservationRoute(path string) (string, string, bool) {
	const prefix = "/v1/network/devices/"
	if !strings.HasPrefix(path, prefix) {
		return "", "", false
	}
	remainder := strings.TrimPrefix(path, prefix)
	for _, action := range []string{"review", "apply"} {
		suffix := "/reservation/" + action
		if strings.HasSuffix(remainder, suffix) {
			id := strings.TrimSuffix(remainder, suffix)
			if id != "" && !strings.Contains(id, "/") {
				return id, action, true
			}
		}
	}
	return "", "", false
}

func isReservationRoute(path string) bool {
	_, _, ok := reservationRoute(path)
	return ok
}

func writeRouterLocalError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, context.DeadlineExceeded), errors.Is(err, routerlocal.ErrCommandTimeout):
		writeError(w, http.StatusGatewayTimeout, "router_timeout")
	case errors.Is(err, routerlocal.ErrInvalidRequest):
		writeError(w, http.StatusBadRequest, "invalid_request")
	case errors.Is(err, routerlocal.ErrStaleState):
		writeError(w, http.StatusConflict, "stale_state")
	case errors.Is(err, routerlocal.ErrPlanExpired):
		writeError(w, http.StatusConflict, "plan_expired")
	case errors.Is(err, routerlocal.ErrPlanMismatch):
		writeError(w, http.StatusConflict, "plan_mismatch")
	case errors.Is(err, routerlocal.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found")
	case errors.Is(err, routerlocal.ErrConflict):
		writeError(w, http.StatusConflict, "conflict")
	case errors.Is(err, routerlocal.ErrRecoveryRequired):
		writeError(w, http.StatusConflict, "recovery_required")
	case errors.Is(err, routerlocal.ErrUnsupportedSchema), errors.Is(err, routerlocal.ErrOutputTooLarge), errors.Is(err, routerlocal.ErrTooManyItems), errors.Is(err, routerlocal.ErrDuplicateIdentity):
		writeError(w, http.StatusBadGateway, "router_response_unsupported")
	default:
		writeError(w, http.StatusServiceUnavailable, "router_unavailable")
	}
}
