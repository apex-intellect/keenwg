package domainpolicy

import (
	"bytes"
	"regexp"
	"sort"
	"strings"
)

const (
	beginRoutingMarker         = "// BEGIN KEENWG DOMAIN POLICY"
	endRoutingMarker           = "// END KEENWG DOMAIN POLICY"
	existingRoutingStartMarker = "// 1C ecosystem — direct"
	existingRoutingEndMarker   = "// Direct: Russian IP ranges"
)

type ImportReport struct {
	ImportedRules    int      `json:"imported_rules"`
	RemovedBroadInfo bool     `json:"removed_broad_info"`
	RemovedBroadTV   bool     `json:"removed_broad_tv"`
	Warnings         []string `json:"warnings"`
}

var existingDomainPattern = regexp.MustCompile(`"domain:([^"\\]+)"`)

func ImportExistingRouting(current []byte) (Policy, ImportReport, error) {
	text := string(current)
	start := strings.Index(text, existingRoutingStartMarker)
	end := strings.Index(text, existingRoutingEndMarker)
	if start < 0 || end <= start || strings.Contains(text, beginRoutingMarker) || strings.Contains(text, endRoutingMarker) {
		return Policy{}, ImportReport{}, ErrInvalidPolicy
	}
	region := text[start:end]
	report := ImportReport{
		RemovedBroadInfo: strings.Contains(region, `)info$`),
		RemovedBroadTV:   strings.Contains(region, `)tv$`),
		Warnings:         []string{},
	}
	rules := make([]Rule, 0, 32)
	add := func(input Rule) error {
		canonical, err := CanonicalizeRule(input)
		if err != nil {
			return err
		}
		for _, existing := range rules {
			if existing.Kind == canonical.Kind && existing.Value == canonical.Value && existing.Effect == canonical.Effect {
				return nil
			}
		}
		rules = append(rules, canonical)
		return nil
	}
	if err := add(Rule{Kind: "geosite", Value: "category-gov-ru", Effect: "direct", Label: "Государственные сайты РФ", Enabled: true}); err != nil {
		return Policy{}, report, err
	}
	for _, match := range existingDomainPattern.FindAllStringSubmatch(region, -1) {
		label := serviceLabel(match[1])
		if err := add(Rule{Kind: "domain", Value: match[1], Effect: "direct", Label: label, Enabled: true}); err != nil {
			return Policy{}, report, err
		}
	}
	zones := make([]string, 0, len(allowedZones))
	for zone := range allowedZones {
		zones = append(zones, zone)
	}
	sort.Strings(zones)
	for _, zone := range zones {
		if strings.Contains(region, ")"+zone+"$") {
			if err := add(Rule{Kind: "suffix", Value: zone, Effect: "direct", Label: zoneLabel(zone), Enabled: true}); err != nil {
				return Policy{}, report, err
			}
		}
	}
	policy := Policy{SchemaVersion: 1, Rules: rules}
	if err := ValidatePolicy(policy); err != nil {
		return Policy{}, report, err
	}
	report.ImportedRules = len(rules)
	return policy, report, nil
}

func RenderRouting(current []byte, policy Policy) ([]byte, error) {
	if ValidatePolicy(policy) != nil {
		return nil, ErrInvalidPolicy
	}
	beginCount := bytes.Count(current, []byte(beginRoutingMarker))
	endCount := bytes.Count(current, []byte(endRoutingMarker))
	if beginCount != endCount || beginCount > 1 {
		return nil, ErrInvalidPolicy
	}
	block := renderManagedBlock(policy)
	if beginCount == 1 {
		before, after, err := splitManaged(current)
		if err != nil {
			return nil, err
		}
		result := append(append(append([]byte{}, before...), block...), after...)
		return result, nil
	}
	start := lineStart(current, bytes.Index(current, []byte(existingRoutingStartMarker)))
	endIndex := bytes.Index(current, []byte(existingRoutingEndMarker))
	end := lineStart(current, endIndex)
	if start < 0 || endIndex < 0 || end <= start {
		return nil, ErrInvalidPolicy
	}
	result := make([]byte, 0, len(current)+len(block))
	result = append(result, current[:start]...)
	result = append(result, block...)
	result = append(result, current[end:]...)
	return result, nil
}

func splitManaged(body []byte) ([]byte, []byte, error) {
	if bytes.Count(body, []byte(beginRoutingMarker)) != 1 || bytes.Count(body, []byte(endRoutingMarker)) != 1 {
		return nil, nil, ErrInvalidPolicy
	}
	beginIndex := bytes.Index(body, []byte(beginRoutingMarker))
	endIndex := bytes.Index(body, []byte(endRoutingMarker))
	if beginIndex < 0 || endIndex <= beginIndex {
		return nil, nil, ErrInvalidPolicy
	}
	start := lineStart(body, beginIndex)
	after := lineAfter(body, endIndex+len(endRoutingMarker))
	if start < 0 || after < 0 {
		return nil, nil, ErrInvalidPolicy
	}
	if after < len(body) && body[after] == '\n' {
		after++
	}
	return append([]byte(nil), body[:start]...), append([]byte(nil), body[after:]...), nil
}

func renderManagedBlock(policy Policy) []byte {
	var builder strings.Builder
	builder.WriteString("      " + beginRoutingMarker + "\n")
	for _, effect := range []string{"vpn", "direct"} {
		domains := make([]string, 0, len(policy.Rules))
		prefixes := make([]string, 0, len(policy.Rules))
		for _, rule := range policy.Rules {
			if rule.Enabled && rule.Effect == effect {
				if rule.Kind == "cidr" {
					prefixes = append(prefixes, rule.Value)
				} else {
					domains = append(domains, xrayMatcher(rule))
				}
			}
		}
		if len(domains) == 0 && len(prefixes) == 0 {
			continue
		}
		builder.WriteString("      {\n")
		builder.WriteString("        \"inboundTag\": [\"redirect\", \"tproxy\"],\n")
		writeMatcherArray(&builder, "domain", domains)
		writeMatcherArray(&builder, "ip", prefixes)
		outbound := "direct"
		if effect == "vpn" {
			outbound = "vless-reality"
		}
		builder.WriteString("        \"outboundTag\": \"" + outbound + "\",\n")
		builder.WriteString("        \"type\": \"field\"\n")
		builder.WriteString("      },\n")
	}
	builder.WriteString("      " + endRoutingMarker + "\n\n")
	return []byte(builder.String())
}

func writeMatcherArray(builder *strings.Builder, name string, values []string) {
	if len(values) == 0 {
		return
	}
	builder.WriteString("        \"" + name + "\": [\n")
	for index, value := range values {
		builder.WriteString("          \"")
		builder.WriteString(value)
		if index+1 < len(values) {
			builder.WriteString("\",\n")
		} else {
			builder.WriteString("\"\n")
		}
	}
	builder.WriteString("        ],\n")
}

func xrayMatcher(rule Rule) string {
	switch rule.Kind {
	case "geosite":
		return "ext:geosite_v2fly.dat:" + rule.Value
	case "suffix":
		return "domain:" + rule.Value
	default:
		return "domain:" + rule.Value
	}
}

func lineStart(body []byte, index int) int {
	if index < 0 {
		return -1
	}
	if newline := bytes.LastIndex(body[:index], []byte{'\n'}); newline >= 0 {
		return newline + 1
	}
	return 0
}

func lineAfter(body []byte, index int) int {
	if index < 0 || index > len(body) {
		return -1
	}
	if newline := bytes.Index(body[index:], []byte{'\n'}); newline >= 0 {
		return index + newline + 1
	}
	return len(body)
}

func serviceLabel(domain string) string {
	switch {
	case strings.HasPrefix(domain, "okko."):
		return "Okko"
	case strings.HasPrefix(domain, "1c") || domain == "buh.ru":
		return "1С"
	default:
		return domain
	}
}

func zoneLabel(zone string) string {
	switch zone {
	case "ru":
		return "Зона .ru"
	case "su":
		return "Зона .su"
	case "xn--p1ai":
		return "Зона .рф"
	case "moscow":
		return "Зона .moscow"
	default:
		return "Российская зона " + zone
	}
}
