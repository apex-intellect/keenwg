package adapter

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/goldb/keenwg/xkeen-control/internal/catalog"
)

const (
	awgManagerAdapterID  = "awgmanager"
	awgManager216OpenAPI = "579663e4e22f2ffe789863fa7a3851a50bd29936e41805206f9fda6489efe644"
	maxAWGBody           = 1 << 20
)

type AWGManagerOptions struct {
	BaseURL  string
	Login    string
	Password string
	DelayURL string
}

type AWGManagerAdapter struct {
	options   AWGManagerOptions
	base      *url.URL
	http      *http.Client
	now       func() time.Time
	allowlist map[string]struct{}
	configErr error

	mu            sync.Mutex
	session       string
	compatible    bool
	references    map[string]awgNodeReference
	subscriptions map[string]string
}

type awgSubscriptionList struct {
	Success bool              `json:"success"`
	Data    []awgSubscription `json:"data"`
}

type awgSubscription struct {
	ID           string      `json:"id"`
	Label        string      `json:"label"`
	Enabled      bool        `json:"enabled"`
	Mode         string      `json:"mode"`
	SelectorTag  string      `json:"selectorTag"`
	ActiveMember string      `json:"activeMember"`
	LastFetched  string      `json:"lastFetched"`
	LastError    string      `json:"lastError"`
	Members      []awgMember `json:"members"`
}

type awgMember struct {
	Tag       string `json:"tag"`
	Label     string `json:"label"`
	Protocol  string `json:"protocol"`
	Server    string `json:"server"`
	Port      int    `json:"port"`
	Transport string `json:"transport"`
	Security  string `json:"security"`
	SNI       string `json:"sni"`
}

func NewAWGManagerAdapter(options AWGManagerOptions, client *http.Client, additionalHashes ...string) *AWGManagerAdapter {
	base, configErr := validateAWGManagerOptions(options)
	if options.DelayURL == "" {
		options.DelayURL = "https://www.gstatic.com/generate_204"
	}
	if client == nil {
		client = &http.Client{
			Timeout:   15 * time.Second,
			Transport: &http.Transport{Proxy: nil, DialContext: (&net.Dialer{Timeout: 3 * time.Second}).DialContext},
		}
	}
	isolated := *client
	isolated.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	allowlist := map[string]struct{}{awgManager216OpenAPI: {}}
	for _, value := range additionalHashes {
		value = strings.ToLower(value)
		if len(value) == 64 {
			allowlist[value] = struct{}{}
		}
	}
	return &AWGManagerAdapter{
		options: options, base: base, http: &isolated, now: time.Now, allowlist: allowlist, configErr: configErr,
	}
}

func (a *AWGManagerAdapter) ID() string { return awgManagerAdapterID }

func (a *AWGManagerAdapter) Discover(ctx context.Context) Discovery {
	if a == nil || a.configErr != nil {
		return Discovery{Reason: "awg_not_configured"}
	}
	err := a.ensureCompatible(ctx)
	switch {
	case err == nil:
		return Discovery{Available: true, Writable: true}
	case errors.Is(err, ErrUnsupportedSchema):
		return Discovery{Reason: "awg_openapi_unsupported"}
	default:
		return Discovery{Reason: "awg_auth_failed"}
	}
}

func (a *AWGManagerAdapter) Snapshot(ctx context.Context) (Projection, error) {
	if err := a.ensureCompatible(ctx); err != nil {
		return Projection{}, err
	}
	body, status, err := a.authorized(ctx, http.MethodGet, "/api/singbox/subscriptions", nil, nil)
	if err != nil || status != http.StatusOK {
		return Projection{}, ErrUnavailable
	}
	var response awgSubscriptionList
	if err := json.Unmarshal(body, &response); err != nil || !response.Success || response.Data == nil || len(response.Data) > 64 {
		return Projection{}, ErrUnsupportedSchema
	}
	projection := Projection{AdapterID: awgManagerAdapterID, Sources: []catalog.Source{}, Nodes: []catalog.Node{}}
	references := make(map[string]awgNodeReference)
	subscriptions := make(map[string]string)
	versionInput := make([]awgVersionSubscription, 0, len(response.Data))
	for _, subscription := range response.Data {
		if !validAWGID(subscription.ID) || !validAWGLabel(subscription.Label) || !validAWGID(subscription.SelectorTag) ||
			(subscription.Mode != "selector" && subscription.Mode != "urltest") || len(subscription.Members) > maxSelectorNodes {
			return Projection{}, ErrUnsupportedSchema
		}
		active, err := a.activeNow(ctx, subscription.ID)
		if err != nil {
			return Projection{}, err
		}
		sourceID := awgSourceID(subscription.ID)
		subscriptions[sourceID] = subscription.ID
		statusValue := catalog.SourceReady
		warnings := []string{}
		if subscription.LastError != "" {
			statusValue = catalog.SourceError
			warnings = append(warnings, "provider_refresh_failed")
		}
		var refreshed *time.Time
		if parsed, err := time.Parse(time.RFC3339, subscription.LastFetched); err == nil {
			parsed = parsed.UTC()
			refreshed = &parsed
		}
		projection.Sources = append(projection.Sources, catalog.Source{
			ID: sourceID, GroupID: "primary", Kind: catalog.SourceForeign, Label: subscription.Label,
			AdapterID: awgManagerAdapterID, Status: statusValue, NodeCount: len(subscription.Members),
			LastRefresh: refreshed, Warnings: warnings, Foreign: true,
		})
		version := awgVersionSubscription{ID: subscription.ID, Enabled: subscription.Enabled, Mode: subscription.Mode, Active: active}
		seen := make(map[string]struct{}, len(subscription.Members))
		for _, member := range subscription.Members {
			protocol, ok := awgProtocol(member.Protocol)
			if !ok || !validAWGID(member.Tag) || !validAWGLabel(member.Label) || member.Port < 1 || member.Port > 65535 || member.Server == "" {
				return Projection{}, ErrUnsupportedSchema
			}
			if _, duplicate := seen[member.Tag]; duplicate {
				return Projection{}, ErrUnsupportedSchema
			}
			seen[member.Tag] = struct{}{}
			nodeID := awgNodeID(subscription.ID, member.Tag)
			activatable := subscription.Enabled && subscription.Mode == "selector"
			projection.Nodes = append(projection.Nodes, catalog.Node{
				ID: nodeID, SourceID: sourceID, GroupID: "primary",
				DisplayName: member.Label, Protocol: protocol, Host: member.Server, Port: member.Port,
				Transport: member.Transport, Security: member.Security, ServerName: member.SNI,
				Active: member.Tag == active, Testable: true,
				Activatable: activatable, Warnings: []string{},
			})
			references[nodeID] = awgNodeReference{
				SubscriptionID: subscription.ID, Selector: subscription.SelectorTag, Member: member.Tag,
				SourceID: sourceID, Activatable: activatable,
			}
			version.Members = append(version.Members, awgVersionMember{
				Tag: member.Tag, Protocol: member.Protocol, Server: member.Server, Port: member.Port,
			})
		}
		if active != "" {
			if _, exists := seen[active]; !exists {
				return Projection{}, ErrUnsupportedSchema
			}
		}
		versionInput = append(versionInput, version)
	}
	canonical, _ := json.Marshal(versionInput)
	digest := sha256.Sum256(canonical)
	projection.StateVersion = binary.BigEndian.Uint64(digest[:8])
	if projection.StateVersion == 0 {
		projection.StateVersion = 1
	}
	for index := range projection.Sources {
		projection.Sources[index].AdapterStateVersion = projection.StateVersion
	}
	a.mu.Lock()
	a.references = references
	a.subscriptions = subscriptions
	a.mu.Unlock()
	return projection, nil
}

// Refresh re-fetches one AWG Manager subscription without exposing its provider URL.
func (a *AWGManagerAdapter) Refresh(ctx context.Context, sourceID string) OperationResult {
	a.mu.Lock()
	subscriptionID := a.subscriptions[sourceID]
	a.mu.Unlock()
	if subscriptionID == "" {
		if _, err := a.Snapshot(ctx); err != nil {
			return OperationResult{Result: ResultUncertain, ErrorCode: "awg_unavailable"}
		}
		a.mu.Lock()
		subscriptionID = a.subscriptions[sourceID]
		a.mu.Unlock()
	}
	if subscriptionID == "" {
		return OperationResult{Result: ResultRejected, ErrorCode: "source_not_found"}
	}
	body, status, err := a.authorized(ctx, http.MethodPost, "/api/singbox/subscriptions/refresh", url.Values{"id": []string{subscriptionID}}, nil)
	if err != nil {
		return OperationResult{Result: ResultUncertain, ErrorCode: "refresh_unavailable"}
	}
	if status != http.StatusOK {
		return OperationResult{Result: ResultRejected, ErrorCode: "refresh_rejected"}
	}
	var response struct {
		Success bool            `json:"success"`
		Data    json.RawMessage `json:"data"`
	}
	if err := decodeStrict(body, &response); err != nil || !response.Success || len(response.Data) == 0 {
		return OperationResult{Result: ResultUncertain, ErrorCode: "unsupported_schema"}
	}
	projection, err := a.Snapshot(ctx)
	if err != nil {
		return OperationResult{Result: ResultUncertain, ErrorCode: "readback_failed"}
	}
	for _, source := range projection.Sources {
		if source.ID == sourceID {
			return OperationResult{Result: ResultCommitted}
		}
	}
	return OperationResult{Result: ResultUncertain, ErrorCode: "source_missing_after_refresh"}
}

func (a *AWGManagerAdapter) Test(ctx context.Context, nodeID string) TestResult {
	reference, err := a.reference(ctx, nodeID)
	if err != nil {
		return TestResult{NodeID: nodeID, ErrorCode: "node_not_found"}
	}
	payload, _ := json.Marshal(struct {
		Group   string `json:"group"`
		Timeout int    `json:"timeout"`
		URL     string `json:"url"`
	}{reference.Selector, 3000, a.options.DelayURL})
	body, status, err := a.authorized(ctx, http.MethodPost, "/api/singbox/router/proxies/test", nil, payload)
	if err != nil || status != http.StatusOK {
		return TestResult{NodeID: nodeID, ErrorCode: "test_unavailable", ObservedAt: a.now().UTC()}
	}
	var response struct {
		Success bool `json:"success"`
		Data    struct {
			Delays map[string]int64 `json:"delays"`
		} `json:"data"`
	}
	if err := decodeStrict(body, &response); err != nil || !response.Success || response.Data.Delays == nil {
		return TestResult{NodeID: nodeID, ErrorCode: "unsupported_schema", ObservedAt: a.now().UTC()}
	}
	delay, exists := response.Data.Delays[reference.Member]
	return TestResult{
		NodeID: nodeID, Reachable: exists && delay > 0, LatencyMS: delay,
		ErrorCode: awgTestError(exists, delay), ObservedAt: a.now().UTC(),
	}
}

func (a *AWGManagerAdapter) PlanActivation(ctx context.Context, nodeID string, reviewed uint64) (ActivationPlan, error) {
	projection, err := a.Snapshot(ctx)
	if err != nil {
		return ActivationPlan{}, err
	}
	if projection.StateVersion != reviewed {
		return ActivationPlan{}, ErrStaleState
	}
	reference, err := a.referenceFromProjection(projection, nodeID)
	if err != nil || !reference.Activatable {
		return ActivationPlan{}, ErrNodeNotFound
	}
	return ActivationPlan{
		AdapterID: awgManagerAdapterID, NodeID: nodeID, ReviewedStateVersion: reviewed,
		PreviousNodeID: activeNodeForSource(projection, reference.SourceID),
		Opaque:         reference.SubscriptionID + "\n" + reference.Selector + "\n" + reference.Member,
	}, nil
}

func (a *AWGManagerAdapter) Activate(ctx context.Context, plan ActivationPlan) OperationResult {
	parts := strings.Split(plan.Opaque, "\n")
	if plan.AdapterID != awgManagerAdapterID || len(parts) != 3 || !validAWGID(parts[0]) || !validAWGID(parts[1]) || !validAWGID(parts[2]) {
		return OperationResult{Result: ResultRejected, ErrorCode: "invalid_plan"}
	}
	before, err := a.Snapshot(ctx)
	if err != nil {
		return OperationResult{Result: ResultUncertain, ErrorCode: "awg_unavailable"}
	}
	if before.StateVersion != plan.ReviewedStateVersion || !projectionHasNode(before, plan.NodeID) {
		return OperationResult{Result: ResultRejected, ErrorCode: "stale_state"}
	}
	payload, _ := json.Marshal(struct {
		MemberTag string `json:"memberTag"`
	}{parts[2]})
	query := url.Values{"id": []string{parts[0]}}
	_, status, postErr := a.authorized(ctx, http.MethodPost, "/api/singbox/subscriptions/active-member", query, payload)
	if postErr == nil && status != http.StatusOK {
		postErr = ErrUnavailable
	}
	active, readErr := a.activeNow(ctx, parts[0])
	if readErr != nil {
		return OperationResult{Result: ResultUncertain, ErrorCode: "readback_failed"}
	}
	activeID := awgNodeID(parts[0], active)
	if active == parts[2] {
		return OperationResult{Result: ResultCommitted, NodeID: plan.NodeID}
	}
	if activeID == plan.PreviousNodeID {
		if postErr != nil {
			return OperationResult{Result: ResultRejected, ErrorCode: "no_change"}
		}
		return OperationResult{Result: ResultRolledBack, ErrorCode: "activation_not_applied"}
	}
	return OperationResult{Result: ResultUncertain, ErrorCode: "unexpected_readback"}
}

func (a *AWGManagerAdapter) Close(ctx context.Context) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.session == "" {
		return nil
	}
	_, status, err := a.raw(ctx, http.MethodPost, "/api/auth/logout", nil, nil, a.session)
	a.session = ""
	a.compatible = false
	if err != nil || status != http.StatusOK {
		return ErrUnavailable
	}
	return nil
}

func (a *AWGManagerAdapter) ensureCompatible(ctx context.Context) error {
	if a == nil || a.configErr != nil {
		return ErrInvalidAdapter
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.session == "" {
		payload, _ := json.Marshal(struct {
			Login    string `json:"login"`
			Password string `json:"password"`
		}{a.options.Login, a.options.Password})
		body, status, cookies, err := a.rawWithCookies(ctx, http.MethodPost, "/api/auth/login", nil, payload, "")
		if err != nil || status != http.StatusOK {
			return ErrUnavailable
		}
		var response struct {
			Success bool   `json:"success"`
			Login   string `json:"login"`
		}
		if err := decodeStrict(body, &response); err != nil || !response.Success || response.Login == "" {
			return ErrUnavailable
		}
		session := validAWGSessionCookie(cookies)
		if session == "" {
			return ErrUnavailable
		}
		a.session = session
		a.compatible = false
	}
	if a.compatible {
		return nil
	}
	body, status, err := a.raw(ctx, http.MethodGet, "/api/openapi.yaml", nil, nil, a.session)
	if err != nil || status != http.StatusOK {
		return ErrUnavailable
	}
	digest := sha256.Sum256(body)
	if _, exists := a.allowlist[hex.EncodeToString(digest[:])]; !exists {
		_, _, _ = a.raw(ctx, http.MethodPost, "/api/auth/logout", nil, nil, a.session)
		a.session = ""
		return ErrUnsupportedSchema
	}
	a.compatible = true
	return nil
}

func (a *AWGManagerAdapter) authorized(ctx context.Context, method, path string, query url.Values, payload []byte) ([]byte, int, error) {
	a.mu.Lock()
	session := a.session
	compatible := a.compatible
	a.mu.Unlock()
	if session == "" || !compatible {
		if err := a.ensureCompatible(ctx); err != nil {
			return nil, 0, err
		}
		a.mu.Lock()
		session = a.session
		a.mu.Unlock()
	}
	body, status, err := a.raw(ctx, method, path, query, payload, session)
	if status == http.StatusUnauthorized {
		a.mu.Lock()
		if a.session == session {
			a.session = ""
			a.compatible = false
		}
		a.mu.Unlock()
		return nil, status, ErrUnavailable
	}
	return body, status, err
}

func (a *AWGManagerAdapter) raw(ctx context.Context, method, path string, query url.Values, payload []byte, session string) ([]byte, int, error) {
	body, status, _, err := a.rawWithCookies(ctx, method, path, query, payload, session)
	return body, status, err
}

func (a *AWGManagerAdapter) rawWithCookies(ctx context.Context, method, path string, query url.Values, payload []byte, session string) ([]byte, int, []*http.Cookie, error) {
	endpoint := *a.base
	endpoint.Path = path
	endpoint.RawQuery = query.Encode()
	request, err := http.NewRequestWithContext(ctx, method, endpoint.String(), bytes.NewReader(payload))
	if err != nil {
		return nil, 0, nil, ErrUnavailable
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Cache-Control", "no-store")
	if payload != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	if session != "" {
		request.AddCookie(&http.Cookie{Name: "awg_session", Value: session})
	}
	response, err := a.http.Do(request)
	if err != nil {
		return nil, 0, nil, ErrUnavailable
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, maxAWGBody+1))
	if err != nil || len(body) > maxAWGBody {
		return nil, response.StatusCode, response.Cookies(), ErrUnsupportedSchema
	}
	return body, response.StatusCode, response.Cookies(), nil
}

func (a *AWGManagerAdapter) activeNow(ctx context.Context, subscriptionID string) (string, error) {
	body, status, err := a.authorized(ctx, http.MethodGet, "/api/singbox/subscriptions/active-now", url.Values{"id": []string{subscriptionID}}, nil)
	if err != nil || status != http.StatusOK {
		return "", ErrUnavailable
	}
	var response struct {
		Success bool `json:"success"`
		Data    struct {
			Now string `json:"now"`
		} `json:"data"`
	}
	if err := decodeStrict(body, &response); err != nil || !response.Success || (response.Data.Now != "" && !validAWGID(response.Data.Now)) {
		return "", ErrUnsupportedSchema
	}
	return response.Data.Now, nil
}

type awgNodeReference struct {
	SubscriptionID string
	Selector       string
	Member         string
	SourceID       string
	Activatable    bool
}

func (a *AWGManagerAdapter) reference(ctx context.Context, nodeID string) (awgNodeReference, error) {
	projection, err := a.Snapshot(ctx)
	if err != nil {
		return awgNodeReference{}, err
	}
	return a.referenceFromProjection(projection, nodeID)
}

func (a *AWGManagerAdapter) referenceFromProjection(projection Projection, nodeID string) (awgNodeReference, error) {
	if !projectionHasNode(projection, nodeID) {
		return awgNodeReference{}, ErrNodeNotFound
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	reference, exists := a.references[nodeID]
	if !exists {
		return awgNodeReference{}, ErrNodeNotFound
	}
	return reference, nil
}

type awgVersionMember struct {
	Tag      string `json:"tag"`
	Protocol string `json:"protocol"`
	Server   string `json:"server"`
	Port     int    `json:"port"`
}

type awgVersionSubscription struct {
	ID      string             `json:"id"`
	Enabled bool               `json:"enabled"`
	Mode    string             `json:"mode"`
	Active  string             `json:"active"`
	Members []awgVersionMember `json:"members"`
}

func validateAWGManagerOptions(options AWGManagerOptions) (*url.URL, error) {
	base, err := url.Parse(options.BaseURL)
	if err != nil || base.Scheme != "http" || base.User != nil || base.RawQuery != "" || base.Fragment != "" ||
		(base.Path != "" && base.Path != "/") || base.Port() == "" {
		return nil, ErrInvalidAdapter
	}
	address, addressErr := netip.ParseAddr(base.Hostname())
	port, portErr := strconv.Atoi(base.Port())
	if addressErr != nil || !address.IsLoopback() || portErr != nil || port < 1 || port > 65535 ||
		options.Login == "" || len(options.Login) > 128 || options.Password == "" || len(options.Password) > 512 ||
		strings.ContainsAny(options.Login+options.Password, "\r\n\x00") {
		return nil, ErrInvalidAdapter
	}
	base.Path = ""
	return base, nil
}

func validAWGSessionCookie(cookies []*http.Cookie) string {
	for _, cookie := range cookies {
		if cookie.Name == "awg_session" && cookie.Value != "" && len(cookie.Value) <= 256 && cookie.Path == "/" && cookie.Domain == "" &&
			cookie.HttpOnly && !cookie.Secure && cookie.SameSite == http.SameSiteStrictMode && cookie.MaxAge > 0 &&
			!strings.ContainsAny(cookie.Value, "\r\n\x00; ") {
			return cookie.Value
		}
	}
	return ""
}

func validAWGID(value string) bool {
	return value != "" && len(value) <= 128 && !strings.ContainsAny(value, "\r\n\x00/?#")
}

func validAWGLabel(value string) bool {
	return strings.TrimSpace(value) != "" && len([]rune(value)) <= 128 && !strings.ContainsAny(value, "\r\n\x00")
}

func awgProtocol(value string) (catalog.Protocol, bool) {
	if strings.EqualFold(value, "amneziawg") || strings.EqualFold(value, "awg") {
		return catalog.ProtocolAmneziaWG, true
	}
	return clashProtocol(value)
}

func awgSourceID(subscriptionID string) string {
	digest := sha256.Sum256([]byte("awgmanager-source\x00" + subscriptionID))
	return "awg-" + hex.EncodeToString(digest[:12])
}

func awgNodeID(subscriptionID, member string) string {
	digest := sha256.Sum256([]byte("awgmanager-node\x00" + subscriptionID + "\x00" + member))
	return "awg-" + hex.EncodeToString(digest[:16])
}

func activeNodeForSource(projection Projection, sourceID string) string {
	for _, node := range projection.Nodes {
		if node.SourceID == sourceID && node.Active {
			return node.ID
		}
	}
	return ""
}

func awgTestError(exists bool, delay int64) string {
	if !exists {
		return "member_missing"
	}
	if delay <= 0 {
		return "unreachable"
	}
	return ""
}
