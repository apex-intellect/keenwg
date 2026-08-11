package xray

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/netip"
	"strings"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/model"
)

var (
	ErrInvalidOutbounds    = errors.New("invalid_outbounds")
	ErrInvalidEndpointIP   = errors.New("invalid_endpoint_ip")
	ErrInvalidExcludeBlock = errors.New("invalid_exclude_block")
	ErrInvalidInitScript   = errors.New("invalid_init_script")
	ErrInvalidNode         = errors.New("invalid_node")
)

const (
	beginMarker = "# BEGIN KEENWG XKeen ENDPOINT"
	endMarker   = "# END KEENWG XKeen ENDPOINT"
)

func RenderOutbounds(current []byte, node model.Node, ip netip.Addr) ([]byte, error) {
	if !validEndpointIP(ip) {
		return nil, ErrInvalidEndpointIP
	}
	if !isValidNode(node) {
		return nil, ErrInvalidNode
	}
	var document map[string]json.RawMessage
	if err := decodeStrictJSON(current, &document); err != nil {
		return nil, ErrInvalidOutbounds
	}
	rawOutbounds, exists := document["outbounds"]
	if !exists {
		return nil, ErrInvalidOutbounds
	}
	var outbounds []json.RawMessage
	if err := json.Unmarshal(rawOutbounds, &outbounds); err != nil {
		return nil, ErrInvalidOutbounds
	}
	managedIndex := -1
	for i, raw := range outbounds {
		var header struct {
			Tag string `json:"tag"`
		}
		if err := json.Unmarshal(raw, &header); err != nil {
			return nil, ErrInvalidOutbounds
		}
		if header.Tag == "vless-reality" {
			if managedIndex >= 0 {
				return nil, ErrInvalidOutbounds
			}
			managedIndex = i
		}
	}
	if managedIndex < 0 {
		return nil, ErrInvalidOutbounds
	}
	managed, err := json.Marshal(renderedOutbound(node, ip))
	if err != nil {
		return nil, ErrInvalidOutbounds
	}
	outbounds[managedIndex] = managed
	document["outbounds"], err = json.Marshal(outbounds)
	if err != nil {
		return nil, ErrInvalidOutbounds
	}
	result, err := json.MarshalIndent(document, "", "  ")
	if err != nil {
		return nil, ErrInvalidOutbounds
	}
	return append(result, '\n'), nil
}

func renderedOutbound(node model.Node, ip netip.Addr) map[string]any {
	return map[string]any{
		"tag":      "vless-reality",
		"protocol": "vless",
		"settings": map[string]any{
			"vnext": []any{map[string]any{
				"address": ip.String(),
				"port":    node.Port,
				"users": []any{map[string]any{
					"id":         node.UUID,
					"encryption": "none",
					"flow":       node.Flow,
					"level":      0,
				}},
			}},
		},
		"streamSettings": map[string]any{
			"network":  node.Transport,
			"security": node.Security,
			"realitySettings": map[string]any{
				"publicKey":   node.PublicKey,
				"fingerprint": node.Fingerprint,
				"serverName":  node.SNI,
				"shortId":     node.ShortID,
				"spiderX":     node.SpiderX,
			},
		},
	}
}

func ReplaceManagedExcludeBlock(current []byte, ip netip.Addr) ([]byte, error) {
	if !validEndpointIP(ip) {
		return nil, ErrInvalidEndpointIP
	}
	text := newTextLines(current)
	begin, end, err := markerIndexes(text.lines)
	if err != nil || end != begin+2 {
		return nil, ErrInvalidExcludeBlock
	}
	prefix, err := netip.ParsePrefix(strings.TrimSpace(text.lines[begin+1]))
	if err != nil || !prefix.Addr().Is4() || prefix.Bits() != 32 {
		return nil, ErrInvalidExcludeBlock
	}
	text.lines[begin+1] = ip.String() + "/32"
	return text.bytes(), nil
}

func CreateManagedExcludeBlock(current []byte, ip netip.Addr) ([]byte, error) {
	if !validEndpointIP(ip) {
		return nil, ErrInvalidEndpointIP
	}
	text := newTextLines(current)
	for _, line := range text.lines {
		if strings.TrimSpace(line) == beginMarker || strings.TrimSpace(line) == endMarker {
			return nil, ErrInvalidExcludeBlock
		}
	}
	if len(text.lines) == 1 && text.lines[0] == "" {
		text.lines = nil
	}
	text.lines = append(text.lines, beginMarker, ip.String()+"/32", endMarker)
	text.finalNewline = true
	return text.bytes(), nil
}

func RemoveHardcodedEndpoint(initScript []byte, oldIP netip.Addr) ([]byte, error) {
	if !validEndpointIP(oldIP) {
		return nil, ErrInvalidEndpointIP
	}
	text := newTextLines(initScript)
	assignmentIndex := -1
	assignmentCount := 0
	target := oldIP.String() + "/32"
	targetCount := 0
	for i, line := range text.lines {
		trimmed := strings.TrimSpace(line)
		if !strings.HasPrefix(trimmed, "ipv4_exclude=") {
			continue
		}
		assignmentCount++
		assignmentIndex = i
		separator := strings.Index(line, "=")
		if separator < 0 {
			return nil, ErrInvalidInitScript
		}
		rawValue := strings.TrimSpace(line[separator+1:])
		if len(rawValue) < 2 || rawValue[0] != '"' || rawValue[len(rawValue)-1] != '"' {
			return nil, ErrInvalidInitScript
		}
		for _, token := range strings.Fields(rawValue[1 : len(rawValue)-1]) {
			if token == target {
				targetCount++
			}
		}
	}
	if assignmentCount != 1 || targetCount != 1 || assignmentIndex < 0 {
		return nil, ErrInvalidInitScript
	}
	line := text.lines[assignmentIndex]
	separator := strings.Index(line, "=")
	rawValue := strings.TrimSpace(line[separator+1:])
	tokens := strings.Fields(rawValue[1 : len(rawValue)-1])
	kept := make([]string, 0, len(tokens)-1)
	for _, token := range tokens {
		if token != target {
			kept = append(kept, token)
		}
	}
	text.lines[assignmentIndex] = line[:separator+1] + `"` + strings.Join(kept, " ") + `"`
	return text.bytes(), nil
}

func ManagedExcludeIP(current []byte) (netip.Addr, error) {
	text := newTextLines(current)
	begin, end, err := markerIndexes(text.lines)
	if err != nil || end != begin+2 {
		return netip.Addr{}, ErrInvalidExcludeBlock
	}
	prefix, err := netip.ParsePrefix(strings.TrimSpace(text.lines[begin+1]))
	if err != nil || !prefix.Addr().Is4() || prefix.Bits() != 32 {
		return netip.Addr{}, ErrInvalidExcludeBlock
	}
	return prefix.Addr().Unmap(), nil
}

func ParseActiveOutbound(current []byte, displayName string, confirmedAt int64) (*model.ActiveNode, error) {
	var document struct {
		Outbounds []json.RawMessage `json:"outbounds"`
	}
	if err := decodeStrictJSON(current, &document); err != nil {
		return nil, ErrInvalidOutbounds
	}
	var managed *struct {
		Tag      string `json:"tag"`
		Protocol string `json:"protocol"`
		Settings struct {
			VNext []struct {
				Address string `json:"address"`
				Port    int    `json:"port"`
				Users   []struct {
					ID         string `json:"id"`
					Encryption string `json:"encryption"`
					Flow       string `json:"flow"`
				} `json:"users"`
			} `json:"vnext"`
		} `json:"settings"`
		StreamSettings struct {
			Network         string `json:"network"`
			Security        string `json:"security"`
			RealitySettings struct {
				PublicKey   string `json:"publicKey"`
				Fingerprint string `json:"fingerprint"`
				ServerName  string `json:"serverName"`
				ShortID     string `json:"shortId"`
			} `json:"realitySettings"`
		} `json:"streamSettings"`
	}
	for _, raw := range document.Outbounds {
		var candidate struct {
			Tag      string `json:"tag"`
			Protocol string `json:"protocol"`
			Settings struct {
				VNext []struct {
					Address string `json:"address"`
					Port    int    `json:"port"`
					Users   []struct {
						ID         string `json:"id"`
						Encryption string `json:"encryption"`
						Flow       string `json:"flow"`
					} `json:"users"`
				} `json:"vnext"`
			} `json:"settings"`
			StreamSettings struct {
				Network         string `json:"network"`
				Security        string `json:"security"`
				RealitySettings struct {
					PublicKey   string `json:"publicKey"`
					Fingerprint string `json:"fingerprint"`
					ServerName  string `json:"serverName"`
					ShortID     string `json:"shortId"`
				} `json:"realitySettings"`
			} `json:"streamSettings"`
		}
		if err := json.Unmarshal(raw, &candidate); err != nil {
			return nil, ErrInvalidOutbounds
		}
		if candidate.Tag != "vless-reality" {
			continue
		}
		if managed != nil {
			return nil, ErrInvalidOutbounds
		}
		managed = &candidate
	}
	if managed == nil || managed.Protocol != "vless" || len(managed.Settings.VNext) != 1 || len(managed.Settings.VNext[0].Users) != 1 {
		return nil, ErrInvalidOutbounds
	}
	vnext := managed.Settings.VNext[0]
	user := vnext.Users[0]
	address, err := netip.ParseAddr(vnext.Address)
	if err != nil || !validEndpointIP(address) || vnext.Port < 1 || vnext.Port > 65535 || user.ID == "" || user.Encryption != "none" || user.Flow != "xtls-rprx-vision" || managed.StreamSettings.Network != "tcp" || managed.StreamSettings.Security != "reality" || managed.StreamSettings.RealitySettings.PublicKey == "" || managed.StreamSettings.RealitySettings.ServerName == "" || managed.StreamSettings.RealitySettings.ShortID == "" || managed.StreamSettings.RealitySettings.Fingerprint == "" {
		return nil, ErrInvalidOutbounds
	}
	if strings.TrimSpace(displayName) == "" {
		displayName = "Текущий узел"
	}
	return &model.ActiveNode{
		PublicNode: model.PublicNode{
			DisplayName: strings.TrimSpace(displayName),
			Host:        address.String(),
			Port:        vnext.Port,
			Fingerprint: managed.StreamSettings.RealitySettings.Fingerprint,
			Transport:   managed.StreamSettings.Network,
			Security:    managed.StreamSettings.Security,
			Flow:        user.Flow,
			Active:      true,
			Warnings:    []string{},
		},
		ResolvedIP:  address.String(),
		ConfirmedAt: confirmedAt,
	}, nil
}

func markerIndexes(lines []string) (int, int, error) {
	begin := -1
	end := -1
	for i, line := range lines {
		switch strings.TrimSpace(line) {
		case beginMarker:
			if begin >= 0 {
				return -1, -1, ErrInvalidExcludeBlock
			}
			begin = i
		case endMarker:
			if end >= 0 {
				return -1, -1, ErrInvalidExcludeBlock
			}
			end = i
		}
	}
	if begin < 0 || end < 0 || end <= begin {
		return -1, -1, ErrInvalidExcludeBlock
	}
	return begin, end, nil
}

func validEndpointIP(ip netip.Addr) bool {
	return ip.IsValid() && ip.Is4() && !ip.IsUnspecified() && !ip.IsMulticast()
}

func isValidNode(node model.Node) bool {
	return node.Port >= 1 && node.Port <= 65535 && node.UUID != "" && node.PublicKey != "" && node.ShortID != "" && node.SNI != "" && node.SpiderX != "" && node.Fingerprint != "" && node.Transport == "tcp" && node.Security == "reality" && node.Flow == "xtls-rprx-vision"
}

func decodeStrictJSON(input []byte, destination any) error {
	decoder := json.NewDecoder(bytes.NewReader(input))
	if err := decoder.Decode(destination); err != nil {
		return err
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return ErrInvalidOutbounds
	}
	return nil
}

type textLines struct {
	lines        []string
	eol          string
	finalNewline bool
}

func newTextLines(input []byte) textLines {
	eol := "\n"
	if bytes.Contains(input, []byte("\r\n")) {
		eol = "\r\n"
	}
	normalized := strings.ReplaceAll(string(input), "\r\n", "\n")
	finalNewline := strings.HasSuffix(normalized, "\n")
	if finalNewline {
		normalized = strings.TrimSuffix(normalized, "\n")
	}
	return textLines{lines: strings.Split(normalized, "\n"), eol: eol, finalNewline: finalNewline}
}

func (t textLines) bytes() []byte {
	result := strings.Join(t.lines, t.eol)
	if t.finalNewline {
		result += t.eol
	}
	return []byte(result)
}
