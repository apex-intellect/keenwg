package subscription

import (
	"encoding/base64"
	"errors"
	"net"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/model"
)

var (
	ErrInvalidSubscription = errors.New("invalid_subscription")
	ErrNoSupportedNodes    = errors.New("no_supported_nodes")
	ErrTooManyNodes        = errors.New("too_many_nodes")
)

var (
	uuidPattern = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	hexPattern  = regexp.MustCompile(`(?i)^[0-9a-f]+$`)
)

type Result struct {
	Nodes    []model.Node
	Rejected map[string]int
}

func Parse(payload []byte, maxNodes int) (Result, error) {
	result := Result{Rejected: make(map[string]int)}
	if maxNodes < 1 {
		return result, ErrTooManyNodes
	}
	decoded, err := decodeRawOrBase64(payload)
	if err != nil {
		return result, ErrInvalidSubscription
	}
	lines := subscriptionNodeLines(nonEmptyLines(decoded))
	if len(lines) > maxNodes {
		return result, ErrTooManyNodes
	}
	result.Nodes = make([]model.Node, 0, len(lines))
	seen := make(map[string]struct{}, len(lines))
	for _, line := range lines {
		node, rejection := parseLine(line)
		if rejection != "" {
			result.Rejected[rejection]++
			continue
		}
		if _, exists := seen[node.CanonicalURI]; exists {
			result.Rejected["duplicate_node"]++
			continue
		}
		seen[node.CanonicalURI] = struct{}{}
		result.Nodes = append(result.Nodes, node)
	}
	if len(result.Nodes) == 0 {
		return result, ErrNoSupportedNodes
	}
	return result, nil
}

func subscriptionNodeLines(lines []string) []string {
	result := make([]string, 0, len(lines))
	for _, line := range lines {
		lower := strings.ToLower(line)
		if strings.HasPrefix(lower, "#profile-title:") || strings.HasPrefix(lower, "#subscription-userinfo:") {
			continue
		}
		result = append(result, line)
	}
	return result
}

func decodeRawOrBase64(payload []byte) ([]byte, error) {
	trimmed := strings.TrimSpace(string(payload))
	if trimmed == "" {
		return nil, ErrInvalidSubscription
	}
	if strings.Contains(trimmed, "://") {
		return []byte(trimmed), nil
	}
	compact := strings.Map(func(r rune) rune {
		if unicode.IsSpace(r) {
			return -1
		}
		return r
	}, trimmed)
	for _, encoding := range []*base64.Encoding{
		base64.StdEncoding,
		base64.RawStdEncoding,
		base64.URLEncoding,
		base64.RawURLEncoding,
	} {
		decoded, err := encoding.DecodeString(compact)
		if err == nil && strings.Contains(string(decoded), "://") {
			return decoded, nil
		}
	}
	return nil, ErrInvalidSubscription
}

func nonEmptyLines(payload []byte) []string {
	rawLines := strings.Split(strings.ReplaceAll(string(payload), "\r\n", "\n"), "\n")
	lines := make([]string, 0, len(rawLines))
	for _, line := range rawLines {
		if line = strings.TrimSpace(line); line != "" {
			lines = append(lines, line)
		}
	}
	return lines
}

func parseLine(line string) (model.Node, string) {
	u, err := url.Parse(line)
	if err != nil {
		return model.Node{}, "invalid_uri"
	}
	if strings.ToLower(u.Scheme) != "vless" {
		return model.Node{}, "unsupported_scheme"
	}
	if u.User == nil {
		return model.Node{}, "invalid_uuid"
	}
	uuid := strings.ToLower(u.User.Username())
	if _, hasPassword := u.User.Password(); hasPassword || !uuidPattern.MatchString(uuid) {
		return model.Node{}, "invalid_uuid"
	}
	host := strings.ToLower(strings.TrimSuffix(u.Hostname(), "."))
	if !validHostname(host) {
		return model.Node{}, "invalid_host"
	}
	port, err := strconv.Atoi(u.Port())
	if err != nil || port < 1 || port > 65535 {
		return model.Node{}, "invalid_port"
	}
	q := u.Query()
	transport := strings.ToLower(q.Get("type"))
	security := strings.ToLower(q.Get("security"))
	flow := strings.ToLower(q.Get("flow"))
	fingerprint := strings.ToLower(q.Get("fp"))
	if transport != "tcp" {
		return model.Node{}, "unsupported_transport"
	}
	if security != "reality" {
		return model.Node{}, "unsupported_security"
	}
	if flow != "xtls-rprx-vision" {
		return model.Node{}, "unsupported_flow"
	}
	publicKey := q.Get("pbk")
	shortID := q.Get("sid")
	sni := strings.ToLower(strings.TrimSuffix(q.Get("sni"), "."))
	spiderX := q.Get("spx")
	if publicKey == "" || len(publicKey) > 256 {
		return model.Node{}, "invalid_reality_key"
	}
	if len(shortID) < 2 || len(shortID) > 32 || len(shortID)%2 != 0 || !hexPattern.MatchString(shortID) {
		return model.Node{}, "invalid_short_id"
	}
	if !validHostname(sni) {
		return model.Node{}, "invalid_sni"
	}
	if spiderX == "" {
		spiderX = "/"
	}
	if len(spiderX) > 256 || !strings.HasPrefix(spiderX, "/") {
		return model.Node{}, "invalid_spider_x"
	}
	if fingerprint == "" || len(fingerprint) > 64 {
		return model.Node{}, "invalid_fingerprint"
	}
	if encryption := strings.ToLower(q.Get("encryption")); encryption != "" && encryption != "none" {
		return model.Node{}, "unsupported_encryption"
	}

	displayName := strings.TrimSpace(u.Fragment)
	if displayName == "" {
		displayName = host + ":" + strconv.Itoa(port)
	}
	q.Set("type", transport)
	q.Set("security", security)
	q.Set("flow", flow)
	q.Set("fp", fingerprint)
	q.Set("sni", sni)
	q.Set("spx", spiderX)
	canonical := (&url.URL{
		Scheme:   "vless",
		User:     url.User(uuid),
		Host:     net.JoinHostPort(host, strconv.Itoa(port)),
		RawQuery: q.Encode(),
		Fragment: displayName,
	}).String()
	country, flag := countryFromDisplayName(displayName)
	return model.Node{
		CanonicalURI: canonical,
		DisplayName:  displayName,
		Country:      country,
		Flag:         flag,
		Host:         host,
		Port:         port,
		UUID:         uuid,
		PublicKey:    publicKey,
		ShortID:      strings.ToLower(shortID),
		SNI:          sni,
		SpiderX:      spiderX,
		Fingerprint:  fingerprint,
		Transport:    transport,
		Security:     security,
		Flow:         flow,
		Warnings:     []string{},
	}, ""
}

func validHostname(host string) bool {
	if host == "" || len(host) > 253 || strings.ContainsAny(host, " /\\@") {
		return false
	}
	if ip := net.ParseIP(host); ip != nil {
		return true
	}
	labels := strings.Split(host, ".")
	for _, label := range labels {
		if label == "" || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, r := range label {
			if (r < 'a' || r > 'z') && (r < '0' || r > '9') && r != '-' {
				return false
			}
		}
	}
	return true
}

func countryFromDisplayName(name string) (string, string) {
	lower := strings.ToLower(name)
	for _, item := range []struct {
		needle  string
		country string
		flag    string
	}{
		{"нидерланд", "Нидерланды", "🇳🇱"},
		{"германи", "Германия", "🇩🇪"},
		{"финлянд", "Финляндия", "🇫🇮"},
		{"франци", "Франция", "🇫🇷"},
		{"швеци", "Швеция", "🇸🇪"},
		{"польш", "Польша", "🇵🇱"},
		{"сша", "США", "🇺🇸"},
		{"united states", "США", "🇺🇸"},
		{"netherlands", "Нидерланды", "🇳🇱"},
		{"germany", "Германия", "🇩🇪"},
	} {
		if strings.Contains(lower, item.needle) {
			return item.country, item.flag
		}
	}
	return "", ""
}
