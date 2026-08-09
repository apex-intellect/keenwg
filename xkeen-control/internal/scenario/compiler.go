package scenario

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/netip"
	"regexp"
	"sort"
	"strings"

	"github.com/goldb/keenwg/xkeen-control/internal/domainpolicy"
	"golang.org/x/net/idna"
)

var (
	ErrInvalidPreset    = errors.New("invalid_scenario_preset")
	ErrStaleState       = errors.New("stale_scenario_state")
	ErrInvalidSignature = errors.New("invalid_preset_signature")
)

const maxConditions = 256

type Conditions struct {
	DeviceIDs []string `json:"device_ids"`
	Services  []string `json:"services"`
	Domains   []string `json:"domains"`
	Suffixes  []string `json:"suffixes"`
	GeoSites  []string `json:"geosites"`
	CIDRs     []string `json:"cidrs"`
}

type Outcome struct {
	Mode    string `json:"mode"`
	GroupID string `json:"group_id,omitempty"`
}

type Preset struct {
	ID         string     `json:"id"`
	Label      string     `json:"label"`
	Optional   bool       `json:"optional"`
	Conditions Conditions `json:"conditions"`
	Outcome    Outcome    `json:"outcome"`
}

type Bundle struct {
	SchemaVersion int      `json:"schema_version"`
	Revision      uint64   `json:"revision"`
	Presets       []Preset `json:"presets"`
	Signature     string   `json:"signature,omitempty"`
}

type Modules struct {
	Devices  bool `json:"devices"`
	Services bool `json:"services"`
	Domains  bool `json:"domains"`
	IP       bool `json:"ip"`
}

type Step struct {
	Module    string  `json:"module"`
	MatchKind string  `json:"match_kind"`
	Value     string  `json:"value"`
	Outcome   Outcome `json:"outcome"`
}

type Plan struct {
	SchemaVersion int      `json:"schema_version"`
	PresetID      string   `json:"preset_id"`
	StateVersion  uint64   `json:"state_version"`
	Outcome       Outcome  `json:"outcome"`
	Steps         []Step   `json:"steps"`
	Skipped       []string `json:"skipped_modules"`
}

func Compile(input Preset, currentVersion, reviewedVersion uint64, modules Modules) (Plan, error) {
	if currentVersion == 0 || reviewedVersion != currentVersion {
		return Plan{}, ErrStaleState
	}
	preset, err := canonicalPreset(input)
	if err != nil {
		return Plan{}, err
	}
	plan := Plan{SchemaVersion: 1, PresetID: preset.ID, StateVersion: currentVersion, Outcome: preset.Outcome, Steps: []Step{}, Skipped: []string{}}
	appendModule := func(module, kind string, enabled bool, values []string) {
		if len(values) == 0 {
			return
		}
		if !enabled {
			if len(plan.Skipped) == 0 || plan.Skipped[len(plan.Skipped)-1] != module {
				plan.Skipped = append(plan.Skipped, module)
			}
			return
		}
		for _, value := range values {
			plan.Steps = append(plan.Steps, Step{Module: module, MatchKind: kind, Value: value, Outcome: preset.Outcome})
		}
	}
	appendModule("devices", "device", modules.Devices, preset.Conditions.DeviceIDs)
	appendModule("services", "service", modules.Services, preset.Conditions.Services)
	appendModule("domains", "domain", modules.Domains, preset.Conditions.Domains)
	appendModule("domains", "suffix", modules.Domains, preset.Conditions.Suffixes)
	appendModule("domains", "geosite", modules.Domains, preset.Conditions.GeoSites)
	appendModule("ip", "cidr", modules.IP, preset.Conditions.CIDRs)
	return plan, nil
}

func SignBundle(private ed25519.PrivateKey, bundle Bundle) (string, error) {
	if len(private) != ed25519.PrivateKeySize {
		return "", ErrInvalidSignature
	}
	body, err := canonicalBundle(bundle)
	if err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(ed25519.Sign(private, body)), nil
}

func VerifyBundle(public ed25519.PublicKey, bundle Bundle) error {
	if len(public) != ed25519.PublicKeySize {
		return ErrInvalidSignature
	}
	signature, err := base64.RawURLEncoding.DecodeString(bundle.Signature)
	if err != nil || len(signature) != ed25519.SignatureSize {
		return ErrInvalidSignature
	}
	body, err := canonicalBundle(bundle)
	if err != nil {
		return err
	}
	if !ed25519.Verify(public, body, signature) {
		return ErrInvalidSignature
	}
	return nil
}

func canonicalBundle(bundle Bundle) ([]byte, error) {
	if bundle.SchemaVersion != 1 || bundle.Revision == 0 || len(bundle.Presets) == 0 || len(bundle.Presets) > 128 {
		return nil, ErrInvalidPreset
	}
	copyBundle := bundle
	copyBundle.Signature = ""
	copyBundle.Presets = append([]Preset(nil), bundle.Presets...)
	seen := map[string]struct{}{}
	for index, preset := range copyBundle.Presets {
		canonical, err := canonicalPreset(preset)
		if err != nil {
			return nil, err
		}
		if _, exists := seen[canonical.ID]; exists {
			return nil, ErrInvalidPreset
		}
		seen[canonical.ID] = struct{}{}
		copyBundle.Presets[index] = canonical
	}
	sort.Slice(copyBundle.Presets, func(i, j int) bool { return copyBundle.Presets[i].ID < copyBundle.Presets[j].ID })
	body, err := json.Marshal(copyBundle)
	if err != nil {
		return nil, ErrInvalidPreset
	}
	return body, nil
}

var identifier = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,63}$`)

func canonicalPreset(input Preset) (Preset, error) {
	input.ID = strings.ToLower(strings.TrimSpace(input.ID))
	input.Label = strings.TrimSpace(input.Label)
	input.Outcome.Mode = strings.ToLower(strings.TrimSpace(input.Outcome.Mode))
	input.Outcome.GroupID = strings.ToLower(strings.TrimSpace(input.Outcome.GroupID))
	count := len(input.Conditions.DeviceIDs) + len(input.Conditions.Services) + len(input.Conditions.Domains) + len(input.Conditions.Suffixes) + len(input.Conditions.GeoSites) + len(input.Conditions.CIDRs)
	if !identifier.MatchString(input.ID) || input.Label == "" || len([]rune(input.Label)) > 128 || count == 0 || count > maxConditions {
		return Preset{}, ErrInvalidPreset
	}
	if input.Outcome.Mode == "direct" {
		if input.Outcome.GroupID != "" {
			return Preset{}, ErrInvalidPreset
		}
	} else if input.Outcome.Mode != "group" || !identifier.MatchString(input.Outcome.GroupID) {
		return Preset{}, ErrInvalidPreset
	}
	var err error
	input.Conditions.DeviceIDs, err = canonicalIdentifiers(input.Conditions.DeviceIDs)
	if err != nil {
		return Preset{}, err
	}
	input.Conditions.Services, err = canonicalIdentifiers(input.Conditions.Services)
	if err != nil {
		return Preset{}, err
	}
	input.Conditions.Domains, err = canonicalDomains(input.Conditions.Domains)
	if err != nil {
		return Preset{}, err
	}
	input.Conditions.Suffixes, err = canonicalDomainMatchers(input.Conditions.Suffixes, "suffix")
	if err != nil {
		return Preset{}, err
	}
	input.Conditions.GeoSites, err = canonicalDomainMatchers(input.Conditions.GeoSites, "geosite")
	if err != nil {
		return Preset{}, err
	}
	input.Conditions.CIDRs, err = canonicalCIDRs(input.Conditions.CIDRs)
	if err != nil {
		return Preset{}, err
	}
	return input, nil
}

func canonicalDomainMatchers(values []string, kind string) ([]string, error) {
	result := make([]string, 0, len(values))
	seen := map[string]struct{}{}
	for _, raw := range values {
		rule, err := domainpolicy.CanonicalizeRule(domainpolicy.Rule{Kind: kind, Value: raw, Effect: "direct"})
		if err != nil {
			return nil, ErrInvalidPreset
		}
		if _, ok := seen[rule.Value]; ok {
			return nil, ErrInvalidPreset
		}
		seen[rule.Value] = struct{}{}
		result = append(result, rule.Value)
	}
	return result, nil
}

func canonicalIdentifiers(values []string) ([]string, error) {
	result := make([]string, 0, len(values))
	seen := map[string]struct{}{}
	for _, raw := range values {
		value := strings.ToLower(strings.TrimSpace(raw))
		if !identifier.MatchString(value) {
			return nil, ErrInvalidPreset
		}
		if _, ok := seen[value]; ok {
			return nil, ErrInvalidPreset
		}
		seen[value] = struct{}{}
		result = append(result, value)
	}
	return result, nil
}

func canonicalDomains(values []string) ([]string, error) {
	result := make([]string, 0, len(values))
	seen := map[string]struct{}{}
	for _, raw := range values {
		value, err := idna.Lookup.ToASCII(strings.Trim(strings.ToLower(strings.TrimSpace(raw)), "."))
		if err != nil || !strings.Contains(value, ".") || len(value) > 253 {
			return nil, ErrInvalidPreset
		}
		value = strings.ToLower(value)
		if _, ok := seen[value]; ok {
			return nil, ErrInvalidPreset
		}
		seen[value] = struct{}{}
		result = append(result, value)
	}
	return result, nil
}

func canonicalCIDRs(values []string) ([]string, error) {
	result := make([]string, 0, len(values))
	seen := map[string]struct{}{}
	for _, raw := range values {
		prefix, err := netip.ParsePrefix(strings.TrimSpace(raw))
		if err != nil {
			return nil, ErrInvalidPreset
		}
		value := prefix.Masked().String()
		if _, ok := seen[value]; ok {
			return nil, ErrInvalidPreset
		}
		seen[value] = struct{}{}
		result = append(result, value)
	}
	return result, nil
}
