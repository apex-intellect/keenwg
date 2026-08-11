package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/capability"
)

func TestSecureRoutesEnforceViewerOperatorAndOwnerScopes(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	owner := pairDevice(t, devices, auth.ScopeOwner, "Owner")
	operator := pairFromOwner(t, devices, auth.ScopeOperator, "Operator")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	secure := NewSecure(legacy, devices, staticCapabilities())

	tests := []struct {
		name, method, path, token, body string
		want                            int
	}{
		{"capabilities without token", http.MethodGet, "/v1/capabilities", "", "", http.StatusUnauthorized},
		{"capabilities as viewer", http.MethodGet, "/v1/capabilities", viewer.Token, "", http.StatusOK},
		{"devices as viewer", http.MethodGet, "/v1/devices", viewer.Token, "", http.StatusForbidden},
		{"devices as owner", http.MethodGet, "/v1/devices", owner.Token, "", http.StatusOK},
		{"legacy status as viewer", http.MethodGet, "/v1/xkeen/status", viewer.Token, "", http.StatusOK},
		{"legacy mutation as viewer", http.MethodPost, "/v1/xkeen/subscription/refresh", viewer.Token, `{"state_version":7,"idempotency_key":"11111111-1111-4111-8111-111111111111"}`, http.StatusForbidden},
		{"legacy mutation as operator", http.MethodPost, "/v1/xkeen/subscription/refresh", operator.Token, `{"state_version":7,"idempotency_key":"22222222-2222-4222-8222-222222222222"}`, http.StatusAccepted},
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
}

func TestSecureRejectsRawControllerTokenAndUnknownPaths(t *testing.T) {
	core, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	_ = pairDevice(t, devices, auth.ScopeOwner, "Owner")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	secure := NewSecure(core, devices, staticCapabilities())

	raw := httptest.NewRequest(http.MethodGet, "/v1/xkeen/status", nil)
	raw.Header.Set("Authorization", "Bearer "+controlToken)
	rawRecorder := httptest.NewRecorder()
	secure.ServeHTTP(rawRecorder, raw)
	if rawRecorder.Code != http.StatusUnauthorized {
		t.Fatalf("raw controller token status=%d body=%s", rawRecorder.Code, rawRecorder.Body.Bytes())
	}

	unknown := httptest.NewRequest(http.MethodGet, "/v1/not-a-route", nil)
	unknown.Header.Set("Authorization", "Bearer "+viewer.Token)
	unknownRecorder := httptest.NewRecorder()
	secure.ServeHTTP(unknownRecorder, unknown)
	if unknownRecorder.Code != http.StatusNotFound {
		t.Fatalf("unknown path status=%d body=%s", unknownRecorder.Code, unknownRecorder.Body.Bytes())
	}
}

func TestSecurePairingExchangeReturnsTokenOnceAndRejectsReplay(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	offer, err := devices.CreateBootstrapOffer(context.Background(), auth.ScopeOwner, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	secure := NewSecure(legacy, devices, staticCapabilities())
	body := `{"schema_version":1,"offer_id":"` + offer.ID + `","secret":"` + offer.Secret + `","device_label":"Pixel 9"}`

	request := httptest.NewRequest(http.MethodPost, "/v1/pairing/exchange", strings.NewReader(body))
	request.RemoteAddr = "192.0.2.10:4321"
	request.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()
	secure.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.Bytes())
	}
	var response struct {
		SchemaVersion int        `json:"schema_version"`
		DeviceID      string     `json:"device_id"`
		Scope         auth.Scope `json:"scope"`
		Token         string     `json:"token"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.SchemaVersion != 1 || response.DeviceID == "" || response.Scope != auth.ScopeOwner || response.Token == "" {
		t.Fatalf("invalid exchange response: %+v", response)
	}

	replay := httptest.NewRequest(http.MethodPost, "/v1/pairing/exchange", strings.NewReader(body))
	replay.RemoteAddr = "192.0.2.10:4322"
	replay.Header.Set("Content-Type", "application/json")
	replayRecorder := httptest.NewRecorder()
	secure.ServeHTTP(replayRecorder, replay)
	if replayRecorder.Code != http.StatusConflict || bytes.Contains(replayRecorder.Body.Bytes(), []byte(offer.Secret)) {
		t.Fatalf("replay status=%d body=%s", replayRecorder.Code, replayRecorder.Body.Bytes())
	}
}

func TestSecureOwnerCreatesOfferAndRevokesDevice(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	owner := pairDevice(t, devices, auth.ScopeOwner, "Owner")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	secure := NewSecure(legacy, devices, staticCapabilities())

	create := httptest.NewRequest(http.MethodPost, "/v1/pairing/offers", strings.NewReader(`{"schema_version":1,"scope":"viewer"}`))
	create.Header.Set("Authorization", "Bearer "+owner.Token)
	create.Header.Set("Content-Type", "application/json")
	createRecorder := httptest.NewRecorder()
	secure.ServeHTTP(createRecorder, create)
	if createRecorder.Code != http.StatusCreated || !bytes.Contains(createRecorder.Body.Bytes(), []byte(`"expires_at"`)) {
		t.Fatalf("create status=%d body=%s", createRecorder.Code, createRecorder.Body.Bytes())
	}

	revoke := httptest.NewRequest(http.MethodDelete, "/v1/devices/"+viewer.Device.ID, nil)
	revoke.Header.Set("Authorization", "Bearer "+owner.Token)
	revokeRecorder := httptest.NewRecorder()
	secure.ServeHTTP(revokeRecorder, revoke)
	if revokeRecorder.Code != http.StatusNoContent {
		t.Fatalf("revoke status=%d body=%s", revokeRecorder.Code, revokeRecorder.Body.Bytes())
	}
	status := httptest.NewRequest(http.MethodGet, "/v1/xkeen/status", nil)
	status.Header.Set("Authorization", "Bearer "+viewer.Token)
	statusRecorder := httptest.NewRecorder()
	secure.ServeHTTP(statusRecorder, status)
	if statusRecorder.Code != http.StatusUnauthorized {
		t.Fatalf("revoked viewer status=%d", statusRecorder.Code)
	}
}

func TestSecureOwnerRevokesUnusedPairingOffer(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	owner := pairDevice(t, devices, auth.ScopeOwner, "Owner")
	secure := NewSecure(legacy, devices, staticCapabilities())
	offer, err := devices.CreateOffer(context.Background(), auth.ScopeOwner, auth.ScopeViewer, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}

	revoke := httptest.NewRequest(http.MethodDelete, "/v1/pairing/offers/"+offer.ID, nil)
	revoke.Header.Set("Authorization", "Bearer "+owner.Token)
	recorder := httptest.NewRecorder()
	secure.ServeHTTP(recorder, revoke)
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("revoke status=%d body=%s", recorder.Code, recorder.Body.Bytes())
	}
	if _, err := devices.Exchange(context.Background(), offer.ID, offer.Secret, "Viewer"); !errors.Is(err, auth.ErrOfferNotFound) {
		t.Fatalf("exchange error=%v, want ErrOfferNotFound", err)
	}
}

func TestSecurePairingExchangeRateLimitsPerRemoteIP(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	secure := NewSecure(legacy, devices, staticCapabilities())
	for attempt := 1; attempt <= 6; attempt++ {
		request := httptest.NewRequest(http.MethodPost, "/v1/pairing/exchange", strings.NewReader(`{"schema_version":1,"offer_id":"missing","secret":"wrong","device_label":"Phone"}`))
		request.RemoteAddr = "198.51.100.25:1234"
		request.Header.Set("Content-Type", "application/json")
		recorder := httptest.NewRecorder()
		secure.ServeHTTP(recorder, request)
		if attempt <= 5 && recorder.Code == http.StatusTooManyRequests {
			t.Fatalf("attempt %d was limited early", attempt)
		}
		if attempt == 6 && recorder.Code != http.StatusTooManyRequests {
			t.Fatalf("attempt 6 status=%d body=%s", recorder.Code, recorder.Body.Bytes())
		}
	}
}

func TestSecureOfferRejectsMissingSchemaVersion(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	owner := pairDevice(t, devices, auth.ScopeOwner, "Owner")
	secure := NewSecure(legacy, devices, staticCapabilities())
	request := httptest.NewRequest(http.MethodPost, "/v1/pairing/offers", strings.NewReader(`{"scope":"viewer"}`))
	request.Header.Set("Authorization", "Bearer "+owner.Token)
	request.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()
	secure.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.Bytes())
	}
}

func TestSecureReadResponsesContainNoCredentialFields(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	owner := pairDevice(t, devices, auth.ScopeOwner, "Owner")
	secure := NewSecure(legacy, devices, staticCapabilities())
	for _, path := range []string{"/v1/capabilities", "/v1/devices", "/v1/health"} {
		request := httptest.NewRequest(http.MethodGet, path, nil)
		if path != "/v1/health" {
			request.Header.Set("Authorization", "Bearer "+owner.Token)
		}
		recorder := httptest.NewRecorder()
		secure.ServeHTTP(recorder, request)
		if recorder.Code != http.StatusOK {
			t.Fatalf("%s status=%d body=%s", path, recorder.Code, recorder.Body.Bytes())
		}
		assertNoSensitiveJSONKeys(t, recorder.Body.Bytes())
	}
}

func TestSecureCapabilitiesHideDeviceManagementFromNonOwners(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	owner := pairDevice(t, devices, auth.ScopeOwner, "Owner")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	document := staticCapabilities().document
	document.Capabilities = append(document.Capabilities, capability.Capability{
		ID: capability.SystemDevices, SchemaVersion: 1, Access: capability.AccessWrite, Available: true, Transport: "companion",
	})
	secure := NewSecure(legacy, devices, fixedCapabilities{document: document})

	for _, test := range []struct {
		name, token, wantAccess string
		wantAvailable           bool
	}{
		{"owner", owner.Token, string(capability.AccessWrite), true},
		{"viewer", viewer.Token, string(capability.AccessNone), false},
	} {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodGet, "/v1/capabilities", nil)
			request.Header.Set("Authorization", "Bearer "+test.token)
			recorder := httptest.NewRecorder()
			secure.ServeHTTP(recorder, request)
			if recorder.Code != http.StatusOK {
				t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.Bytes())
			}
			var got capability.Document
			if err := json.Unmarshal(recorder.Body.Bytes(), &got); err != nil {
				t.Fatal(err)
			}
			for _, entry := range got.Capabilities {
				if entry.ID == capability.SystemDevices && (string(entry.Access) != test.wantAccess || entry.Available != test.wantAvailable) {
					t.Fatalf("system.devices=%+v", entry)
				}
			}
		})
	}
}

func newSecureDeviceStore(t *testing.T) *auth.FileStore {
	t.Helper()
	dir := t.TempDir()
	store, err := auth.NewFileStore(filepath.Join(dir, "devices.json"), filepath.Join(dir, "offers.json"))
	if err != nil {
		t.Fatal(err)
	}
	return store
}

func pairDevice(t *testing.T, store *auth.FileStore, scope auth.Scope, label string) auth.PlainCredential {
	t.Helper()
	offer, err := store.CreateBootstrapOffer(context.Background(), scope, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	credential, err := store.Exchange(context.Background(), offer.ID, offer.Secret, label)
	if err != nil {
		t.Fatal(err)
	}
	return credential
}

func pairFromOwner(t *testing.T, store *auth.FileStore, scope auth.Scope, label string) auth.PlainCredential {
	t.Helper()
	offer, err := store.CreateOffer(context.Background(), auth.ScopeOwner, scope, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	credential, err := store.Exchange(context.Background(), offer.ID, offer.Secret, label)
	if err != nil {
		t.Fatal(err)
	}
	return credential
}

type fixedCapabilities struct{ document capability.Document }

func staticCapabilities() fixedCapabilities {
	return fixedCapabilities{document: capability.Document{
		SchemaVersion: 1,
		StateVersion:  77,
		Capabilities:  []capability.Capability{{ID: capability.OverviewHealth, SchemaVersion: 1, Access: capability.AccessRead, Available: true, Transport: "companion"}},
	}}
}

func (f fixedCapabilities) Detect(context.Context) (capability.Document, error) {
	return f.document, nil
}

func assertNoSensitiveJSONKeys(t *testing.T, body []byte) {
	t.Helper()
	var value any
	if err := json.Unmarshal(body, &value); err != nil {
		t.Fatal(err)
	}
	var walk func(any)
	walk = func(current any) {
		switch typed := current.(type) {
		case map[string]any:
			for key, nested := range typed {
				normalized := strings.ToLower(key)
				for _, forbidden := range []string{"token", "secret", "password", "subscription_url", "token_hash", "secret_hash"} {
					if normalized == forbidden {
						t.Fatalf("sensitive key %q in %s", key, body)
					}
				}
				walk(nested)
			}
		case []any:
			for _, nested := range typed {
				walk(nested)
			}
		}
	}
	walk(value)
}
