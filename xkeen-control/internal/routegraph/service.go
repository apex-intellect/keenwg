package routegraph

import (
	"context"
	"errors"
	"net/netip"
	"sort"
	"strings"
	"time"
	"unicode"

	"golang.org/x/net/idna"
)

var (
	ErrInvalidRequest      = errors.New("invalid_route_request")
	ErrEvidenceUnavailable = errors.New("route_evidence_unavailable")
)

type RuleKind string

const (
	RuleDevice  RuleKind = "device"
	RuleDomain  RuleKind = "domain"
	RuleSuffix  RuleKind = "suffix"
	RuleCIDR    RuleKind = "cidr"
	RuleGeoSite RuleKind = "geosite"
	RuleGeoIP   RuleKind = "geoip"
	RuleDefault RuleKind = "default"
)

type Request struct {
	SchemaVersion int    `json:"schema_version"`
	Domain        string `json:"domain,omitempty"`
	IP            string `json:"ip,omitempty"`
	DeviceID      string `json:"device_id,omitempty"`
	Protocol      string `json:"protocol,omitempty"`
	Port          int    `json:"port,omitempty"`
}

type Rule struct {
	ID       string   `json:"id"`
	Kind     RuleKind `json:"kind"`
	Value    string   `json:"value"`
	Outcome  string   `json:"outcome"`
	Domains  []string `json:"-"`
	Prefixes []string `json:"-"`
}

type DNSObservation struct {
	Answers    []string  `json:"answers"`
	ErrorCode  string    `json:"error,omitempty"`
	ObservedAt time.Time `json:"observed_at"`
}

type SelectorObservation struct {
	GroupID    string    `json:"group_id"`
	NodeID     string    `json:"node_id"`
	Observed   bool      `json:"observed"`
	ObservedAt time.Time `json:"observed_at"`
}

type EgressObservation struct {
	Route      string    `json:"route"`
	Address    string    `json:"address,omitempty"`
	ObservedAt time.Time `json:"observed_at"`
}

type QUICObservation struct {
	Supported bool   `json:"supported"`
	Reason    string `json:"reason,omitempty"`
}

type AdapterObservation struct {
	ID        string `json:"id"`
	Available bool   `json:"available"`
	Reason    string `json:"reason,omitempty"`
}

type Snapshot struct {
	ObservedAt   time.Time
	DNS          DNSObservation
	DeviceRules  []Rule
	Rules        []Rule
	Selector     *SelectorObservation
	Egress       *EgressObservation
	QUIC         QUICObservation
	GeoUpdatedAt time.Time
	GeoMaxAge    time.Duration
	Adapters     []AdapterObservation
	Warnings     []string
}

type Decision struct {
	Outcome    string `json:"outcome"`
	RuleID     string `json:"rule_id,omitempty"`
	Confidence string `json:"confidence"`
}

type Step struct {
	Kind       string    `json:"kind"`
	Label      string    `json:"label"`
	Source     string    `json:"source"`
	ObservedAt time.Time `json:"observed_at,omitempty"`
}

type Explanation struct {
	SchemaVersion   int                  `json:"schema_version"`
	Decision        Decision             `json:"decision"`
	Steps           []Step               `json:"steps"`
	ShadowedRuleIDs []string             `json:"shadowed_rule_ids"`
	Warnings        []string             `json:"warnings"`
	Adapters        []AdapterObservation `json:"adapters"`
	ObservedAt      time.Time            `json:"observed_at"`
}

type SnapshotProvider interface {
	Snapshot(context.Context, Request) (Snapshot, error)
}

type Service struct{ provider SnapshotProvider }

func NewService(provider SnapshotProvider) *Service { return &Service{provider: provider} }

func (s *Service) Explain(ctx context.Context, request Request) (Explanation, error) {
	request, err := canonicalRequest(request)
	if err != nil || s == nil || s.provider == nil {
		return Explanation{}, ErrInvalidRequest
	}
	snapshot, err := s.provider.Snapshot(ctx, request)
	if err != nil {
		return Explanation{}, ErrEvidenceUnavailable
	}
	return evaluate(request, snapshot), nil
}

func canonicalRequest(request Request) (Request, error) {
	if request.SchemaVersion != 1 || request.Port < 0 || request.Port > 65535 || strings.IndexFunc(request.DeviceID, unicode.IsControl) >= 0 || len(request.DeviceID) > 128 {
		return Request{}, ErrInvalidRequest
	}
	request.Protocol = strings.ToLower(strings.TrimSpace(request.Protocol))
	if request.Protocol != "" && request.Protocol != "tcp" && request.Protocol != "udp" {
		return Request{}, ErrInvalidRequest
	}
	request.Domain = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(request.Domain)), ".")
	request.IP = strings.TrimSpace(request.IP)
	if (request.Domain == "") == (request.IP == "") {
		return Request{}, ErrInvalidRequest
	}
	if request.Domain != "" {
		ascii, err := idna.Lookup.ToASCII(request.Domain)
		if err != nil || !validDomain(ascii) {
			return Request{}, ErrInvalidRequest
		}
		request.Domain = strings.ToLower(ascii)
	} else if address, err := netip.ParseAddr(request.IP); err != nil || !address.IsValid() {
		return Request{}, ErrInvalidRequest
	} else {
		request.IP = address.String()
	}
	return request, nil
}

func evaluate(request Request, snapshot Snapshot) Explanation {
	explanation := Explanation{
		SchemaVersion: 1,
		Decision:      Decision{Outcome: "unknown", Confidence: "inferred"},
		Steps:         []Step{}, ShadowedRuleIDs: []string{}, Warnings: []string{},
		Adapters: append([]AdapterObservation(nil), snapshot.Adapters...), ObservedAt: snapshot.ObservedAt,
	}
	for _, warning := range snapshot.Warnings {
		explanation.Warnings = appendUnique(explanation.Warnings, warning)
	}
	if len(snapshot.DNS.Answers) > 0 || snapshot.DNS.ErrorCode != "" {
		explanation.Steps = append(explanation.Steps, Step{Kind: "dns", Label: "dns_resolution", Source: "observed", ObservedAt: snapshot.DNS.ObservedAt})
	}
	if snapshot.DNS.ErrorCode != "" {
		explanation.Warnings = appendUnique(explanation.Warnings, snapshot.DNS.ErrorCode)
	}

	addresses := requestAddresses(request, snapshot.DNS.Answers)
	matched := make([]Rule, 0)
	for _, rule := range snapshot.DeviceRules {
		if rule.Kind == RuleDevice && request.DeviceID != "" && rule.Value == request.DeviceID {
			matched = append(matched, rule)
		}
	}
	for _, rule := range snapshot.Rules {
		if ruleMatches(rule, request.Domain, addresses) {
			matched = append(matched, rule)
		}
	}
	if len(matched) > 0 {
		selected := matched[0]
		explanation.Decision = Decision{Outcome: selected.Outcome, RuleID: selected.ID, Confidence: "inferred"}
		explanation.Steps = append(explanation.Steps, Step{Kind: "rule", Label: selected.ID, Source: "inferred", ObservedAt: snapshot.ObservedAt})
		for _, rule := range matched[1:] {
			explanation.ShadowedRuleIDs = append(explanation.ShadowedRuleIDs, rule.ID)
		}
	}
	if strings.HasPrefix(explanation.Decision.Outcome, "group:") {
		groupID := strings.TrimPrefix(explanation.Decision.Outcome, "group:")
		if snapshot.Selector != nil && snapshot.Selector.GroupID == groupID {
			source := "inferred"
			if snapshot.Selector.Observed {
				source = "observed"
			}
			explanation.Steps = append(explanation.Steps, Step{Kind: "selector", Label: snapshot.Selector.NodeID, Source: source, ObservedAt: snapshot.Selector.ObservedAt})
		} else {
			explanation.Warnings = appendUnique(explanation.Warnings, "selector_unavailable")
		}
	}
	if snapshot.Egress != nil {
		explanation.Steps = append(explanation.Steps, Step{Kind: "egress", Label: snapshot.Egress.Route, Source: "observed", ObservedAt: snapshot.Egress.ObservedAt})
	}
	if request.Protocol == "udp" && request.Port == 443 && !snapshot.QUIC.Supported {
		explanation.Warnings = appendUnique(explanation.Warnings, "quic_may_bypass")
	}
	if snapshot.GeoMaxAge > 0 && !snapshot.GeoUpdatedAt.IsZero() && snapshot.ObservedAt.Sub(snapshot.GeoUpdatedAt) > snapshot.GeoMaxAge {
		explanation.Warnings = appendUnique(explanation.Warnings, "geo_data_stale")
	}
	available, unavailable := 0, 0
	for _, adapter := range snapshot.Adapters {
		if adapter.Available {
			available++
		} else {
			unavailable++
		}
	}
	if unavailable > 0 {
		warning := "adapter_unavailable"
		if available > 0 {
			warning = "adapter_partial_failure"
		}
		explanation.Warnings = appendUnique(explanation.Warnings, warning)
	}
	sort.Strings(explanation.Warnings)
	return explanation
}

func ruleMatches(rule Rule, domain string, addresses []netip.Addr) bool {
	switch rule.Kind {
	case RuleDomain:
		return domain != "" && domain == strings.ToLower(strings.TrimSuffix(rule.Value, "."))
	case RuleSuffix:
		value := strings.ToLower(strings.Trim(strings.TrimSpace(rule.Value), "."))
		return domain != "" && (domain == value || strings.HasSuffix(domain, "."+value))
	case RuleCIDR:
		return prefixMatches(rule.Value, addresses)
	case RuleGeoSite:
		for _, member := range rule.Domains {
			value := strings.ToLower(strings.Trim(strings.TrimSpace(member), "."))
			if domain == value || strings.HasSuffix(domain, "."+value) {
				return true
			}
		}
	case RuleGeoIP:
		for _, prefix := range rule.Prefixes {
			if prefixMatches(prefix, addresses) {
				return true
			}
		}
	case RuleDefault:
		return true
	}
	return false
}

func prefixMatches(raw string, addresses []netip.Addr) bool {
	prefix, err := netip.ParsePrefix(strings.TrimSpace(raw))
	if err != nil {
		return false
	}
	for _, address := range addresses {
		if prefix.Contains(address) {
			return true
		}
	}
	return false
}

func requestAddresses(request Request, answers []string) []netip.Addr {
	result := make([]netip.Addr, 0, len(answers)+1)
	if request.IP != "" {
		if address, err := netip.ParseAddr(request.IP); err == nil {
			result = append(result, address)
		}
	}
	for _, raw := range answers {
		if address, err := netip.ParseAddr(raw); err == nil {
			result = append(result, address)
		}
	}
	return result
}

func validDomain(value string) bool {
	if len(value) > 253 || !strings.Contains(value, ".") {
		return false
	}
	for _, label := range strings.Split(value, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, char := range label {
			if !(char >= 'a' && char <= 'z') && !(char >= '0' && char <= '9') && char != '-' {
				return false
			}
		}
	}
	return true
}

func appendUnique(values []string, value string) []string {
	if value == "" {
		return values
	}
	for _, existing := range values {
		if existing == value {
			return values
		}
	}
	return append(values, value)
}
