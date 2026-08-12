package api

import (
	"errors"
	"net/http"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/subscriptionconfig"
)

const (
	subscriptionConfigurationPath          = "/v1/connections/sources/xkeen-subscription/configuration"
	maxSubscriptionConfigurationBody int64 = 16 << 10
)

type SubscriptionConfiguration interface {
	Configured() bool
	Replace(string) error
}

func isSubscriptionConfigurationRoute(path string) bool {
	_, ok := onePathSegment(path, "/v1/connections/sources/", "/configuration")
	return ok
}

func (s *SecureServer) handleSubscriptionConfiguration(w http.ResponseWriter, r *http.Request) {
	sourceID, ok := onePathSegment(r.URL.Path, "/v1/connections/sources/", "/configuration")
	if !ok || sourceID != "xkeen-subscription" {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	switch r.Method {
	case http.MethodGet:
		writeSubscriptionConfiguration(w, s.subscriptionConfiguration.Configured())
	case http.MethodPut:
		var request struct {
			SchemaVersion   int    `json:"schema_version"`
			SubscriptionURL string `json:"subscription_url"`
		}
		if !decodeSecureJSONLimit(w, r, &request, maxSubscriptionConfigurationBody) {
			return
		}
		if request.SchemaVersion != 1 {
			writeError(w, http.StatusBadRequest, "unsupported_schema")
			return
		}
		if err := s.subscriptionConfiguration.Replace(request.SubscriptionURL); err != nil {
			switch {
			case errors.Is(err, subscriptionconfig.ErrInvalid):
				writeError(w, http.StatusBadRequest, "invalid_subscription_url")
			default:
				writeError(w, http.StatusServiceUnavailable, "subscription_configuration_unavailable")
			}
			return
		}
		writeSubscriptionConfiguration(w, s.subscriptionConfiguration.Configured())
	default:
		methodNotAllowed(w, http.MethodGet, http.MethodPut)
	}
}

func writeSubscriptionConfiguration(w http.ResponseWriter, configured bool) {
	writeJSON(w, http.StatusOK, struct {
		SchemaVersion int  `json:"schema_version"`
		Configured    bool `json:"configured"`
	}{SchemaVersion: 1, Configured: configured})
}
