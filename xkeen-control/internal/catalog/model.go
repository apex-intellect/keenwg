package catalog

import (
	"errors"
	"fmt"
	"net/netip"
	"regexp"
	"strings"
	"time"

	"golang.org/x/net/idna"
)

const SchemaVersion = 1

const (
	MaxGroups  = 64
	MaxSources = 128
	MaxNodes   = 4096
)

type Protocol string

const (
	ProtocolVLESS     Protocol = "vless"
	ProtocolVMess     Protocol = "vmess"
	ProtocolTrojan    Protocol = "trojan"
	ProtocolHysteria2 Protocol = "hysteria2"
	ProtocolWireGuard Protocol = "wireguard"
	ProtocolAmneziaWG Protocol = "amneziawg"
)

type SourceKind string

const (
	SourceSubscription SourceKind = "subscription"
	SourceShareLink    SourceKind = "share_link"
	SourceConfig       SourceKind = "config"
	SourceForeign      SourceKind = "foreign"
)

type SourceStatus string

const (
	SourceReady SourceStatus = "ready"
	SourceStale SourceStatus = "stale"
	SourceError SourceStatus = "error"
)

type Group struct {
	ID    string `json:"id"`
	Label string `json:"label"`
	Order int    `json:"order"`
}

type Source struct {
	ID                  string       `json:"id"`
	GroupID             string       `json:"group_id"`
	Kind                SourceKind   `json:"kind"`
	Label               string       `json:"label"`
	AdapterID           string       `json:"adapter_id"`
	Status              SourceStatus `json:"status"`
	NodeCount           int          `json:"node_count"`
	LastRefresh         *time.Time   `json:"last_refresh,omitempty"`
	Warnings            []string     `json:"warnings"`
	Foreign             bool         `json:"foreign"`
	AdapterStateVersion uint64       `json:"adapter_state_version,omitempty"`
}

type Node struct {
	ID                 string   `json:"id"`
	SourceID           string   `json:"source_id"`
	GroupID            string   `json:"group_id"`
	DisplayName        string   `json:"display_name"`
	Country            string   `json:"country,omitempty"`
	Protocol           Protocol `json:"protocol"`
	Host               string   `json:"host"`
	Port               int      `json:"port"`
	Transport          string   `json:"transport,omitempty"`
	Security           string   `json:"security,omitempty"`
	ServerName         string   `json:"server_name,omitempty"`
	ALPN               string   `json:"alpn,omitempty"`
	Flow               string   `json:"flow,omitempty"`
	VariantFingerprint string   `json:"-"`
	Active             bool     `json:"active"`
	Testable           bool     `json:"testable"`
	Activatable        bool     `json:"activatable"`
	Warnings           []string `json:"warnings"`
}

type Document struct {
	SchemaVersion int      `json:"schema_version"`
	StateVersion  uint64   `json:"state_version"`
	Groups        []Group  `json:"groups"`
	Sources       []Source `json:"sources"`
	Nodes         []Node   `json:"nodes"`
}

var opaqueID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)

func (d Document) Validate() error {
	if d.SchemaVersion != SchemaVersion {
		return errors.New("unsupported catalog schema")
	}
	if len(d.Groups) > MaxGroups || len(d.Sources) > MaxSources || len(d.Nodes) > MaxNodes {
		return errors.New("catalog limit exceeded")
	}
	groups := make(map[string]struct{}, len(d.Groups))
	for _, group := range d.Groups {
		if !opaqueID.MatchString(group.ID) || !validLabel(group.Label) || group.Order < 0 {
			return errors.New("invalid catalog group")
		}
		if _, exists := groups[group.ID]; exists {
			return errors.New("duplicate catalog group")
		}
		groups[group.ID] = struct{}{}
	}
	sources := make(map[string]struct{}, len(d.Sources))
	for _, source := range d.Sources {
		if !opaqueID.MatchString(source.ID) || !opaqueID.MatchString(source.GroupID) || !opaqueID.MatchString(source.AdapterID) ||
			!validLabel(source.Label) || !validSourceKind(source.Kind) || !validSourceStatus(source.Status) || source.NodeCount < 0 {
			return errors.New("invalid catalog source")
		}
		if _, exists := groups[source.GroupID]; !exists {
			return errors.New("catalog source references unknown group")
		}
		if _, exists := sources[source.ID]; exists {
			return errors.New("duplicate catalog source")
		}
		sources[source.ID] = struct{}{}
	}
	nodes := make(map[string]struct{}, len(d.Nodes))
	counts := make(map[string]int, len(d.Sources))
	for _, node := range d.Nodes {
		if !opaqueID.MatchString(node.ID) || !opaqueID.MatchString(node.SourceID) || !opaqueID.MatchString(node.GroupID) ||
			!validLabel(node.DisplayName) || !validProtocol(node.Protocol) || node.Port < 1 || node.Port > 65535 {
			return errors.New("invalid catalog node")
		}
		if _, err := normalizeHost(node.Host); err != nil {
			return fmt.Errorf("invalid catalog node host: %w", err)
		}
		if _, exists := sources[node.SourceID]; !exists {
			return errors.New("catalog node references unknown source")
		}
		if _, exists := groups[node.GroupID]; !exists {
			return errors.New("catalog node references unknown group")
		}
		if _, exists := nodes[node.ID]; exists {
			return errors.New("duplicate catalog node")
		}
		nodes[node.ID] = struct{}{}
		counts[node.SourceID]++
	}
	for _, source := range d.Sources {
		if source.NodeCount != 0 && source.NodeCount != counts[source.ID] {
			return errors.New("catalog source node count mismatch")
		}
	}
	return nil
}

func validLabel(value string) bool {
	trimmed := strings.TrimSpace(value)
	return trimmed != "" && len([]rune(trimmed)) <= 128 && !strings.ContainsAny(trimmed, "\r\n\x00")
}

func validProtocol(value Protocol) bool {
	switch value {
	case ProtocolVLESS, ProtocolVMess, ProtocolTrojan, ProtocolHysteria2, ProtocolWireGuard, ProtocolAmneziaWG:
		return true
	default:
		return false
	}
}

func validSourceKind(value SourceKind) bool {
	switch value {
	case SourceSubscription, SourceShareLink, SourceConfig, SourceForeign:
		return true
	default:
		return false
	}
}

func validSourceStatus(value SourceStatus) bool {
	return value == "" || value == SourceReady || value == SourceStale || value == SourceError
}

func normalizeHost(value string) (string, error) {
	value = strings.TrimSuffix(strings.TrimSpace(value), ".")
	if value == "" || strings.Contains(value, "://") || strings.ContainsAny(value, "/?#@[]") {
		return "", errors.New("invalid host")
	}
	if address, err := netip.ParseAddr(value); err == nil {
		if address.IsUnspecified() || address.IsMulticast() {
			return "", errors.New("unsafe address")
		}
		return address.String(), nil
	}
	ascii, err := idna.Lookup.ToASCII(value)
	if err != nil || len(ascii) > 253 || !strings.Contains(ascii, ".") {
		return "", errors.New("invalid hostname")
	}
	return strings.ToLower(ascii), nil
}
