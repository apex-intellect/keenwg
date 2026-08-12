package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/capability"
)

type DeviceStore interface {
	Authenticate(context.Context, string) (auth.Device, error)
	ListDevices(context.Context) ([]auth.Device, error)
	RevokeDevice(context.Context, string) error
	CreateOffer(context.Context, auth.Scope, auth.Scope, time.Duration) (auth.PlainOffer, error)
	RevokeOffer(context.Context, string) error
	Exchange(context.Context, string, string, string) (auth.PlainCredential, error)
}

type CapabilityProvider interface {
	Detect(context.Context) (capability.Document, error)
}

type SecureServer struct {
	core                      *Server
	devices                   DeviceStore
	capabilities              CapabilityProvider
	catalog                   CatalogStore
	connections               ConnectionCoordinator
	routes                    RouteExplainer
	scenarios                 ScenarioManager
	recovery                  RecoveryManager
	support                   SupportReporter
	backup                    BackupManager
	routerLocal               RouterLocalService
	subscriptionConfiguration SubscriptionConfiguration
	limiter                   *attemptLimiter
}

type SecureOption func(*SecureServer)

func WithCatalog(store CatalogStore) SecureOption {
	return func(server *SecureServer) { server.catalog = store }
}

func WithConnectionCoordinator(coordinator ConnectionCoordinator) SecureOption {
	return func(server *SecureServer) { server.connections = coordinator }
}

func WithRouteExplainer(explainer RouteExplainer) SecureOption {
	return func(server *SecureServer) { server.routes = explainer }
}

func WithScenarios(manager ScenarioManager) SecureOption {
	return func(server *SecureServer) { server.scenarios = manager }
}

func WithRecovery(manager RecoveryManager) SecureOption {
	return func(server *SecureServer) { server.recovery = manager }
}

func WithSupport(reporter SupportReporter) SecureOption {
	return func(server *SecureServer) { server.support = reporter }
}

func WithBackup(manager BackupManager) SecureOption {
	return func(server *SecureServer) { server.backup = manager }
}

func WithSubscriptionConfiguration(value SubscriptionConfiguration) SecureOption {
	return func(server *SecureServer) { server.subscriptionConfiguration = value }
}

func NewSecure(core *Server, devices DeviceStore, capabilities CapabilityProvider, options ...SecureOption) *SecureServer {
	server := &SecureServer{
		core: core, devices: devices, capabilities: capabilities,
		limiter: newAttemptLimiter(5, time.Minute, 1024),
	}
	for _, option := range options {
		option(server)
	}
	return server
}

func (s *SecureServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if r.URL.RawQuery != "" {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	switch {
	case r.URL.Path == "/v1/health":
		clone := r.Clone(r.Context())
		clone.URL.Path = "/v1/xkeen/health"
		s.core.ServeHTTP(w, clone)
	case r.URL.Path == "/v1/pairing/exchange":
		s.handleExchange(w, r)
	case r.URL.Path == "/v1/capabilities":
		request, principal, ok := s.authenticate(w, r, auth.ScopeViewer)
		if ok {
			s.handleCapabilities(w, request, principal)
		}
	case r.URL.Path == "/v1/connections/catalog" && s.catalog != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeViewer)
		if ok {
			s.handleCatalog(w, request)
		}
	case r.URL.Path == "/v1/network/devices" && s.routerLocal != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeViewer)
		if ok {
			s.handleHomeDevices(w, request)
		}
	case r.URL.Path == "/v1/access/wireguard" && s.routerLocal != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeViewer)
		if ok {
			s.handleWireGuard(w, request)
		}
	case r.URL.Path == "/v1/access/wireguard/peers/review" && s.routerLocal != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeViewer)
		if ok {
			s.handleWireGuardPeer(w, request, "review")
		}
	case r.URL.Path == "/v1/access/wireguard/peers/apply" && s.routerLocal != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeOperator)
		if ok {
			s.handleWireGuardPeer(w, request, "apply")
		}
	case isReservationRoute(r.URL.Path) && s.routerLocal != nil:
		deviceID, action, _ := reservationRoute(r.URL.Path)
		required := auth.ScopeViewer
		if action == "apply" {
			required = auth.ScopeOperator
		}
		request, _, ok := s.authenticate(w, r, required)
		if ok {
			s.handleReservation(w, request, deviceID, action)
		}
	case (r.URL.Path == "/v1/connections/groups" || r.URL.Path == "/v1/connections/sources") && s.catalog != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeOperator)
		if ok {
			s.handleCatalogMutation(w, request)
		}
	case isSubscriptionConfigurationRoute(r.URL.Path):
		if s.subscriptionConfiguration == nil {
			writeError(w, http.StatusNotFound, "not_found")
			return
		}
		required := auth.ScopeViewer
		if r.Method == http.MethodPut {
			required = auth.ScopeOwner
		}
		request, _, ok := s.authenticate(w, r, required)
		if ok {
			s.handleSubscriptionConfiguration(w, request)
		}
	case isConnectionOperationPath(r.URL.Path) && s.connections != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeOperator)
		if ok {
			s.handleConnectionOperation(w, request)
		}
	case r.URL.Path == "/v1/routes/explain" && s.routes != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeViewer)
		if ok {
			s.handleRouteExplain(w, request)
		}
	case r.URL.Path == "/v1/scenarios" && s.scenarios != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeViewer)
		if ok {
			s.handleScenarioCatalog(w, request)
		}
	case r.URL.Path == "/v1/recovery" && s.recovery != nil:
		required := auth.ScopeOperator
		if r.Method == http.MethodGet {
			required = auth.ScopeViewer
		}
		request, _, ok := s.authenticate(w, r, required)
		if ok {
			s.handleRecovery(w, request)
		}
	case r.URL.Path == "/v1/support/report" && s.support != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeViewer)
		if ok {
			s.handleSupport(w, request)
		}
	case r.URL.Path == "/v1/backup" && s.backup != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeOwner)
		if ok {
			s.handleBackup(w, request, "create")
		}
	case r.URL.Path == "/v1/backup/preview" && s.backup != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeOwner)
		if ok {
			s.handleBackup(w, request, "preview")
		}
	case r.URL.Path == "/v1/backup/apply" && s.backup != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeOwner)
		if ok {
			s.handleBackup(w, request, "apply")
		}
	case isScenarioPath(r.URL.Path, "/review") && s.scenarios != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeViewer)
		if ok {
			s.handleScenarioReview(w, request)
		}
	case isScenarioPath(r.URL.Path, "/apply") && s.scenarios != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeOperator)
		if ok {
			s.handleScenarioApply(w, request)
		}
	case strings.HasPrefix(r.URL.Path, "/v1/connections/sources/") && s.catalog != nil:
		request, _, ok := s.authenticate(w, r, auth.ScopeOperator)
		if ok {
			s.handleCatalogMutation(w, request)
		}
	case r.URL.Path == "/v1/devices":
		request, _, ok := s.authenticate(w, r, auth.ScopeOwner)
		if ok {
			s.handleDevices(w, request)
		}
	case r.URL.Path == "/v1/pairing/offers":
		request, principal, ok := s.authenticate(w, r, auth.ScopeOwner)
		if ok {
			s.handleCreateOffer(w, request, principal)
		}
	case strings.HasPrefix(r.URL.Path, "/v1/pairing/offers/"):
		request, _, ok := s.authenticate(w, r, auth.ScopeOwner)
		if ok {
			s.handleRevokeOffer(w, request)
		}
	case strings.HasPrefix(r.URL.Path, "/v1/devices/"):
		request, _, ok := s.authenticate(w, r, auth.ScopeOwner)
		if ok {
			s.handleRevokeDevice(w, request)
		}
	default:
		required, protected := coreScope(r)
		if !protected {
			s.core.ServeHTTP(w, r)
			return
		}
		request, _, ok := s.authenticate(w, r, required)
		if ok {
			s.core.ServeHTTP(w, request)
		}
	}
}

func (s *SecureServer) handleRevokeOffer(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		methodNotAllowed(w, http.MethodDelete)
		return
	}
	id, ok := onePathSegment(r.URL.Path, "/v1/pairing/offers/", "")
	if !ok {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	if err := s.devices.RevokeOffer(r.Context(), id); err != nil {
		if errors.Is(err, auth.ErrOfferNotFound) {
			writeError(w, http.StatusNotFound, "not_found")
		} else {
			writeError(w, http.StatusServiceUnavailable, "device_store_unavailable")
		}
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *SecureServer) authenticate(w http.ResponseWriter, r *http.Request, required auth.Scope) (*http.Request, Principal, bool) {
	header := r.Header.Get("Authorization")
	if !strings.HasPrefix(header, "Bearer ") || strings.Count(header, " ") != 1 {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return r, Principal{}, false
	}
	device, err := s.devices.Authenticate(r.Context(), strings.TrimPrefix(header, "Bearer "))
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return r, Principal{}, false
	}
	principal := Principal{DeviceID: device.ID, Scope: device.Scope}
	if !scopeAllows(principal.Scope, required) {
		writeError(w, http.StatusForbidden, "forbidden")
		return r, Principal{}, false
	}
	return withPrincipal(r, principal), principal, true
}

func (s *SecureServer) handleCapabilities(w http.ResponseWriter, r *http.Request, principal Principal) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w, http.MethodGet)
		return
	}
	document, err := s.capabilities.Detect(r.Context())
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "capabilities_unavailable")
		return
	}
	if document.Capabilities == nil {
		document.Capabilities = []capability.Capability{}
	} else {
		document.Capabilities = append([]capability.Capability(nil), document.Capabilities...)
	}
	if principal.Scope != auth.ScopeOwner {
		for index := range document.Capabilities {
			if document.Capabilities[index].ID == capability.SystemDevices {
				document.Capabilities[index].Access = capability.AccessNone
				document.Capabilities[index].Available = false
				document.Capabilities[index].Reason = "owner_required"
			}
		}
	}
	writeJSON(w, http.StatusOK, document)
}

func (s *SecureServer) handleDevices(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w, http.MethodGet)
		return
	}
	devices, err := s.devices.ListDevices(r.Context())
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "device_store_unavailable")
		return
	}
	if devices == nil {
		devices = []auth.Device{}
	}
	writeJSON(w, http.StatusOK, struct {
		SchemaVersion int           `json:"schema_version"`
		Devices       []auth.Device `json:"devices"`
	}{SchemaVersion: 1, Devices: devices})
}

func (s *SecureServer) handleRevokeDevice(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		methodNotAllowed(w, http.MethodDelete)
		return
	}
	id, ok := onePathSegment(r.URL.Path, "/v1/devices/", "")
	if !ok {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	if err := s.devices.RevokeDevice(r.Context(), id); err != nil {
		switch {
		case errors.Is(err, auth.ErrDeviceNotFound):
			writeError(w, http.StatusNotFound, "not_found")
		case errors.Is(err, auth.ErrLastOwner):
			writeError(w, http.StatusConflict, "last_owner")
		default:
			writeError(w, http.StatusServiceUnavailable, "device_store_unavailable")
		}
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *SecureServer) handleCreateOffer(w http.ResponseWriter, r *http.Request, principal Principal) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	var request struct {
		SchemaVersion int        `json:"schema_version"`
		Scope         auth.Scope `json:"scope"`
	}
	if !decodeSecureJSON(w, r, &request) {
		return
	}
	if request.SchemaVersion != 1 {
		writeError(w, http.StatusBadRequest, "unsupported_schema")
		return
	}
	offer, err := s.devices.CreateOffer(r.Context(), principal.Scope, request.Scope, 5*time.Minute)
	if err != nil {
		if errors.Is(err, auth.ErrInvalidScope) {
			writeError(w, http.StatusBadRequest, "invalid_scope")
		} else {
			writeError(w, http.StatusServiceUnavailable, "device_store_unavailable")
		}
		return
	}
	writeJSON(w, http.StatusCreated, struct {
		SchemaVersion int        `json:"schema_version"`
		OfferID       string     `json:"offer_id"`
		Secret        string     `json:"secret"`
		Scope         auth.Scope `json:"scope"`
		ExpiresAt     time.Time  `json:"expires_at"`
	}{1, offer.ID, offer.Secret, offer.Scope, offer.ExpiresAt})
}

func (s *SecureServer) handleExchange(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	if !s.limiter.Allow(remoteIP(r.RemoteAddr), time.Now()) {
		writeError(w, http.StatusTooManyRequests, "rate_limited")
		return
	}
	var request struct {
		SchemaVersion int    `json:"schema_version"`
		OfferID       string `json:"offer_id"`
		Secret        string `json:"secret"`
		DeviceLabel   string `json:"device_label"`
	}
	if !decodeSecureJSON(w, r, &request) {
		return
	}
	if request.SchemaVersion != 1 {
		writeError(w, http.StatusBadRequest, "unsupported_schema")
		return
	}
	credential, err := s.devices.Exchange(r.Context(), request.OfferID, request.Secret, request.DeviceLabel)
	if err != nil {
		switch {
		case errors.Is(err, auth.ErrUnauthorized):
			writeError(w, http.StatusUnauthorized, "unauthorized")
		case errors.Is(err, auth.ErrOfferUsed), errors.Is(err, auth.ErrOfferExpired), errors.Is(err, auth.ErrOfferNotFound):
			writeError(w, http.StatusConflict, "offer_unavailable")
		case errors.Is(err, auth.ErrInvalidLabel):
			writeError(w, http.StatusBadRequest, "invalid_label")
		default:
			writeError(w, http.StatusServiceUnavailable, "device_store_unavailable")
		}
		return
	}
	writeJSON(w, http.StatusOK, struct {
		SchemaVersion int        `json:"schema_version"`
		DeviceID      string     `json:"device_id"`
		Scope         auth.Scope `json:"scope"`
		Token         string     `json:"token"`
	}{1, credential.Device.ID, credential.Device.Scope, credential.Token})
}

func decodeSecureJSON(w http.ResponseWriter, r *http.Request, destination any) bool {
	return decodeSecureJSONLimit(w, r, destination, maxMutationBody)
}

func decodeSecureJSONLimit(w http.ResponseWriter, r *http.Request, destination any, limit int64) bool {
	mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || mediaType != "application/json" {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return false
	}
	if r.ContentLength > limit {
		writeError(w, http.StatusRequestEntityTooLarge, "request_too_large")
		return false
	}
	r.Body = http.MaxBytesReader(w, r.Body, limit)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			writeError(w, http.StatusRequestEntityTooLarge, "request_too_large")
		} else {
			writeError(w, http.StatusBadRequest, "invalid_request")
		}
		return false
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return false
	}
	return true
}

func coreScope(r *http.Request) (auth.Scope, bool) {
	switch {
	case r.URL.Path == "/v1/xkeen/status", r.URL.Path == "/v1/diagnostics/nodes", r.URL.Path == "/v1/network/domains":
		return auth.ScopeViewer, true
	case r.URL.Path == "/v1/network/exclusions":
		if r.Method == http.MethodGet {
			return auth.ScopeViewer, true
		}
		return auth.ScopeOperator, true
	case r.URL.Path == "/v1/network/domains/rules", strings.HasPrefix(r.URL.Path, "/v1/network/domains/rules/"):
		return auth.ScopeOperator, true
	case r.URL.Path == "/v1/xkeen/subscription/refresh":
		return auth.ScopeOperator, true
	case strings.HasPrefix(r.URL.Path, "/v1/xkeen/nodes/") && strings.HasSuffix(r.URL.Path, "/select"):
		return auth.ScopeOperator, true
	case strings.HasPrefix(r.URL.Path, "/v1/xkeen/operations/"):
		return auth.ScopeViewer, true
	default:
		return "", false
	}
}

func remoteIP(remoteAddress string) string {
	host, _, err := net.SplitHostPort(remoteAddress)
	if err == nil && host != "" {
		return host
	}
	return remoteAddress
}

type attemptWindow struct {
	started time.Time
	count   int
}

type attemptLimiter struct {
	mu         sync.Mutex
	limit      int
	window     time.Duration
	maxEntries int
	entries    map[string]attemptWindow
}

func newAttemptLimiter(limit int, window time.Duration, maxEntries int) *attemptLimiter {
	return &attemptLimiter{limit: limit, window: window, maxEntries: maxEntries, entries: make(map[string]attemptWindow)}
}

func (l *attemptLimiter) Allow(key string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	for existing, entry := range l.entries {
		if now.Sub(entry.started) >= l.window {
			delete(l.entries, existing)
		}
	}
	entry, exists := l.entries[key]
	if !exists {
		if len(l.entries) >= l.maxEntries {
			return false
		}
		l.entries[key] = attemptWindow{started: now, count: 1}
		return true
	}
	if entry.count >= l.limit {
		return false
	}
	entry.count++
	l.entries[key] = entry
	return true
}
