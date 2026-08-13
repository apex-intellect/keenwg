package api

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/routerlocal"
)

func TestSecureRouterLocalRoutesKeepTrustedPhonesSeparateAndEnforceScopes(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	owner := pairDevice(t, devices, auth.ScopeOwner, "Owner")
	operator := pairFromOwner(t, devices, auth.ScopeOperator, "Operator")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	local := &routerLocalStub{
		home:      routerlocal.HomeDocument{SchemaVersion: 1, StateVersion: "home-v1", Devices: []routerlocal.HomeDevice{{ID: "mac-1234", MAC: "02:00:00:00:00:01", Name: "Phone", IP: "192.168.1.10"}}},
		wireGuard: routerlocal.WireGuardDocument{SchemaVersion: 1, StateVersion: "wg-v1", Interfaces: []routerlocal.WireGuardInterface{{ID: "Wireguard0", Addresses: []string{"10.8.0.1/24"}, Peers: []routerlocal.WireGuardPeer{}}}},
	}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithRouterLocal(local))

	tests := []struct {
		name, method, path, token, body string
		want                            int
	}{
		{"home without token", http.MethodGet, "/v1/network/devices", "", "", http.StatusUnauthorized},
		{"home as viewer", http.MethodGet, "/v1/network/devices", viewer.Token, "", http.StatusOK},
		{"trusted phones still owner only", http.MethodGet, "/v1/devices", viewer.Token, "", http.StatusForbidden},
		{"reservation review as viewer", http.MethodPost, "/v1/network/devices/mac-1234/reservation/review", viewer.Token, `{"schema_version":1,"state_version":"home-v1","reserved_ip":"192.168.1.20"}`, http.StatusOK},
		{"reservation apply as viewer", http.MethodPost, "/v1/network/devices/mac-1234/reservation/apply", viewer.Token, `{"schema_version":1,"state_version":"home-v1","reserved_ip":"192.168.1.20","plan_id":"plan-1","idempotency_key":"11111111-1111-4111-8111-111111111111"}`, http.StatusForbidden},
		{"reservation apply as operator", http.MethodPost, "/v1/network/devices/mac-1234/reservation/apply", operator.Token, `{"schema_version":1,"state_version":"home-v1","reserved_ip":"192.168.1.20","plan_id":"plan-1","idempotency_key":"11111111-1111-4111-8111-111111111111"}`, http.StatusOK},
		{"wireguard as viewer", http.MethodGet, "/v1/access/wireguard", viewer.Token, "", http.StatusOK},
		{"peer review as viewer", http.MethodPost, "/v1/access/wireguard/peers/review", viewer.Token, `{"schema_version":1,"state_version":"wg-v1","interface_id":"Wireguard0","action":"revoke","public_key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="}`, http.StatusOK},
		{"peer apply as operator", http.MethodPost, "/v1/access/wireguard/peers/apply", operator.Token, `{"schema_version":1,"state_version":"wg-v1","interface_id":"Wireguard0","action":"revoke","public_key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=","plan_id":"peer-plan","idempotency_key":"22222222-2222-4222-8222-222222222222"}`, http.StatusOK},
		{"unknown field", http.MethodPost, "/v1/access/wireguard/peers/review", owner.Token, `{"schema_version":1,"state_version":"wg-v1","interface_id":"Wireguard0","action":"revoke","public_key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=","password":"leak"}`, http.StatusBadRequest},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(test.method, test.path, strings.NewReader(test.body))
			if test.token != "" {
				request.Header.Set("Authorization", "Bearer "+test.token)
			}
			if test.body != "" {
				request.Header.Set("Content-Type", "application/json")
			}
			recorder := httptest.NewRecorder()
			secure.ServeHTTP(recorder, request)
			if recorder.Code != test.want {
				t.Fatalf("status=%d want=%d body=%s", recorder.Code, test.want, recorder.Body.Bytes())
			}
		})
	}
	if local.homeReads == 0 || local.wireGuardReadCalls != 1 || local.reservationApplies != 1 || local.peerApplies != 1 {
		t.Fatalf("unexpected calls: %+v", local)
	}
}

func TestSecureRouterLocalMapsSanitizedErrors(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	pairDevice(t, devices, auth.ScopeOwner, "Owner")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	local := &routerLocalStub{homeErr: routerlocal.ErrCommandTimeout}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithRouterLocal(local))
	request := httptest.NewRequest(http.MethodGet, "/v1/network/devices", nil)
	request.Header.Set("Authorization", "Bearer "+viewer.Token)
	recorder := httptest.NewRecorder()
	secure.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusGatewayTimeout || recorder.Body.String() != "{\"error\":{\"code\":\"router_timeout\"}}\n" {
		t.Fatalf("status=%d body=%q", recorder.Code, recorder.Body.String())
	}
}

func TestSecureRouterLocalMapsRecoveryRequiredWithoutDetails(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	viewer := pairDevice(t, devices, auth.ScopeOwner, "Owner")
	local := &routerLocalStub{homeErr: routerlocal.ErrRecoveryRequired}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithRouterLocal(local))
	request := httptest.NewRequest(http.MethodGet, "/v1/network/devices", nil)
	request.Header.Set("Authorization", "Bearer "+viewer.Token)
	recorder := httptest.NewRecorder()

	secure.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusConflict || recorder.Body.String() != "{\"error\":{\"code\":\"recovery_required\"}}\n" {
		t.Fatalf("status=%d body=%q", recorder.Code, recorder.Body.String())
	}
}

type routerLocalStub struct {
	home               routerlocal.HomeDocument
	wireGuard          routerlocal.WireGuardDocument
	homeErr            error
	homeReads          int
	wireGuardReads     int
	wireGuardReadCalls int
	reservationApplies int
	peerApplies        int
}

func (s *routerLocalStub) SnapshotHome(context.Context) (routerlocal.HomeDocument, error) {
	s.homeReads++
	return s.home, s.homeErr
}
func (s *routerLocalStub) RecoverHome(ctx context.Context) (routerlocal.HomeDocument, error) {
	return s.SnapshotHome(ctx)
}
func (s *routerLocalStub) SnapshotWireGuard(context.Context) (routerlocal.WireGuardDocument, error) {
	s.wireGuardReads++
	return s.wireGuard, nil
}
func (s *routerLocalStub) RecoverWireGuard(ctx context.Context) (routerlocal.WireGuardDocument, error) {
	return s.SnapshotWireGuard(ctx)
}
func (s *routerLocalStub) ReadWireGuard(context.Context) (routerlocal.WireGuardDocument, error) {
	s.wireGuardReadCalls++
	return s.wireGuard, nil
}
func (s *routerLocalStub) ReviewReservation(_ context.Context, request routerlocal.ReservationReviewRequest) (routerlocal.ReservationPlan, error) {
	return routerlocal.ReservationPlan{SchemaVersion: 1, PlanID: "plan-1", ExpiresAt: time.Unix(1_900_000_000, 0), StateVersion: request.StateVersion, MAC: request.MAC, AfterIP: pointerText(request.ReservedIP)}, nil
}
func (s *routerLocalStub) ApplyReservation(context.Context, routerlocal.ReservationApplyRequest) (routerlocal.MutationResult, error) {
	s.reservationApplies++
	return routerlocal.MutationResult{SchemaVersion: 1, Status: routerlocal.MutationCommitted, Home: &s.home}, nil
}
func (s *routerLocalStub) ReviewPeer(_ context.Context, request routerlocal.PeerReviewRequest) (routerlocal.PeerPlan, error) {
	return routerlocal.PeerPlan{SchemaVersion: 1, PlanID: "peer-plan", Request: request}, nil
}
func (s *routerLocalStub) ApplyPeer(context.Context, routerlocal.PeerApplyRequest) (routerlocal.MutationResult, error) {
	s.peerApplies++
	return routerlocal.MutationResult{SchemaVersion: 1, Status: routerlocal.MutationCommitted, WireGuard: &s.wireGuard}, nil
}

func pointerText(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}
