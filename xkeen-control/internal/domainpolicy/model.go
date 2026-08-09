package domainpolicy

import (
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"net/netip"
	"strings"
	"unicode"
	"unicode/utf8"

	"golang.org/x/net/idna"
)

var ErrInvalidPolicy = errors.New("invalid_domain_policy")

var allowedZones = map[string]struct{}{
	"ru": {}, "su": {}, "xn--p1ai": {}, "xn--p1acf": {}, "xn--80asehdb": {}, "xn--c1avg": {},
	"xn--80aswg": {}, "xn--80adxhks": {}, "moscow": {}, "xn--d1acj3b": {},
}

type Rule struct {
	ID        string `json:"id"`
	Kind      string `json:"kind"`
	Value     string `json:"value"`
	Effect    string `json:"effect"`
	Label     string `json:"label"`
	Enabled   bool   `json:"enabled"`
	Source    string `json:"source"`
	Protected bool   `json:"protected"`
}

type Preset struct {
	ID        string `json:"id"`
	Label     string `json:"label"`
	Matcher   string `json:"matcher"`
	Available bool   `json:"available"`
	Enabled   bool   `json:"enabled"`
}

type Policy struct {
	SchemaVersion int    `json:"schema_version"`
	Rules         []Rule `json:"rules"`
}

type Status struct {
	SchemaVersion int      `json:"schema_version"`
	StateVersion  uint64   `json:"state_version"`
	Rules         []Rule   `json:"rules"`
	Presets       []Preset `json:"presets"`
	Warnings      []string `json:"warnings"`
}

type Mutation struct {
	StateVersion   uint64 `json:"state_version"`
	IdempotencyKey string `json:"idempotency_key"`
	Action         string `json:"action"`
	Rule           *Rule  `json:"rule,omitempty"`
	RuleID         string `json:"rule_id,omitempty"`
}

type Result struct {
	Result string `json:"result"`
	Status Status `json:"status"`
}

type ReplaceRequest struct {
	StateVersion   uint64 `json:"state_version"`
	IdempotencyKey string `json:"idempotency_key"`
	Rules          []Rule `json:"rules"`
}

func CanonicalizeRule(input Rule) (Rule, error) {
	input.Kind = strings.ToLower(strings.TrimSpace(input.Kind))
	input.Effect = strings.ToLower(strings.TrimSpace(input.Effect))
	input.Label = strings.TrimSpace(input.Label)
	if input.Effect != "direct" && input.Effect != "vpn" {
		return Rule{}, ErrInvalidPolicy
	}
	if utf8.RuneCountInString(input.Label) > 80 || strings.IndexFunc(input.Label, unicode.IsControl) >= 0 {
		return Rule{}, ErrInvalidPolicy
	}
	raw := strings.ToLower(strings.TrimSpace(input.Value))
	unsafe := strings.ContainsAny(raw, "*\\ ") || (input.Kind != "cidr" && strings.Contains(raw, "/"))
	if raw == "" || unsafe {
		return Rule{}, ErrInvalidPolicy
	}
	switch input.Kind {
	case "domain":
		raw = strings.TrimSuffix(raw, ".")
		ascii, err := idna.Lookup.ToASCII(raw)
		if err != nil || !validDomain(ascii) {
			return Rule{}, ErrInvalidPolicy
		}
		input.Value = strings.ToLower(ascii)
		if input.Source == "" {
			input.Source = "manual"
		}
	case "suffix":
		raw = strings.TrimPrefix(strings.TrimSuffix(raw, "."), ".")
		ascii, err := idna.Lookup.ToASCII(raw)
		if err != nil {
			return Rule{}, ErrInvalidPolicy
		}
		input.Value = strings.ToLower(ascii)
		if _, ok := allowedZones[input.Value]; !ok {
			return Rule{}, ErrInvalidPolicy
		}
		if input.Source == "" {
			input.Source = "zone"
		}
	case "geosite":
		if raw != "category-gov-ru" {
			return Rule{}, ErrInvalidPolicy
		}
		input.Value = raw
		if input.Source == "" {
			input.Source = "geosite"
		}
	case "cidr":
		prefix, err := netip.ParsePrefix(raw)
		if err != nil || prefix.Masked().String() != raw || prefix.Addr().IsUnspecified() || prefix.Addr().IsMulticast() {
			return Rule{}, ErrInvalidPolicy
		}
		input.Value = prefix.String()
		if input.Source == "" {
			input.Source = "manual"
		}
	default:
		return Rule{}, ErrInvalidPolicy
	}
	if input.Source != "manual" && input.Source != "zone" && input.Source != "geosite" && input.Source != "system" {
		return Rule{}, ErrInvalidPolicy
	}
	if input.ID == "" {
		input.ID = ruleID(input.Kind, input.Value, input.Effect)
	}
	if !validID(input.ID) {
		return Rule{}, ErrInvalidPolicy
	}
	return input, nil
}

func ValidatePolicy(policy Policy) error {
	if policy.SchemaVersion != 1 {
		return ErrInvalidPolicy
	}
	ids := make(map[string]struct{}, len(policy.Rules))
	matchers := make(map[string]string, len(policy.Rules))
	for _, rule := range policy.Rules {
		canonical, err := CanonicalizeRule(rule)
		if err != nil || canonical != rule {
			return ErrInvalidPolicy
		}
		if _, exists := ids[rule.ID]; exists {
			return ErrInvalidPolicy
		}
		ids[rule.ID] = struct{}{}
		key := rule.Kind + "\x00" + rule.Value
		if effect, exists := matchers[key]; exists && effect != rule.Effect {
			return ErrInvalidPolicy
		}
		if _, exists := matchers[key]; exists {
			return ErrInvalidPolicy
		}
		matchers[key] = rule.Effect
	}
	return nil
}

func NewStatus(version uint64, rules []Rule, presets []Preset, warnings []string) Status {
	return Status{
		SchemaVersion: 1,
		StateVersion:  version,
		Rules:         append([]Rule{}, rules...),
		Presets:       append([]Preset{}, presets...),
		Warnings:      append([]string{}, warnings...),
	}
}

func PolicyVersion(body []byte) uint64 {
	hash := sha256.Sum256(body)
	return binary.BigEndian.Uint64(hash[:8])
}

func ruleID(kind, value, effect string) string {
	hash := sha256.Sum256([]byte(kind + "\x00" + value + "\x00" + effect))
	const alphabet = "0123456789abcdef"
	out := make([]byte, 16)
	for i, value := range hash[:8] {
		out[i*2] = alphabet[value>>4]
		out[i*2+1] = alphabet[value&15]
	}
	return string(out)
}

func validID(value string) bool {
	if len(value) < 1 || len(value) > 64 {
		return false
	}
	for _, char := range value {
		if !(char >= 'a' && char <= 'z') && !(char >= '0' && char <= '9') && char != '-' && char != '_' {
			return false
		}
	}
	return true
}

func validDomain(value string) bool {
	if len(value) > 253 || !strings.Contains(value, ".") {
		return false
	}
	if address, err := netip.ParseAddr(value); err == nil && address.IsValid() {
		return false
	}
	for _, label := range strings.Split(value, ".") {
		if len(label) < 1 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
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
