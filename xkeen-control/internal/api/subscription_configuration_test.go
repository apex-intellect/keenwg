package api

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/subscriptionconfig"
)

func TestSubscriptionConfigurationNeverReturnsSecret(t *testing.T) {
	service := &fakeSubscriptionConfiguration{configured: true}
	server, owner, _, viewer := subscriptionConfigurationFixture(t, service)
	secret := "https://vpn.example.test/sub/private"

	get := subscriptionConfigurationRequest(http.MethodGet, subscriptionConfigurationPath, viewer.Token, "")
	getResponse := httptest.NewRecorder()
	server.ServeHTTP(getResponse, get)
	if getResponse.Code != http.StatusOK || strings.Contains(getResponse.Body.String(), "subscription_url") || strings.Contains(getResponse.Body.String(), secret) {
		t.Fatalf("GET status=%d body=%s", getResponse.Code, getResponse.Body.String())
	}

	put := subscriptionConfigurationRequest(
		http.MethodPut, subscriptionConfigurationPath, owner.Token,
		`{"schema_version":1,"subscription_url":"`+secret+`"}`,
	)
	putResponse := httptest.NewRecorder()
	server.ServeHTTP(putResponse, put)
	if putResponse.Code != http.StatusOK || service.replaced != secret || strings.Contains(putResponse.Body.String(), secret) || strings.Contains(putResponse.Body.String(), "subscription_url") {
		t.Fatalf("PUT status=%d body=%s replaced=%q", putResponse.Code, putResponse.Body.String(), service.replaced)
	}
}

func TestSubscriptionConfigurationPutRequiresOwner(t *testing.T) {
	server, _, operator, viewer := subscriptionConfigurationFixture(t, &fakeSubscriptionConfiguration{})
	body := `{"schema_version":1,"subscription_url":"https://vpn.example.test/sub/private"}`
	for name, token := range map[string]string{"operator": operator.Token, "viewer": viewer.Token} {
		t.Run(name, func(t *testing.T) {
			response := httptest.NewRecorder()
			server.ServeHTTP(response, subscriptionConfigurationRequest(http.MethodPut, subscriptionConfigurationPath, token, body))
			if response.Code != http.StatusForbidden {
				t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
			}
		})
	}
}

func TestSubscriptionConfigurationRejectsUnsafeRequestsWithoutEcho(t *testing.T) {
	service := &fakeSubscriptionConfiguration{}
	server, owner, _, _ := subscriptionConfigurationFixture(t, service)
	secret := "https://vpn.example.test/sub/private"
	tests := []struct {
		name, path, body string
		want             int
	}{
		{"unknown field", subscriptionConfigurationPath, `{"schema_version":1,"subscription_url":"` + secret + `","debug":true}`, http.StatusBadRequest},
		{"missing schema", subscriptionConfigurationPath, `{"subscription_url":"` + secret + `"}`, http.StatusBadRequest},
		{"wrong source", "/v1/connections/sources/other/configuration", `{"schema_version":1,"subscription_url":"` + secret + `"}`, http.StatusNotFound},
		{"oversize", subscriptionConfigurationPath, `{"schema_version":1,"subscription_url":"https://vpn.example.test/` + strings.Repeat("x", int(maxSubscriptionConfigurationBody)) + `"}`, http.StatusRequestEntityTooLarge},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			response := httptest.NewRecorder()
			server.ServeHTTP(response, subscriptionConfigurationRequest(http.MethodPut, test.path, owner.Token, test.body))
			if response.Code != test.want || strings.Contains(response.Body.String(), secret) {
				t.Fatalf("status=%d want=%d body=%s", response.Code, test.want, response.Body.String())
			}
		})
	}
}

func TestSubscriptionConfigurationMapsValidationAndStorageFailures(t *testing.T) {
	for name, failure := range map[string]struct {
		failure    error
		wantStatus int
		wantCode   string
	}{
		"invalid": {subscriptionconfig.ErrInvalid, http.StatusBadRequest, "invalid_subscription_url"},
		"storage": {subscriptionconfig.ErrStorage, http.StatusServiceUnavailable, "subscription_configuration_unavailable"},
	} {
		t.Run(name, func(t *testing.T) {
			service := &fakeSubscriptionConfiguration{err: failure.failure}
			server, owner, _, _ := subscriptionConfigurationFixture(t, service)
			body := `{"schema_version":1,"subscription_url":"https://vpn.example.test/sub/private"}`
			response := httptest.NewRecorder()
			server.ServeHTTP(response, subscriptionConfigurationRequest(http.MethodPut, subscriptionConfigurationPath, owner.Token, body))
			if response.Code != failure.wantStatus || !strings.Contains(response.Body.String(), failure.wantCode) || strings.Contains(response.Body.String(), "vpn.example.test") {
				t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
			}
		})
	}
}

type fakeSubscriptionConfiguration struct {
	configured bool
	replaced   string
	err        error
}

func (f *fakeSubscriptionConfiguration) Configured() bool { return f.configured }
func (f *fakeSubscriptionConfiguration) Replace(value string) error {
	if f.err != nil {
		return f.err
	}
	f.replaced = value
	f.configured = true
	return nil
}

func subscriptionConfigurationFixture(
	t *testing.T,
	service SubscriptionConfiguration,
) (*SecureServer, auth.PlainCredential, auth.PlainCredential, auth.PlainCredential) {
	t.Helper()
	core, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	owner := pairDevice(t, devices, auth.ScopeOwner, "Owner")
	operator := pairFromOwner(t, devices, auth.ScopeOperator, "Operator")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	return NewSecure(core, devices, staticCapabilities(), WithSubscriptionConfiguration(service)), owner, operator, viewer
}

func subscriptionConfigurationRequest(method, path, token, body string) *http.Request {
	request := httptest.NewRequest(method, path, strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+token)
	if body != "" {
		request.Header.Set("Content-Type", "application/json")
	}
	return request
}
