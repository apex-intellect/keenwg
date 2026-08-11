package app

import (
	"context"
	"errors"
	"net/netip"
	"testing"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/adapter"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/catalog"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/domainpolicy"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/routegraph"
)

func TestRouteEvidenceProviderBuildsPartialSnapshotWithoutSecrets(t *testing.T) {
	now := time.Date(2026, 8, 9, 4, 0, 0, 0, time.UTC)
	provider := &routeEvidenceProvider{
		domains: fakeRouteDomains{status: domainpolicy.NewStatus(7, []domainpolicy.Rule{
			{ID: "domain-direct", Kind: "domain", Value: "example.com", Effect: "direct", Enabled: true},
			{ID: "geo-direct", Kind: "geosite", Value: "category-gov-ru", Effect: "direct", Enabled: true},
		}, nil, nil)},
		catalog: fakeRouteCatalog{document: catalog.Document{Nodes: []catalog.Node{
			{ID: "node-active", GroupID: "primary", Active: true},
		}}},
		adapters: fakeRouteAdapters{snapshot: adapter.RegistrySnapshot{Adapters: []adapter.AdapterState{
			{ID: "xkeen", Discovery: adapter.Discovery{Available: true}},
			{ID: "singbox", Discovery: adapter.Discovery{Available: false, Reason: "adapter_unavailable"}},
		}}},
		resolver: fakeRouteResolver{addresses: []netip.Addr{netip.MustParseAddr("192.0.2.10")}},
		now:      func() time.Time { return now },
	}

	snapshot, err := provider.Snapshot(context.Background(), routegraph.Request{SchemaVersion: 1, Domain: "example.com"})
	if err != nil {
		t.Fatal(err)
	}
	if len(snapshot.DNS.Answers) != 1 || snapshot.DNS.Answers[0] != "192.0.2.10" {
		t.Fatalf("dns=%+v", snapshot.DNS)
	}
	if len(snapshot.Rules) != 3 || snapshot.Rules[0].Outcome != "direct" || snapshot.Rules[2].Kind != routegraph.RuleDefault {
		t.Fatalf("rules=%+v", snapshot.Rules)
	}
	if snapshot.Selector == nil || snapshot.Selector.NodeID != "node-active" || !containsString(snapshot.Warnings, "geosite_membership_unavailable") {
		t.Fatalf("snapshot=%+v", snapshot)
	}
	if len(snapshot.Adapters) != 2 || snapshot.Adapters[1].Reason != "adapter_unavailable" {
		t.Fatalf("adapters=%+v", snapshot.Adapters)
	}
}

func TestRouteEvidenceProviderIsolatesDNSAndOptionalModuleFailure(t *testing.T) {
	provider := &routeEvidenceProvider{
		domains:  fakeRouteDomains{err: errors.New("domain offline")},
		catalog:  fakeRouteCatalog{document: catalog.Document{}},
		adapters: fakeRouteAdapters{err: errors.New("adapter offline")},
		resolver: fakeRouteResolver{err: errors.New("dns offline")},
		now:      time.Now,
	}
	snapshot, err := provider.Snapshot(context.Background(), routegraph.Request{SchemaVersion: 1, Domain: "example.com"})
	if err != nil {
		t.Fatal(err)
	}
	for _, warning := range []string{"domain_policy_unavailable", "adapter_evidence_unavailable"} {
		if !containsString(snapshot.Warnings, warning) {
			t.Fatalf("warnings=%v", snapshot.Warnings)
		}
	}
	if snapshot.DNS.ErrorCode != "dns_unavailable" {
		t.Fatalf("dns=%+v", snapshot.DNS)
	}
}

type fakeRouteDomains struct {
	status domainpolicy.Status
	err    error
}

func (f fakeRouteDomains) Status(context.Context) (domainpolicy.Status, error) {
	return f.status, f.err
}

type fakeRouteCatalog struct {
	document catalog.Document
	err      error
}

func (f fakeRouteCatalog) Snapshot(context.Context) (catalog.Document, error) {
	return f.document, f.err
}

type fakeRouteAdapters struct {
	snapshot adapter.RegistrySnapshot
	err      error
}

func (f fakeRouteAdapters) Snapshot(context.Context) (adapter.RegistrySnapshot, error) {
	return f.snapshot, f.err
}

type fakeRouteResolver struct {
	addresses []netip.Addr
	err       error
}

func (f fakeRouteResolver) LookupNetIP(context.Context, string, string) ([]netip.Addr, error) {
	return f.addresses, f.err
}

func containsString(values []string, expected string) bool {
	for _, value := range values {
		if value == expected {
			return true
		}
	}
	return false
}
