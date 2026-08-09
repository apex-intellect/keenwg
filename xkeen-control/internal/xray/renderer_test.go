package xray

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/netip"
	"os"
	"path/filepath"
	"testing"

	"github.com/goldb/keenwg/xkeen-control/internal/model"
)

func TestRenderOutboundsChangesOnlyManagedOutbound(t *testing.T) {
	current := fixture(t, "outbounds.json")
	node := validNode()
	got, err := RenderOutbounds(current, node, netip.MustParseAddr("203.0.113.44"))
	if err != nil {
		t.Fatal(err)
	}
	var document struct {
		CustomTopLevel map[string]bool  `json:"custom_top_level"`
		Outbounds      []map[string]any `json:"outbounds"`
	}
	if err := json.Unmarshal(got, &document); err != nil {
		t.Fatal(err)
	}
	if !document.CustomTopLevel["preserve"] || len(document.Outbounds) != 3 {
		t.Fatalf("document=%s", got)
	}
	managed := document.Outbounds[0]
	settings := managed["settings"].(map[string]any)
	vnext := settings["vnext"].([]any)[0].(map[string]any)
	if vnext["address"] != "203.0.113.44" || vnext["port"] != float64(node.Port) {
		t.Fatalf("managed=%v", managed)
	}
	reality := managed["streamSettings"].(map[string]any)["realitySettings"].(map[string]any)
	if reality["publicKey"] != node.PublicKey || reality["spiderX"] != node.SpiderX {
		t.Fatalf("reality=%v", reality)
	}
	if document.Outbounds[1]["tag"] != "direct" || document.Outbounds[1]["settings"].(map[string]any)["domainStrategy"] != "UseIP" || document.Outbounds[2]["tag"] != "block" {
		t.Fatalf("unrelated outbounds changed: %v", document.Outbounds)
	}
	publicJSON, err := json.Marshal(model.SanitizeNode(node, false))
	if err != nil || bytes.Contains(publicJSON, []byte(node.UUID)) || bytes.Contains(publicJSON, []byte(node.PublicKey)) || bytes.Contains(publicJSON, []byte(node.SpiderX)) {
		t.Fatalf("unsafe public node: %s err=%v", publicJSON, err)
	}
}

func TestRenderOutboundsRejectsMissingDuplicateAndInvalidIP(t *testing.T) {
	valid := fixture(t, "outbounds.json")
	duplicate := bytes.Replace(valid, []byte(`{"protocol":"freedom","tag":"direct"`), []byte(`{"protocol":"freedom","tag":"vless-reality"`), 1)
	missing := bytes.ReplaceAll(valid, []byte("vless-reality"), []byte("other-tag"))
	trailing := append(append([]byte(nil), valid...), []byte(`{}`)...)
	garbage := append(append([]byte(nil), valid...), []byte(`private-garbage`)...)
	for name, input := range map[string][]byte{"duplicate": duplicate, "missing": missing, "malformed": []byte("{"), "trailing object": trailing, "trailing garbage": garbage} {
		t.Run(name, func(t *testing.T) {
			if _, err := RenderOutbounds(input, validNode(), netip.MustParseAddr("203.0.113.44")); !errors.Is(err, ErrInvalidOutbounds) {
				t.Fatalf("err=%v", err)
			}
		})
	}
	if _, err := RenderOutbounds(valid, validNode(), netip.MustParseAddr("2001:db8::1")); !errors.Is(err, ErrInvalidEndpointIP) {
		t.Fatalf("ipv6 err=%v", err)
	}
}

func TestReplaceManagedExcludeBlockPreservesUserLines(t *testing.T) {
	current := fixture(t, "ip_exclude.lst")
	got, err := ReplaceManagedExcludeBlock(current, netip.MustParseAddr("203.0.113.44"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(got, []byte("8.8.8.8/32")) || !bytes.Contains(got, []byte("192.0.2.0/24")) || bytes.Count(got, []byte("BEGIN KEENWG")) != 1 || !bytes.Contains(got, []byte("203.0.113.44/32")) || bytes.Contains(got, []byte("203.0.113.10/32")) {
		t.Fatalf("%s", got)
	}
}

func TestManagedExcludeBlockCreationAndMalformedMarkersFailClosed(t *testing.T) {
	created, err := CreateManagedExcludeBlock([]byte("# custom\n8.8.8.8/32\n"), netip.MustParseAddr("203.0.113.44"))
	if err != nil || !bytes.Contains(created, []byte("203.0.113.44/32")) || !bytes.HasPrefix(created, []byte("# custom\n8.8.8.8/32\n")) {
		t.Fatalf("created=%s err=%v", created, err)
	}
	malformed := []byte("# BEGIN KEENWG XKeen ENDPOINT\n1.1.1.1/32\n# BEGIN KEENWG XKeen ENDPOINT\n2.2.2.2/32\n# END KEENWG XKeen ENDPOINT\n")
	if _, err := ReplaceManagedExcludeBlock(malformed, netip.MustParseAddr("203.0.113.44")); !errors.Is(err, ErrInvalidExcludeBlock) {
		t.Fatalf("err=%v", err)
	}
	if _, err := CreateManagedExcludeBlock(malformed, netip.MustParseAddr("203.0.113.44")); !errors.Is(err, ErrInvalidExcludeBlock) {
		t.Fatalf("create err=%v", err)
	}
}

func TestRemoveHardcodedEndpointChangesOnlySingleIPv4ExcludeAssignment(t *testing.T) {
	input := []byte("before=1\nipv4_exclude=\"10.0.0.0/8 203.0.113.44/32 192.168.0.0/16\"\nafter=2\n")
	got, err := RemoveHardcodedEndpoint(input, netip.MustParseAddr("203.0.113.44"))
	if err != nil || !bytes.Contains(got, []byte("10.0.0.0/8 192.168.0.0/16")) || bytes.Contains(got, []byte("203.0.113.44/32")) || !bytes.Contains(got, []byte("before=1\n")) || !bytes.Contains(got, []byte("after=2\n")) {
		t.Fatalf("got=%s err=%v", got, err)
	}
	for name, malformed := range map[string][]byte{
		"missing endpoint":     []byte("ipv4_exclude=\"10.0.0.0/8\"\n"),
		"duplicate assignment": []byte("ipv4_exclude=\"203.0.113.44/32\"\nipv4_exclude=\"203.0.113.44/32\"\n"),
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := RemoveHardcodedEndpoint(malformed, netip.MustParseAddr("203.0.113.44")); !errors.Is(err, ErrInvalidInitScript) {
				t.Fatalf("err=%v", err)
			}
		})
	}
}

func TestParseActiveOutboundReturnsOnlySanitizedBootstrapState(t *testing.T) {
	active, err := ParseActiveOutbound(fixture(t, "outbounds.json"), "Нидерланды 1", 123)
	if err != nil {
		t.Fatal(err)
	}
	if active.ID != "" || active.DisplayName != "Нидерланды 1" || active.Host != "203.0.113.10" || active.ResolvedIP != "203.0.113.10" || active.Port != 443 || active.Fingerprint != "firefox" || !active.Active || active.ConfirmedAt != 123 {
		t.Fatalf("active=%+v", active)
	}
	body, err := json.Marshal(active)
	if err != nil || bytes.Contains(body, []byte("aaaaaaaa-aaaa")) || bytes.Contains(body, []byte("OLD_SYNTHETIC_KEY")) || bytes.Contains(body, []byte("0011223344556677")) || bytes.Contains(body, []byte("old.example.test")) {
		t.Fatalf("unsafe active=%s err=%v", body, err)
	}
}

func TestParseActiveOutboundDoesNotWarnForChromeFingerprint(t *testing.T) {
	current := bytes.Replace(fixture(t, "outbounds.json"), []byte(`"fingerprint": "firefox"`), []byte(`"fingerprint": "chrome"`), 1)
	active, err := ParseActiveOutbound(current, "Германия", 123)
	if err != nil {
		t.Fatal(err)
	}
	if active.Fingerprint != "chrome" || len(active.Warnings) != 0 {
		t.Fatalf("warnings=%v", active.Warnings)
	}
}

func TestManagedExcludeIPRequiresOneStrictMarkerBlock(t *testing.T) {
	got, err := ManagedExcludeIP(fixture(t, "ip_exclude.lst"))
	if err != nil || got.String() != "203.0.113.10" {
		t.Fatalf("got=%v err=%v", got, err)
	}
	if _, err := ManagedExcludeIP([]byte("8.8.8.8/32\n")); !errors.Is(err, ErrInvalidExcludeBlock) {
		t.Fatalf("missing marker err=%v", err)
	}
}

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	body, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	return body
}

func validNode() model.Node {
	return model.Node{
		ID:           "aabbccddeeff00112233445566778899",
		CanonicalURI: "vless://private-synthetic-uri",
		DisplayName:  "🇳🇱 Нидерланды 1",
		Country:      "Нидерланды",
		Flag:         "🇳🇱",
		Host:         "nl1.example.test",
		Port:         8443,
		UUID:         "11111111-1111-4111-8111-111111111111",
		PublicKey:    "SYNTHETIC_PUBLIC_KEY_AAAAAAAAAAAAAAAAAAAAA",
		ShortID:      "0123456789abcdef",
		SNI:          "intel.example.test",
		SpiderX:      "/synthetic",
		Fingerprint:  "firefox",
		Transport:    "tcp",
		Security:     "reality",
		Flow:         "xtls-rprx-vision",
	}
}
