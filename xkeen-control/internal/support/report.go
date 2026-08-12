package support

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	StatusOK           = "ok"
	StatusFailed       = "failed"
	StatusUnsupported  = "unsupported"
	MaxBundleBytes     = 64 << 10
	MaxReviewTextBytes = 16 << 10
	MaxNotes           = 16
	maxNoteRunes       = 256
)

type Resolver interface {
	LookupIP(context.Context, string) ([]net.IP, error)
}

type Dialer interface {
	Dial(context.Context, string, string) error
}

type Target struct {
	Host      string
	Port      int
	Transport string
}

type Input struct {
	Version      string
	StateVersion uint64
	Active       bool
	NodeCount    int
	Target       *Target
	Notes        []string
}

type Evidence struct {
	Code string `json:"code"`
	At   string `json:"at"`
}

type Check struct {
	Layer       string   `json:"layer"`
	Status      string   `json:"status"`
	DurationMS  int64    `json:"duration_ms"`
	Observation Evidence `json:"observation"`
	Inference   Evidence `json:"inference"`
}

type Summary struct {
	Version      string `json:"version"`
	StateVersion uint64 `json:"state_version"`
	Active       bool   `json:"active"`
	NodeCount    int    `json:"node_count"`
	TargetKind   string `json:"target_kind"`
	Transport    string `json:"transport"`
}

type Report struct {
	SchemaVersion int      `json:"schema_version"`
	GeneratedAt   string   `json:"generated_at"`
	Summary       Summary  `json:"summary"`
	Checks        []Check  `json:"checks"`
	Notes         []string `json:"notes"`
}

type Bundle struct {
	SchemaVersion int    `json:"schema_version"`
	GeneratedAt   string `json:"generated_at"`
	Report        Report `json:"report"`
	ReviewText    string `json:"review_text"`
}

type Service struct {
	resolver Resolver
	dialer   Dialer
	timeout  time.Duration
	now      func() time.Time
}

func New(resolver Resolver, dialer Dialer, timeout time.Duration, now func() time.Time) *Service {
	if timeout <= 0 || timeout > 10*time.Second {
		timeout = 3 * time.Second
	}
	if now == nil {
		now = time.Now
	}
	return &Service{resolver: resolver, dialer: dialer, timeout: timeout, now: now}
}

func NewDefault() *Service {
	return New(defaultResolver{}, defaultDialer{dialer: &net.Dialer{}}, 3*time.Second, time.Now)
}

func (s *Service) Build(ctx context.Context, input Input) Bundle {
	generatedAt := s.now().UTC().Format(time.RFC3339)
	summary := Summary{
		Version: Sanitize(input.Version), StateVersion: input.StateVersion, Active: input.Active,
		NodeCount: boundedCount(input.NodeCount), TargetKind: targetKind(input.Target), Transport: safeTransport(input.Target),
	}
	notes := make([]string, 0, min(len(input.Notes), MaxNotes))
	for _, note := range input.Notes {
		if len(notes) == MaxNotes {
			break
		}
		if clean := Sanitize(note); clean != "" {
			notes = append(notes, clean)
		}
	}
	report := Report{SchemaVersion: 1, GeneratedAt: generatedAt, Summary: summary, Checks: s.check(ctx, input.Target, generatedAt), Notes: notes}
	return Bundle{SchemaVersion: 1, GeneratedAt: generatedAt, Report: report, ReviewText: reviewText(report)}
}

func ValidBundle(bundle Bundle) bool {
	if bundle.SchemaVersion != 1 || bundle.Report.SchemaVersion != 1 || bundle.GeneratedAt == "" || bundle.Report.GeneratedAt != bundle.GeneratedAt {
		return false
	}
	if len(bundle.ReviewText) > MaxReviewTextBytes || !utf8.ValidString(bundle.ReviewText) {
		return false
	}
	body, err := json.Marshal(bundle)
	return err == nil && len(body) <= MaxBundleBytes
}

func (s *Service) check(parent context.Context, target *Target, at string) []Check {
	if target == nil || strings.TrimSpace(target.Host) == "" || target.Port < 1 || target.Port > 65535 || s.resolver == nil || s.dialer == nil {
		return unsupportedChecks(at, "target_unavailable")
	}
	started := time.Now()
	ctx, cancel := context.WithTimeout(parent, s.timeout)
	ips, err := s.resolver.LookupIP(ctx, target.Host)
	cancel()
	dnsDuration := elapsedMS(started)
	if err != nil || len(ips) == 0 {
		checks := unsupportedChecks(at, "dns_unavailable")
		checks[0] = newCheck("dns", StatusFailed, dnsDuration, "lookup_failed", "address_family_unknown", at)
		return checks
	}
	v4, v6 := splitIPs(ips)
	checks := []Check{newCheck("dns", StatusOK, dnsDuration, "addresses_resolved", "name_resolution_available", at)}
	if len(v4) > 0 {
		checks = append(checks, newCheck("ipv4", StatusOK, 0, "ipv4_address_observed", "ipv4_available", at))
	} else {
		checks = append(checks, newCheck("ipv4", StatusUnsupported, 0, "ipv4_address_absent", "ipv4_unconfirmed", at))
	}
	if len(v6) > 0 {
		checks = append(checks, newCheck("ipv6", StatusOK, 0, "ipv6_address_observed", "ipv6_available", at))
	} else {
		checks = append(checks, newCheck("ipv6", StatusUnsupported, 0, "ipv6_address_absent", "ipv6_unconfirmed", at))
	}
	checks = append(checks, s.connectCheck(parent, "tcp", target.Port, v4, v6, at))
	if strings.EqualFold(strings.TrimSpace(target.Transport), "quic") {
		checks = append(checks, s.connectCheck(parent, "quic", target.Port, v4, v6, at))
	} else {
		checks = append(checks, newCheck("quic", StatusUnsupported, 0, "transport_not_quic", "quic_not_applicable", at))
	}
	return checks
}

func (s *Service) connectCheck(parent context.Context, layer string, port int, v4, v6 []net.IP, at string) Check {
	protocol := "tcp"
	if layer == "quic" {
		protocol = "udp"
	}
	type candidate struct {
		network string
		ip      net.IP
	}
	candidates := make([]candidate, 0, 2)
	if len(v4) > 0 {
		candidates = append(candidates, candidate{protocol + "4", v4[0]})
	}
	if len(v6) > 0 {
		candidates = append(candidates, candidate{protocol + "6", v6[0]})
	}
	if len(candidates) == 0 {
		return newCheck(layer, StatusUnsupported, 0, "address_unavailable", layer+"_unconfirmed", at)
	}
	started := time.Now()
	for _, item := range candidates {
		ctx, cancel := context.WithTimeout(parent, s.timeout)
		err := s.dialer.Dial(ctx, item.network, net.JoinHostPort(item.ip.String(), fmt.Sprint(port)))
		cancel()
		if err == nil {
			observation := "connection_opened"
			inference := "tcp_reachable"
			if layer == "quic" {
				observation = "udp_socket_opened"
				inference = "quic_handshake_unverified"
			}
			return newCheck(layer, StatusOK, elapsedMS(started), observation, inference, at)
		}
	}
	return newCheck(layer, StatusFailed, elapsedMS(started), "connection_failed", layer+"_unreachable", at)
}

func unsupportedChecks(at, reason string) []Check {
	layers := []string{"dns", "ipv4", "ipv6", "tcp", "quic"}
	checks := make([]Check, len(layers))
	for index, layer := range layers {
		checks[index] = newCheck(layer, StatusUnsupported, 0, reason, layer+"_unconfirmed", at)
	}
	return checks
}

func newCheck(layer, status string, duration int64, observation, inference, at string) Check {
	return Check{Layer: layer, Status: status, DurationMS: duration, Observation: Evidence{Code: observation, At: at}, Inference: Evidence{Code: inference, At: at}}
}

func splitIPs(values []net.IP) ([]net.IP, []net.IP) {
	v4, v6 := []net.IP{}, []net.IP{}
	for _, value := range values {
		if value == nil {
			continue
		}
		if parsed := value.To4(); parsed != nil {
			v4 = append(v4, append(net.IP(nil), parsed...))
		} else if parsed := value.To16(); parsed != nil {
			v6 = append(v6, append(net.IP(nil), parsed...))
		}
	}
	return v4, v6
}

func reviewText(report Report) string {
	labels := map[string]string{"dns": "DNS", "ipv4": "IPv4", "ipv6": "IPv6", "tcp": "TCP", "quic": "QUIC"}
	var body strings.Builder
	fmt.Fprintf(&body, "KeenWG support report\nGenerated: %s\nVersion: %s\nState version: %d\nActive route: %t\nNodes: %d\n\n", report.GeneratedAt, report.Summary.Version, report.Summary.StateVersion, report.Summary.Active, report.Summary.NodeCount)
	for _, check := range report.Checks {
		fmt.Fprintf(&body, "%s: %s\n  observed: %s at %s\n  inferred: %s at %s\n", labels[check.Layer], check.Status, check.Observation.Code, check.Observation.At, check.Inference.Code, check.Inference.At)
	}
	if len(report.Notes) > 0 {
		body.WriteString("\nNotes:\n")
		for _, note := range report.Notes {
			fmt.Fprintf(&body, "- %s\n", note)
		}
	}
	return truncateUTF8Bytes(body.String(), MaxReviewTextBytes)
}

var (
	urlPattern               = regexp.MustCompile(`(?i)https?://[^\s]+`)
	subscriptionFieldPattern = regexp.MustCompile(`(?i)\bsubscription[_ -]?url\b\s*[:=]?`)
	credentialPattern        = regexp.MustCompile(`(?i)\b(?:bearer\s+[^\s,;]+|(?:token|password|passwd|secret)\s*[:=]\s*[^\s,;]+)`)
	uuidPattern              = regexp.MustCompile(`(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b`)
	keyPattern               = regexp.MustCompile(`[A-Za-z0-9+/]{43}=`)
	macPattern               = regexp.MustCompile(`(?i)\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\b`)
	hostPattern              = regexp.MustCompile(`(?i)\b(?:[a-z0-9](?:[a-z0-9-]{0,62})\.)+[a-z]{2,63}\b`)
	ipv4Pattern              = regexp.MustCompile(`\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b`)
	ipv6Pattern              = regexp.MustCompile(`[0-9A-Fa-f:]{3,}`)
	spacePattern             = regexp.MustCompile(`\s+`)
)

func Sanitize(value string) string {
	clean := truncateRunes(value, 4096)
	clean = subscriptionFieldPattern.ReplaceAllString(clean, "[configuration] ")
	clean = urlPattern.ReplaceAllString(clean, "[url]")
	clean = credentialPattern.ReplaceAllString(clean, "[credential]")
	clean = uuidPattern.ReplaceAllString(clean, "[uuid]")
	clean = keyPattern.ReplaceAllString(clean, "[key]")
	clean = macPattern.ReplaceAllString(clean, "[mac]")
	clean = hostPattern.ReplaceAllString(clean, "[host]")
	clean = ipv4Pattern.ReplaceAllString(clean, "[ip]")
	clean = ipv6Pattern.ReplaceAllStringFunc(clean, func(candidate string) string {
		if strings.Count(candidate, ":") >= 2 && net.ParseIP(candidate) != nil {
			return "[ip]"
		}
		return candidate
	})
	clean = strings.TrimSpace(spacePattern.ReplaceAllString(clean, " "))
	return truncateRunes(clean, maxNoteRunes)
}

func targetKind(target *Target) string {
	if target == nil || strings.TrimSpace(target.Host) == "" {
		return "none"
	}
	parsed := net.ParseIP(strings.TrimSpace(target.Host))
	if parsed == nil {
		return "domain"
	}
	if parsed.To4() != nil {
		return "ipv4"
	}
	return "ipv6"
}

func safeTransport(target *Target) string {
	if target == nil {
		return "unknown"
	}
	value := strings.ToLower(strings.TrimSpace(target.Transport))
	if value != "tcp" && value != "quic" {
		return "other"
	}
	return value
}

func boundedCount(value int) int {
	if value < 0 {
		return 0
	}
	if value > 10000 {
		return 10000
	}
	return value
}

func elapsedMS(start time.Time) int64 {
	value := time.Since(start).Milliseconds()
	if value < 0 {
		return 0
	}
	return value
}

func truncateRunes(value string, limit int) string {
	if limit < 1 {
		return ""
	}
	runes := []rune(value)
	if len(runes) <= limit {
		return value
	}
	return string(runes[:limit])
}

func truncateUTF8Bytes(value string, limit int) string {
	if limit < 1 {
		return ""
	}
	if len(value) <= limit {
		return value
	}
	end := limit
	for end > 0 && !utf8.ValidString(value[:end]) {
		end--
	}
	return value[:end]
}

type defaultResolver struct{}

func (defaultResolver) LookupIP(ctx context.Context, host string) ([]net.IP, error) {
	addresses, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return nil, err
	}
	result := make([]net.IP, 0, len(addresses))
	for _, address := range addresses {
		result = append(result, address.IP)
	}
	return result, nil
}

type defaultDialer struct{ dialer *net.Dialer }

func (d defaultDialer) Dial(ctx context.Context, network, address string) error {
	connection, err := d.dialer.DialContext(ctx, network, address)
	if err != nil {
		return err
	}
	return connection.Close()
}
