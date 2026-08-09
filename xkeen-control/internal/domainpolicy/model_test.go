package domainpolicy

import "testing"

func TestCanonicalizeRuleConvertsIDNAAndNormalizesSuffix(t *testing.T) {
	domain, err := CanonicalizeRule(Rule{Kind: "domain", Value: "ПРИМЕР.РФ", Effect: "direct", Label: "Пример", Enabled: true})
	if err != nil || domain.Value != "xn--e1afmkfd.xn--p1ai" || domain.Source != "manual" {
		t.Fatalf("domain=%+v err=%v", domain, err)
	}
	suffix, err := CanonicalizeRule(Rule{Kind: "suffix", Value: ".RU", Effect: "direct", Enabled: true})
	if err != nil || suffix.Value != "ru" || suffix.Source != "zone" {
		t.Fatalf("suffix=%+v err=%v", suffix, err)
	}
}

func TestCanonicalizeRuleAllowsOnlyStructuredMatchers(t *testing.T) {
	valid := []Rule{
		{Kind: "domain", Value: "okko.sport", Effect: "direct", Enabled: true},
		{Kind: "suffix", Value: "xn--p1ai", Effect: "direct", Enabled: true},
		{Kind: "geosite", Value: "category-gov-ru", Effect: "direct", Enabled: true},
	}
	for _, rule := range valid {
		if _, err := CanonicalizeRule(rule); err != nil {
			t.Fatalf("valid rule rejected: %+v: %v", rule, err)
		}
	}
	invalid := []Rule{
		{Kind: "domain", Value: "*.example.com", Effect: "direct"},
		{Kind: "domain", Value: "192.0.2.1", Effect: "direct"},
		{Kind: "suffix", Value: "info", Effect: "direct"},
		{Kind: "geosite", Value: "private", Effect: "direct"},
		{Kind: "regexp", Value: ".*", Effect: "direct"},
		{Kind: "domain", Value: "example.com", Effect: "block"},
	}
	for _, rule := range invalid {
		if _, err := CanonicalizeRule(rule); err == nil {
			t.Fatalf("invalid rule accepted: %+v", rule)
		}
	}
}

func TestValidatePolicyRejectsDuplicateAndConflictingRules(t *testing.T) {
	for name, rules := range map[string][]Rule{
		"duplicate id": {
			{ID: "same", Kind: "domain", Value: "okko.sport", Effect: "direct", Enabled: true},
			{ID: "same", Kind: "domain", Value: "example.com", Effect: "vpn", Enabled: true},
		},
		"conflicting matcher": {
			{ID: "a", Kind: "domain", Value: "okko.sport", Effect: "direct", Enabled: true},
			{ID: "b", Kind: "domain", Value: "okko.sport", Effect: "vpn", Enabled: true},
		},
	} {
		t.Run(name, func(t *testing.T) {
			if ValidatePolicy(Policy{SchemaVersion: 1, Rules: rules}) == nil {
				t.Fatal("invalid policy accepted")
			}
		})
	}
}

func TestNewStatusUsesNonNilCollections(t *testing.T) {
	status := NewStatus(1, nil, nil, nil)
	if status.Rules == nil || status.Presets == nil || status.Warnings == nil {
		t.Fatalf("status=%#v", status)
	}
}

func TestCanonicalizeCIDRRequiresCanonicalPrefix(t *testing.T) {
	rule, err := CanonicalizeRule(Rule{Kind: "cidr", Value: "192.0.2.0/24", Effect: "direct", Label: "Test network", Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	if rule.Value != "192.0.2.0/24" || rule.Source != "manual" {
		t.Fatalf("rule=%+v", rule)
	}
	for _, value := range []string{"192.0.2.7/24", "0.0.0.0/0", "not-a-prefix"} {
		if _, err := CanonicalizeRule(Rule{Kind: "cidr", Value: value, Effect: "direct"}); err == nil {
			t.Fatalf("accepted %q", value)
		}
	}
}
