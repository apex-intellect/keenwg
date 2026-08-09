package routegraph

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestExplainOrdersEvidenceAndReportsShadowing(t *testing.T) {
	now := time.Date(2026, 8, 9, 3, 0, 0, 0, time.UTC)
	service := NewService(staticProvider{snapshot: Snapshot{
		ObservedAt:  now,
		DNS:         DNSObservation{Answers: []string{"203.0.113.7"}, ObservedAt: now},
		DeviceRules: []Rule{{ID: "device-direct", Kind: RuleDevice, Value: "phone", Outcome: "direct"}},
		Rules: []Rule{
			{ID: "domain-vpn", Kind: RuleDomain, Value: "video.example", Outcome: "group:fast"},
			{ID: "cidr-direct", Kind: RuleCIDR, Value: "203.0.113.0/24", Outcome: "direct"},
			{ID: "geosite-direct", Kind: RuleGeoSite, Value: "media", Outcome: "direct", Domains: []string{"video.example"}},
			{ID: "geoip-direct", Kind: RuleGeoIP, Value: "test", Outcome: "direct", Prefixes: []string{"203.0.113.0/24"}},
		},
		Selector: &SelectorObservation{GroupID: "fast", NodeID: "node-a", Observed: true, ObservedAt: now},
		Egress:   &EgressObservation{Route: "vpn", Address: "198.51.100.0/24", ObservedAt: now},
	}})

	explanation, err := service.Explain(context.Background(), Request{SchemaVersion: 1, Domain: "video.example", DeviceID: "laptop", Protocol: "tcp", Port: 443})
	if err != nil {
		t.Fatal(err)
	}
	if explanation.Decision.Outcome != "group:fast" || explanation.Decision.RuleID != "domain-vpn" {
		t.Fatalf("decision=%+v", explanation.Decision)
	}
	if len(explanation.ShadowedRuleIDs) != 3 || explanation.ShadowedRuleIDs[0] != "cidr-direct" || explanation.ShadowedRuleIDs[2] != "geoip-direct" {
		t.Fatalf("shadowed=%v", explanation.ShadowedRuleIDs)
	}
	if len(explanation.Steps) < 4 || explanation.Steps[0].Kind != "dns" || explanation.Steps[1].Kind != "rule" || explanation.Steps[2].Kind != "selector" || explanation.Steps[3].Kind != "egress" {
		t.Fatalf("steps=%+v", explanation.Steps)
	}
}

func TestExplainDevicePolicyWinsAndQUICBypassIsVisible(t *testing.T) {
	now := time.Date(2026, 8, 9, 3, 0, 0, 0, time.UTC)
	service := NewService(staticProvider{snapshot: Snapshot{
		ObservedAt:  now,
		DNS:         DNSObservation{Answers: []string{"2001:db8::7"}, ObservedAt: now},
		DeviceRules: []Rule{{ID: "phone-direct", Kind: RuleDevice, Value: "phone", Outcome: "direct"}},
		Rules:       []Rule{{ID: "all-vpn", Kind: RuleSuffix, Value: "example", Outcome: "group:main"}},
		QUIC:        QUICObservation{Supported: false, Reason: "udp_not_routed"},
	}})

	explanation, err := service.Explain(context.Background(), Request{SchemaVersion: 1, Domain: "cdn.example", DeviceID: "phone", Protocol: "udp", Port: 443})
	if err != nil {
		t.Fatal(err)
	}
	if explanation.Decision.RuleID != "phone-direct" || !contains(explanation.Warnings, "quic_may_bypass") {
		t.Fatalf("explanation=%+v", explanation)
	}
}

func TestExplainMarksStaleGeoAndPartialAdapterFailure(t *testing.T) {
	now := time.Date(2026, 8, 9, 3, 0, 0, 0, time.UTC)
	service := NewService(staticProvider{snapshot: Snapshot{
		ObservedAt:   now,
		DNS:          DNSObservation{ErrorCode: "dns_unavailable", ObservedAt: now},
		GeoUpdatedAt: now.Add(-45 * 24 * time.Hour),
		GeoMaxAge:    30 * 24 * time.Hour,
		Adapters:     []AdapterObservation{{ID: "xkeen", Available: true}, {ID: "singbox", Available: false, Reason: "adapter_unavailable"}},
	}})

	explanation, err := service.Explain(context.Background(), Request{SchemaVersion: 1, IP: "192.0.2.5", Protocol: "tcp", Port: 443})
	if err != nil {
		t.Fatal(err)
	}
	for _, warning := range []string{"dns_unavailable", "geo_data_stale", "adapter_partial_failure"} {
		if !contains(explanation.Warnings, warning) {
			t.Fatalf("missing %q in %v", warning, explanation.Warnings)
		}
	}
	if explanation.Decision.Outcome != "unknown" {
		t.Fatalf("decision=%+v", explanation.Decision)
	}
}

func TestExplainRejectsInvalidInputAndProviderFailure(t *testing.T) {
	service := NewService(staticProvider{})
	if _, err := service.Explain(context.Background(), Request{SchemaVersion: 1, Domain: "bad name"}); !errors.Is(err, ErrInvalidRequest) {
		t.Fatalf("invalid err=%v", err)
	}
	service = NewService(staticProvider{err: errors.New("offline")})
	if _, err := service.Explain(context.Background(), Request{SchemaVersion: 1, IP: "192.0.2.1"}); !errors.Is(err, ErrEvidenceUnavailable) {
		t.Fatalf("provider err=%v", err)
	}
}

type staticProvider struct {
	snapshot Snapshot
	err      error
}

func (p staticProvider) Snapshot(context.Context, Request) (Snapshot, error) {
	return p.snapshot, p.err
}

func contains(values []string, expected string) bool {
	for _, value := range values {
		if value == expected {
			return true
		}
	}
	return false
}
