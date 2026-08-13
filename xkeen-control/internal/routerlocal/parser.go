package routerlocal

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/xml"
	"errors"
	"io"
	"net"
	"net/netip"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

var (
	macPattern      = regexp.MustCompile(`^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$`)
	peerNamePattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$`)
	staticPattern   = regexp.MustCompile(`^ip dhcp host ([0-9A-Fa-f:]{17}) ((?:[0-9]{1,3}\.){3}[0-9]{1,3})$`)
	interfaceLine   = regexp.MustCompile(`^interface ([A-Za-z0-9][A-Za-z0-9/_-]{0,63})$`)
	peerLine        = regexp.MustCompile(`^wireguard peer ([A-Za-z0-9+/]{43}=) !([A-Za-z0-9][A-Za-z0-9_-]{0,63})$`)
	allowIPLine     = regexp.MustCompile(`^allow-ips ((?:[0-9]{1,3}\.){3}[0-9]{1,3}) 255\.255\.255\.255$`)
	keepaliveLine   = regexp.MustCompile(`^keepalive-interval ([0-9]{1,4})$`)
	ipAddressLine   = regexp.MustCompile(`^ip address ((?:[0-9]{1,3}\.){3}[0-9]{1,3}) ((?:[0-9]{1,3}\.){3}[0-9]{1,3})$`)
	listenPortLine  = regexp.MustCompile(`^wireguard listen-port ([0-9]{1,5})$`)
)

type hotspotResponse struct {
	XMLName xml.Name     `xml:"response"`
	Hosts   []hotspotXML `xml:"host"`
}

type hotspotXML struct {
	MAC       string `xml:"mac"`
	IP        string `xml:"ip"`
	Hostname  string `xml:"hostname"`
	Name      string `xml:"name"`
	Active    string `xml:"active"`
	RSSI      *int   `xml:"rssi"`
	Interface struct {
		Name string `xml:"name"`
	} `xml:"interface"`
}

type leaseResponse struct {
	XMLName xml.Name   `xml:"response"`
	Leases  []leaseXML `xml:"lease"`
}

type leaseXML struct {
	MAC      string `xml:"mac"`
	IP       string `xml:"ip"`
	Hostname string `xml:"hostname"`
	Name     string `xml:"name"`
	Expires  string `xml:"expires"`
}

type runningResponse struct {
	XMLName xml.Name `xml:"response"`
	Lines   []string `xml:"message"`
}

type homeRecord struct {
	name, hostname, ip, interfaceName string
	online, infinite                  bool
	rssi                              *int
}

func ParseHomeDevices(hotspot, leases, running []byte) ([]HomeDevice, error) {
	var hotspotDoc hotspotResponse
	if err := decodeResponse(hotspot, &hotspotDoc); err != nil {
		return nil, err
	}
	var leaseDoc leaseResponse
	if err := decodeResponse(leases, &leaseDoc); err != nil {
		return nil, err
	}
	var runningDoc runningResponse
	if err := decodeResponse(running, &runningDoc); err != nil {
		return nil, err
	}
	if len(hotspotDoc.Hosts) > maxItems || len(leaseDoc.Leases) > maxItems || len(runningDoc.Lines) > maxItems*8 {
		return nil, ErrTooManyItems
	}
	hotspots := make(map[string]homeRecord, len(hotspotDoc.Hosts))
	for _, raw := range hotspotDoc.Hosts {
		mac, err := canonicalMAC(raw.MAC)
		if err != nil {
			return nil, ErrUnsupportedSchema
		}
		if _, exists := hotspots[mac]; exists {
			return nil, ErrDuplicateIdentity
		}
		ip, err := optionalIPv4(raw.IP)
		if err != nil {
			return nil, ErrUnsupportedSchema
		}
		if ip == "0.0.0.0" {
			ip = ""
		}
		if !boundedText(raw.Name, 128) || !boundedText(raw.Hostname, 253) || !boundedText(raw.Interface.Name, 64) {
			return nil, ErrUnsupportedSchema
		}
		online, err := boolish(raw.Active)
		if err != nil {
			return nil, ErrUnsupportedSchema
		}
		hotspots[mac] = homeRecord{name: strings.TrimSpace(raw.Name), hostname: strings.TrimSpace(raw.Hostname), ip: ip, online: online, interfaceName: strings.TrimSpace(raw.Interface.Name), rssi: raw.RSSI}
	}
	leaseValues := make(map[string]homeRecord, len(leaseDoc.Leases))
	for _, raw := range leaseDoc.Leases {
		mac, err := canonicalMAC(raw.MAC)
		if err != nil {
			return nil, ErrUnsupportedSchema
		}
		if _, exists := leaseValues[mac]; exists {
			return nil, ErrDuplicateIdentity
		}
		ip, err := optionalIPv4(raw.IP)
		if err != nil || ip == "" {
			return nil, ErrUnsupportedSchema
		}
		if !boundedText(raw.Name, 128) || !boundedText(raw.Hostname, 253) || !boundedText(raw.Expires, 64) {
			return nil, ErrUnsupportedSchema
		}
		leaseValues[mac] = homeRecord{name: strings.TrimSpace(raw.Name), hostname: strings.TrimSpace(raw.Hostname), ip: ip, infinite: strings.EqualFold(strings.TrimSpace(raw.Expires), "infinity")}
	}
	reservations := make(map[string]string)
	for _, raw := range runningDoc.Lines {
		line := strings.TrimSpace(raw)
		match := staticPattern.FindStringSubmatch(line)
		if match == nil {
			continue
		}
		mac, err := canonicalMAC(match[1])
		if err != nil {
			return nil, ErrUnsupportedSchema
		}
		ip, err := optionalIPv4(match[2])
		if err != nil || ip == "" {
			return nil, ErrUnsupportedSchema
		}
		if _, exists := reservations[mac]; exists {
			return nil, ErrDuplicateIdentity
		}
		reservations[mac] = ip
	}
	identities := make(map[string]struct{}, len(hotspots)+len(leaseValues)+len(reservations))
	for mac := range hotspots {
		identities[mac] = struct{}{}
	}
	for mac := range leaseValues {
		identities[mac] = struct{}{}
	}
	for mac := range reservations {
		identities[mac] = struct{}{}
	}
	if len(identities) > maxItems {
		return nil, ErrTooManyItems
	}
	result := make([]HomeDevice, 0, len(identities))
	for mac := range identities {
		host := hotspots[mac]
		lease := leaseValues[mac]
		reserved := reservations[mac]
		name := firstNonBlank(host.name, lease.name, host.hostname, lease.hostname, mac)
		ip := firstNonBlank(host.ip, lease.ip, reserved)
		hostname := firstNonBlank(host.hostname, lease.hostname)
		result = append(result, HomeDevice{ID: homeDeviceID(mac), MAC: mac, Name: name, Hostname: hostname, IP: ip, ReservedIP: reserved, Online: host.online, StaticReservation: reserved != "" || lease.infinite, InterfaceName: host.interfaceName, RSSI: cloneInt(host.rssi)})
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].Online != result[j].Online {
			return result[i].Online
		}
		left, right := strings.ToLower(result[i].Name), strings.ToLower(result[j].Name)
		if left != right {
			return left < right
		}
		return result[i].MAC < result[j].MAC
	})
	return result, nil
}

func DiscoverWireGuardInterfaces(running []byte) ([]string, error) {
	doc, err := parseRunning(running)
	if err != nil {
		return nil, err
	}
	seen := make(map[string]bool)
	current := ""
	for _, raw := range doc.Lines {
		line := strings.TrimSpace(raw)
		if match := interfaceLine.FindStringSubmatch(line); match != nil {
			current = match[1]
			continue
		}
		if current != "" && (strings.HasPrefix(line, "wireguard ") || strings.HasPrefix(line, "wireguard peer ")) {
			seen[current] = true
		}
	}
	result := make([]string, 0, len(seen))
	for id := range seen {
		result = append(result, id)
	}
	sort.Strings(result)
	return result, nil
}

type wireGuardResponse struct {
	XMLName    xml.Name                `xml:"response"`
	ID         string                  `xml:"id"`
	MTU        int                     `xml:"mtu"`
	WireGuard  wireGuardRuntimeXML     `xml:"wireguard"`
	Interfaces []wireGuardInterfaceXML `xml:"interface"`
}

type wireGuardInterfaceXML struct {
	ID        string              `xml:"id"`
	MTU       int                 `xml:"mtu"`
	WireGuard wireGuardRuntimeXML `xml:"wireguard"`
}

type wireGuardRuntimeXML struct {
	PublicKey string             `xml:"public-key"`
	Peers     []wireGuardPeerXML `xml:"peer"`
}

type wireGuardPeerXML struct {
	PublicKey        string `xml:"public-key"`
	Online           string `xml:"online"`
	Enabled          string `xml:"enabled"`
	LastHandshakeSec *int64 `xml:"last-handshake"`
	RXBytes          uint64 `xml:"rxbytes"`
	TXBytes          uint64 `xml:"txbytes"`
}

type configuredPeer struct {
	WireGuardPeer
}

const keenOSInvalidHandshakeSentinel int64 = 1<<31 - 1

func ParseWireGuardInterface(runtime, running []byte, interfaceID string) (WireGuardInterface, error) {
	if !interfacePattern.MatchString(interfaceID) {
		return WireGuardInterface{}, ErrInvalidCommand
	}
	var runtimeDoc wireGuardResponse
	if err := decodeResponse(runtime, &runtimeDoc); err != nil {
		return WireGuardInterface{}, err
	}
	var runtimeValue wireGuardInterfaceXML
	if runtimeDoc.ID != "" || runtimeDoc.WireGuard.PublicKey != "" || len(runtimeDoc.WireGuard.Peers) > 0 {
		runtimeValue = wireGuardInterfaceXML{ID: runtimeDoc.ID, MTU: runtimeDoc.MTU, WireGuard: runtimeDoc.WireGuard}
	} else {
		if len(runtimeDoc.Interfaces) != 1 {
			return WireGuardInterface{}, ErrUnsupportedSchema
		}
		runtimeValue = runtimeDoc.Interfaces[0]
	}
	if runtimeValue.ID != interfaceID {
		return WireGuardInterface{}, ErrUnsupportedSchema
	}
	if len(runtimeValue.WireGuard.Peers) > maxItems {
		return WireGuardInterface{}, ErrTooManyItems
	}
	runtimePeers := make(map[string]wireGuardPeerXML, len(runtimeValue.WireGuard.Peers))
	for _, peer := range runtimeValue.WireGuard.Peers {
		if !canonicalWireGuardKey(peer.PublicKey) {
			return WireGuardInterface{}, ErrUnsupportedSchema
		}
		if _, exists := runtimePeers[peer.PublicKey]; exists {
			return WireGuardInterface{}, ErrDuplicateIdentity
		}
		if _, err := boolishOptional(peer.Online); err != nil {
			return WireGuardInterface{}, ErrUnsupportedSchema
		}
		if _, err := boolishOptional(peer.Enabled); err != nil {
			return WireGuardInterface{}, ErrUnsupportedSchema
		}
		if peer.LastHandshakeSec != nil && (*peer.LastHandshakeSec < 0 || (*peer.LastHandshakeSec >= 1_000_000_000 && *peer.LastHandshakeSec != keenOSInvalidHandshakeSentinel)) {
			return WireGuardInterface{}, ErrUnsupportedSchema
		}
		runtimePeers[peer.PublicKey] = peer
	}
	if runtimeValue.WireGuard.PublicKey != "" && !canonicalWireGuardKey(runtimeValue.WireGuard.PublicKey) {
		return WireGuardInterface{}, ErrUnsupportedSchema
	}
	runningDoc, err := parseRunning(running)
	if err != nil {
		return WireGuardInterface{}, err
	}
	configured, addresses, listenPort, err := configuredPeers(runningDoc.Lines, interfaceID)
	if err != nil {
		return WireGuardInterface{}, err
	}
	if len(configured) > maxItems {
		return WireGuardInterface{}, ErrTooManyItems
	}
	result := WireGuardInterface{ID: interfaceID, PublicKey: runtimeValue.WireGuard.PublicKey, Addresses: addresses, ListenPort: listenPort, MTU: runtimeValue.MTU, Peers: make([]WireGuardPeer, 0, len(configured))}
	for _, peer := range configured {
		value := peer.WireGuardPeer
		if live, exists := runtimePeers[value.PublicKey]; exists {
			value.Online, _ = boolishOptional(live.Online)
			if live.Enabled != "" {
				value.Enabled, _ = boolishOptional(live.Enabled)
			}
			value.LastHandshakeSec = cloneInt64(live.LastHandshakeSec)
			value.RXBytes = live.RXBytes
			value.TXBytes = live.TXBytes
			delete(runtimePeers, value.PublicKey)
		}
		result.Peers = append(result.Peers, value)
	}
	if len(runtimePeers) != 0 {
		return WireGuardInterface{}, ErrUnsupportedSchema
	}
	return result, nil
}

func configuredPeers(lines []string, interfaceID string) ([]configuredPeer, []string, int, error) {
	current := ""
	result := make([]configuredPeer, 0)
	seen := make(map[string]struct{})
	addresses := make([]string, 0)
	listenPort := 0
	var active *configuredPeer
	finish := func() error {
		if active == nil {
			return nil
		}
		if _, exists := seen[active.PublicKey]; exists {
			return ErrDuplicateIdentity
		}
		seen[active.PublicKey] = struct{}{}
		result = append(result, *active)
		active = nil
		return nil
	}
	for _, raw := range lines {
		line := strings.TrimSpace(raw)
		if match := interfaceLine.FindStringSubmatch(line); match != nil {
			if err := finish(); err != nil {
				return nil, nil, 0, err
			}
			current = match[1]
			continue
		}
		if current != interfaceID {
			continue
		}
		if match := peerLine.FindStringSubmatch(line); match != nil {
			if err := finish(); err != nil {
				return nil, nil, 0, err
			}
			if !canonicalWireGuardKey(match[1]) || !peerNamePattern.MatchString(match[2]) {
				return nil, nil, 0, ErrUnsupportedSchema
			}
			active = &configuredPeer{WireGuardPeer: WireGuardPeer{PublicKey: match[1], Name: match[2]}}
			continue
		}
		if strings.HasPrefix(line, "wireguard peer ") {
			return nil, nil, 0, ErrUnsupportedSchema
		}
		if active != nil {
			switch {
			case line == "!":
				if err := finish(); err != nil {
					return nil, nil, 0, err
				}
			case allowIPLine.MatchString(line):
				match := allowIPLine.FindStringSubmatch(line)
				ip, err := optionalIPv4(match[1])
				if err != nil || ip == "" {
					return nil, nil, 0, ErrUnsupportedSchema
				}
				active.AllowedIP = ip
			case keepaliveLine.MatchString(line):
				value, _ := strconv.Atoi(keepaliveLine.FindStringSubmatch(line)[1])
				if value > 3600 {
					return nil, nil, 0, ErrUnsupportedSchema
				}
				active.Keepalive = value
			case line == "connect":
				active.Enabled = true
			case line == "no connect":
				active.Enabled = false
			case line != "":
				return nil, nil, 0, ErrUnsupportedSchema
			}
			continue
		}
		if match := ipAddressLine.FindStringSubmatch(line); match != nil {
			ip, err := optionalIPv4(match[1])
			if err != nil || ip == "" {
				return nil, nil, 0, ErrUnsupportedSchema
			}
			mask := net.ParseIP(match[2]).To4()
			if mask == nil {
				return nil, nil, 0, ErrUnsupportedSchema
			}
			ones, bits := net.IPMask(mask).Size()
			if bits != 32 || ones < 0 {
				return nil, nil, 0, ErrUnsupportedSchema
			}
			addresses = append(addresses, ip+"/"+strconv.Itoa(ones))
			continue
		}
		if match := listenPortLine.FindStringSubmatch(line); match != nil {
			value, _ := strconv.Atoi(match[1])
			if value < 1 || value > 65535 {
				return nil, nil, 0, ErrUnsupportedSchema
			}
			listenPort = value
		}
	}
	if err := finish(); err != nil {
		return nil, nil, 0, err
	}
	return result, addresses, listenPort, nil
}

func parseRunning(data []byte) (runningResponse, error) {
	var doc runningResponse
	if err := decodeResponse(data, &doc); err != nil {
		return runningResponse{}, err
	}
	if len(doc.Lines) > maxItems*8 {
		return runningResponse{}, ErrTooManyItems
	}
	for _, line := range doc.Lines {
		if !boundedText(line, 512) {
			return runningResponse{}, ErrUnsupportedSchema
		}
	}
	return doc, nil
}

func decodeResponse(data []byte, target any) error {
	if len(data) > maxStdoutBytes {
		return ErrOutputTooLarge
	}
	decoder := xml.NewDecoder(bytes.NewReader(data))
	if err := decoder.Decode(target); err != nil {
		return ErrUnsupportedSchema
	}
	for {
		token, err := decoder.Token()
		if errors.Is(err, io.EOF) {
			return nil
		}
		if err != nil {
			return ErrUnsupportedSchema
		}
		if text, ok := token.(xml.CharData); ok && strings.TrimSpace(string(text)) == "" {
			continue
		}
		return ErrUnsupportedSchema
	}
}

func canonicalMAC(value string) (string, error) {
	normalized := strings.ToLower(strings.TrimSpace(value))
	if !macPattern.MatchString(normalized) {
		return "", ErrUnsupportedSchema
	}
	return normalized, nil
}

func optionalIPv4(value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", nil
	}
	address, err := netip.ParseAddr(value)
	if err != nil || !address.Is4() || address.String() != value {
		return "", ErrUnsupportedSchema
	}
	return value, nil
}

func canonicalWireGuardKey(value string) bool {
	if len(value) != 44 {
		return false
	}
	decoded, err := base64.StdEncoding.DecodeString(value)
	return err == nil && len(decoded) == 32 && base64.StdEncoding.EncodeToString(decoded) == value
}

func boolish(value string) (bool, error) {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "yes", "true", "1":
		return true, nil
	case "no", "false", "0", "":
		return false, nil
	default:
		return false, ErrUnsupportedSchema
	}
}

func boolishOptional(value string) (bool, error) { return boolish(value) }

func boundedText(value string, max int) bool {
	return len(value) <= max && !strings.ContainsRune(value, '\x00')
}
func firstNonBlank(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}
func cloneInt(value *int) *int {
	if value == nil {
		return nil
	}
	copyValue := *value
	return &copyValue
}
func cloneInt64(value *int64) *int64 {
	if value == nil {
		return nil
	}
	copyValue := *value
	return &copyValue
}

func homeDeviceID(mac string) string {
	digest := sha256.Sum256([]byte(mac))
	return "mac-" + hex.EncodeToString(digest[:8])
}
