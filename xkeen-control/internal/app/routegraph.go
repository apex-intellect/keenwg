package app

import (
	"context"
	"net"
	"net/netip"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/adapter"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/catalog"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/domainpolicy"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/routegraph"
)

type routeDomainStatus interface {
	Status(context.Context) (domainpolicy.Status, error)
}

type routeCatalogSnapshot interface {
	Snapshot(context.Context) (catalog.Document, error)
}

type routeAdapterSnapshot interface {
	Snapshot(context.Context) (adapter.RegistrySnapshot, error)
}

type routeResolver interface {
	LookupNetIP(context.Context, string, string) ([]netip.Addr, error)
}

type routeEvidenceProvider struct {
	domains  routeDomainStatus
	catalog  routeCatalogSnapshot
	adapters routeAdapterSnapshot
	resolver routeResolver
	now      func() time.Time
}

func (p *routeEvidenceProvider) Snapshot(ctx context.Context, request routegraph.Request) (routegraph.Snapshot, error) {
	if err := ctx.Err(); err != nil {
		return routegraph.Snapshot{}, err
	}
	now := time.Now().UTC()
	if p.now != nil {
		now = p.now().UTC()
	}
	result := routegraph.Snapshot{
		ObservedAt:  now,
		DNS:         routegraph.DNSObservation{Answers: []string{}, ObservedAt: now},
		DeviceRules: []routegraph.Rule{}, Rules: []routegraph.Rule{},
		Adapters: []routegraph.AdapterObservation{}, Warnings: []string{},
		QUIC: routegraph.QUICObservation{Supported: false, Reason: "quic_not_observed"},
	}
	if request.Domain != "" && p.resolver != nil {
		addresses, err := p.resolver.LookupNetIP(ctx, "ip", request.Domain)
		if err != nil {
			result.DNS.ErrorCode = "dns_unavailable"
		} else {
			for _, address := range addresses {
				if address.IsValid() {
					result.DNS.Answers = append(result.DNS.Answers, address.String())
				}
			}
		}
	}

	if p.domains != nil {
		status, err := p.domains.Status(ctx)
		if err != nil {
			result.Warnings = append(result.Warnings, "domain_policy_unavailable")
		} else {
			for _, rule := range status.Rules {
				if !rule.Enabled {
					continue
				}
				outcome := "direct"
				if rule.Effect == "vpn" {
					outcome = "group:vpn"
				}
				routeRule := routegraph.Rule{ID: rule.ID, Value: rule.Value, Outcome: outcome}
				switch rule.Kind {
				case "domain":
					routeRule.Kind = routegraph.RuleDomain
				case "suffix":
					routeRule.Kind = routegraph.RuleSuffix
				case "geosite":
					routeRule.Kind = routegraph.RuleGeoSite
					result.Warnings = append(result.Warnings, "geosite_membership_unavailable", "geo_data_age_unknown")
				default:
					continue
				}
				result.Rules = append(result.Rules, routeRule)
			}
		}
	}

	if p.catalog != nil {
		document, err := p.catalog.Snapshot(ctx)
		if err != nil {
			result.Warnings = append(result.Warnings, "catalog_evidence_unavailable")
		} else {
			for _, node := range document.Nodes {
				if !node.Active {
					continue
				}
				result.Selector = &routegraph.SelectorObservation{GroupID: "vpn", NodeID: node.ID, Observed: true, ObservedAt: now}
				result.Rules = append(result.Rules, routegraph.Rule{ID: "default-active-route", Kind: routegraph.RuleDefault, Outcome: "group:vpn"})
				break
			}
		}
	}

	if p.adapters != nil {
		snapshot, err := p.adapters.Snapshot(ctx)
		if err != nil {
			result.Warnings = append(result.Warnings, "adapter_evidence_unavailable")
		} else {
			for _, item := range snapshot.Adapters {
				result.Adapters = append(result.Adapters, routegraph.AdapterObservation{ID: item.ID, Available: item.Discovery.Available, Reason: item.Discovery.Reason})
			}
		}
	}
	return result, nil
}

var _ routeResolver = net.DefaultResolver
