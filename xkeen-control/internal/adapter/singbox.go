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
	"time"
	"unicode/utf8"

	"github.com/goldb/keenwg/xkeen-control/internal/catalog"
)

const (
	singBoxAdapterID = "singbox"
	maxClashBody     = 1 << 20
	maxSelectorNodes = 512
)

type SingBoxOptions struct {
	ControllerURL string
	Secret        string
	Selector      string
	DelayURL      string
}

type SingBoxAdapter struct {
	options SingBoxOptions
	base    *url.URL
	http    *http.Client
	now     func() time.Time
	err     error
}

type clashProxy struct {
	Type string   `json:"type"`
	Now  string   `json:"now,omitempty"`
	All  []string `json:"all,omitempty"`
}

type clashDocument struct {
	Proxies map[string]clashProxy `json:"proxies"`
}

func NewSingBoxAdapter(options SingBoxOptions, client *http.Client) *SingBoxAdapter {
	base, err := validateSingBoxOptions(options)
	if options.DelayURL == "" {
		options.DelayURL = "https://www.gstatic.com/generate_204"
	}
	if client == nil {
		client = &http.Client{
			Timeout: 10 * time.Second,
			Transport: &http.Transport{
				Proxy:       nil,
				DialContext: (&net.Dialer{Timeout: 3 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
			},
		}
	}
	isolatedClient := *client
	isolatedClient.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	return &SingBoxAdapter{options: options, base: base, http: &isolatedClient, now: time.Now, err: err}
}

func (s *SingBoxAdapter) ID() string { return singBoxAdapterID }

func (s *SingBoxAdapter) Discover(context.Context) Discovery {
	if s == nil || s.err != nil {
		return Discovery{Reason: "singbox_unsafe_controller"}
	}
	return Discovery{Available: true, Writable: true}
}

func (s *SingBoxAdapter) Snapshot(ctx context.Context) (Projection, error) {
	document, selector, version, err := s.readState(ctx)
	if err != nil {
		return Projection{}, err
	}
	port, _ := strconv.Atoi(s.base.Port())
	sourceID := singBoxSourceID(s.options.Selector)
	nodes := make([]catalog.Node, len(selector.All))
	for index, member := range selector.All {
		proxy := document.Proxies[member]
		protocol, ok := clashProtocol(proxy.Type)
		if !ok {
			return Projection{}, ErrUnsupportedSchema
		}
		nodes[index] = catalog.Node{
			ID: singBoxNodeID(s.options.Selector, member), SourceID: sourceID, GroupID: "primary",
			DisplayName: member, Protocol: protocol, Host: s.base.Hostname(), Port: port,
			Transport: "clash-api", Security: strings.ToLower(proxy.Type), Active: member == selector.Now,
			Testable: true, Activatable: true, Warnings: []string{"endpoint_hidden_by_clash_api"},
		}
	}
	return Projection{
		AdapterID: singBoxAdapterID, StateVersion: version,
		Sources: []catalog.Source{{
			ID: sourceID, GroupID: "primary", Kind: catalog.SourceForeign, Label: "sing-box · " + s.options.Selector,
			AdapterID: singBoxAdapterID, Status: catalog.SourceReady, NodeCount: len(nodes), Warnings: []string{}, Foreign: true,
			AdapterStateVersion: version,
		}},
		Nodes: nodes,
	}, nil
}

func (s *SingBoxAdapter) Test(ctx context.Context, nodeID string) TestResult {
	member, err := s.memberForNode(ctx, nodeID)
	if err != nil {
		return TestResult{NodeID: nodeID, ErrorCode: "node_not_found"}
	}
	endpoint := s.endpoint("proxies", member, "delay")
	query := endpoint.Query()
	query.Set("timeout", "3000")
	query.Set("url", s.options.DelayURL)
	endpoint.RawQuery = query.Encode()
	body, status, err := s.request(ctx, http.MethodGet, endpoint, nil)
	if err != nil || status != http.StatusOK {
		return TestResult{NodeID: nodeID, ErrorCode: "test_unavailable", ObservedAt: s.now().UTC()}
	}
	var response struct {
		Delay int64 `json:"delay"`
	}
	if err := decodeStrict(body, &response); err != nil || response.Delay < 0 {
		return TestResult{NodeID: nodeID, ErrorCode: "unsupported_schema", ObservedAt: s.now().UTC()}
	}
	return TestResult{NodeID: nodeID, Reachable: true, LatencyMS: response.Delay, ObservedAt: s.now().UTC()}
}

func (s *SingBoxAdapter) PlanActivation(ctx context.Context, nodeID string, reviewed uint64) (ActivationPlan, error) {
	projection, err := s.Snapshot(ctx)
	if err != nil {
		return ActivationPlan{}, err
	}
	if projection.StateVersion != reviewed {
		return ActivationPlan{}, ErrStaleState
	}
	member := ""
	previous := ""
	for _, node := range projection.Nodes {
		if node.ID == nodeID {
			member = node.DisplayName
		}
		if node.Active {
			previous = node.ID
		}
	}
	if member == "" {
		return ActivationPlan{}, ErrNodeNotFound
	}
	return ActivationPlan{
		AdapterID: singBoxAdapterID, NodeID: nodeID, ReviewedStateVersion: reviewed,
		PreviousNodeID: previous, Opaque: member,
	}, nil
}

func (s *SingBoxAdapter) Activate(ctx context.Context, plan ActivationPlan) OperationResult {
	if plan.AdapterID != singBoxAdapterID || plan.NodeID == "" || !validClashName(plan.Opaque) {
		return OperationResult{Result: ResultRejected, ErrorCode: "invalid_plan"}
	}
	before, err := s.Snapshot(ctx)
	if err != nil {
		return OperationResult{Result: ResultUncertain, ErrorCode: "singbox_unavailable"}
	}
	if before.StateVersion != plan.ReviewedStateVersion || !projectionHasNode(before, plan.NodeID) {
		return OperationResult{Result: ResultRejected, ErrorCode: "stale_state"}
	}
	payload, _ := json.Marshal(struct {
		Name string `json:"name"`
	}{Name: plan.Opaque})
	_, status, putErr := s.request(ctx, http.MethodPut, s.endpoint("proxies", s.options.Selector), payload)
	if putErr == nil && status != http.StatusNoContent && status != http.StatusOK {
		putErr = ErrUnavailable
	}
	after, readErr := s.Snapshot(ctx)
	if readErr != nil {
		return OperationResult{Result: ResultUncertain, ErrorCode: "readback_failed"}
	}
	active := activeNodeID(after)
	if active == plan.NodeID {
		return OperationResult{Result: ResultCommitted, NodeID: plan.NodeID}
	}
	if active == plan.PreviousNodeID {
		if putErr != nil {
			return OperationResult{Result: ResultRejected, ErrorCode: "no_change"}
		}
		return OperationResult{Result: ResultRolledBack, ErrorCode: "activation_not_applied"}
	}
	return OperationResult{Result: ResultUncertain, ErrorCode: "unexpected_readback"}
}

func (s *SingBoxAdapter) memberForNode(ctx context.Context, nodeID string) (string, error) {
	projection, err := s.Snapshot(ctx)
	if err != nil {
		return "", err
	}
	for _, node := range projection.Nodes {
		if node.ID == nodeID {
			return node.DisplayName, nil
		}
	}
	return "", ErrNodeNotFound
}

func (s *SingBoxAdapter) readState(ctx context.Context) (clashDocument, clashProxy, uint64, error) {
	if s == nil || s.err != nil {
		return clashDocument{}, clashProxy{}, 0, ErrUnavailable
	}
	body, status, err := s.request(ctx, http.MethodGet, s.endpoint("proxies"), nil)
	if err != nil || status != http.StatusOK {
		return clashDocument{}, clashProxy{}, 0, ErrUnavailable
	}
	var document clashDocument
	if err := json.Unmarshal(body, &document); err != nil || document.Proxies == nil || len(document.Proxies) > 2048 {
		return clashDocument{}, clashProxy{}, 0, ErrUnsupportedSchema
	}
	selector, exists := document.Proxies[s.options.Selector]
	if !exists || !strings.EqualFold(selector.Type, "selector") || len(selector.All) == 0 || len(selector.All) > maxSelectorNodes || !validClashName(selector.Now) {
		return clashDocument{}, clashProxy{}, 0, ErrUnsupportedSchema
	}
	seen := make(map[string]struct{}, len(selector.All))
	for _, member := range selector.All {
		proxy, exists := document.Proxies[member]
		if !exists || !validClashName(member) {
			return clashDocument{}, clashProxy{}, 0, ErrUnsupportedSchema
		}
		if _, duplicate := seen[member]; duplicate {
			return clashDocument{}, clashProxy{}, 0, ErrUnsupportedSchema
		}
		if _, ok := clashProtocol(proxy.Type); !ok {
			return clashDocument{}, clashProxy{}, 0, ErrUnsupportedSchema
		}
		seen[member] = struct{}{}
	}
	if _, exists := seen[selector.Now]; !exists {
		return clashDocument{}, clashProxy{}, 0, ErrUnsupportedSchema
	}
	type memberVersion struct {
		Name string `json:"name"`
		Type string `json:"type"`
	}
	members := make([]memberVersion, len(selector.All))
	for index, member := range selector.All {
		members[index] = memberVersion{Name: member, Type: document.Proxies[member].Type}
	}
	versionBody, _ := json.Marshal(struct {
		Selector string          `json:"selector"`
		Now      string          `json:"now"`
		Members  []memberVersion `json:"members"`
	}{s.options.Selector, selector.Now, members})
	digest := sha256.Sum256(versionBody)
	version := binary.BigEndian.Uint64(digest[:8])
	if version == 0 {
		version = 1
	}
	return document, selector, version, nil
}

func (s *SingBoxAdapter) request(ctx context.Context, method string, endpoint *url.URL, body []byte) ([]byte, int, error) {
	request, err := http.NewRequestWithContext(ctx, method, endpoint.String(), bytes.NewReader(body))
	if err != nil {
		return nil, 0, ErrUnavailable
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Authorization", "Bearer "+s.options.Secret)
	request.Header.Set("Cache-Control", "no-store")
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := s.http.Do(request)
	if err != nil {
		return nil, 0, err
	}
	defer response.Body.Close()
	limited := io.LimitReader(response.Body, maxClashBody+1)
	responseBody, err := io.ReadAll(limited)
	if err != nil || len(responseBody) > maxClashBody {
		return nil, response.StatusCode, ErrUnsupportedSchema
	}
	return responseBody, response.StatusCode, nil
}

func (s *SingBoxAdapter) endpoint(parts ...string) *url.URL {
	copyValue := *s.base
	copyValue.Path = "/" + strings.Join(parts, "/")
	return &copyValue
}

func validateSingBoxOptions(options SingBoxOptions) (*url.URL, error) {
	base, err := url.Parse(options.ControllerURL)
	if err != nil || base.Scheme != "http" || base.User != nil || base.RawQuery != "" || base.Fragment != "" ||
		(base.Path != "" && base.Path != "/") || base.Port() == "" {
		return nil, ErrInvalidAdapter
	}
	address, err := netip.ParseAddr(base.Hostname())
	port, portErr := strconv.Atoi(base.Port())
	if err != nil || !address.IsLoopback() || portErr != nil || port < 1 || port > 65535 ||
		!validBearerSecret(options.Secret) || !validClashName(options.Selector) {
		return nil, ErrInvalidAdapter
	}
	base.Path = ""
	return base, nil
}

func validBearerSecret(value string) bool {
	return value != "" && len(value) <= 256 && strings.TrimSpace(value) == value &&
		!strings.ContainsAny(value, "\r\n\x00 \t")
}

func validClashName(value string) bool {
	return value != "" && utf8.ValidString(value) && len([]rune(value)) <= 128 && strings.TrimSpace(value) == value &&
		!strings.ContainsAny(value, "/?#\r\n\x00")
}

func clashProtocol(value string) (catalog.Protocol, bool) {
	normalized := strings.ToLower(strings.ReplaceAll(value, "-", ""))
	switch normalized {
	case "vless":
		return catalog.ProtocolVLESS, true
	case "vmess":
		return catalog.ProtocolVMess, true
	case "trojan":
		return catalog.ProtocolTrojan, true
	case "hysteria2":
		return catalog.ProtocolHysteria2, true
	case "wireguard":
		return catalog.ProtocolWireGuard, true
	default:
		return "", false
	}
}

func singBoxSourceID(selector string) string {
	digest := sha256.Sum256([]byte("singbox-source\x00" + selector))
	return "singbox-" + hex.EncodeToString(digest[:12])
}

func singBoxNodeID(selector, member string) string {
	digest := sha256.Sum256([]byte("singbox-node\x00" + selector + "\x00" + member))
	return "singbox-" + hex.EncodeToString(digest[:16])
}

func projectionHasNode(projection Projection, nodeID string) bool {
	for _, node := range projection.Nodes {
		if node.ID == nodeID {
			return true
		}
	}
	return false
}

func activeNodeID(projection Projection) string {
	for _, node := range projection.Nodes {
		if node.Active {
			return node.ID
		}
	}
	return ""
}

func decodeStrict(body []byte, destination any) error {
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		return err
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return ErrUnsupportedSchema
	}
	return nil
}
