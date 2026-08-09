package domainpolicy

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func routingFixture(t *testing.T) []byte {
	t.Helper()
	body, err := os.ReadFile(filepath.Join("testdata", "05_routing.json"))
	if err != nil {
		t.Fatal(err)
	}
	return body
}

func TestImportLegacyNarrowsBroadZonesAndKeepsServices(t *testing.T) {
	policy, report, err := ImportLegacy(routingFixture(t))
	if err != nil {
		t.Fatal(err)
	}
	if !report.RemovedBroadInfo || !report.RemovedBroadTV {
		t.Fatalf("report=%+v", report)
	}
	want := map[string]bool{
		"geosite:category-gov-ru": false,
		"domain:okko.sport":       false,
		"domain:okko.tv":          false,
		"domain:okko.ru":          false,
		"domain:1c.ru":            false,
		"suffix:ru":               false,
		"suffix:su":               false,
		"suffix:xn--p1ai":         false,
		"suffix:moscow":           false,
	}
	for _, rule := range policy.Rules {
		key := rule.Kind + ":" + rule.Value
		if _, ok := want[key]; ok {
			want[key] = true
		}
		if rule.Kind == "suffix" && (rule.Value == "info" || rule.Value == "tv") {
			t.Fatalf("broad rule retained: %+v", rule)
		}
	}
	for key, found := range want {
		if !found {
			t.Errorf("missing %s", key)
		}
	}
}

func TestRenderRoutingReplacesOnlyLegacyDomainRegion(t *testing.T) {
	current := routingFixture(t)
	policy, _, err := ImportLegacy(current)
	if err != nil {
		t.Fatal(err)
	}
	got, err := RenderRouting(current, policy)
	if err != nil {
		t.Fatal(err)
	}
	legacyStart := bytes.Index(current, []byte("      // 1C ecosystem"))
	legacyEnd := bytes.Index(current, []byte("      // Direct: Russian IP ranges"))
	managedStart := bytes.Index(got, []byte("      // BEGIN KEENWG DOMAIN POLICY"))
	managedEnd := bytes.Index(got, []byte("      // Direct: Russian IP ranges"))
	if legacyStart < 0 || legacyEnd < 0 || managedStart < 0 || managedEnd < 0 {
		t.Fatal("expected boundaries missing")
	}
	if !bytes.Equal(current[:legacyStart], got[:managedStart]) || !bytes.Equal(current[legacyEnd:], got[managedEnd:]) {
		t.Fatal("bytes outside migrated region changed")
	}
	text := string(got)
	for _, forbidden := range []string{")info$", ")tv$"} {
		if strings.Contains(text, forbidden) {
			t.Fatalf("found %q", forbidden)
		}
	}
	for _, required := range []string{"ext:geosite_v2fly.dat:category-gov-ru", "domain:okko.sport", "domain:okko.tv", "domain:okko.ru"} {
		if !strings.Contains(text, required) {
			t.Fatalf("missing %q", required)
		}
	}
}

func TestRenderRoutingUpdatesManagedBlockAndPreservesOutside(t *testing.T) {
	policy, _, _ := ImportLegacy(routingFixture(t))
	first, err := RenderRouting(routingFixture(t), policy)
	if err != nil {
		t.Fatal(err)
	}
	policy.Rules[0].Enabled = false
	second, err := RenderRouting(first, policy)
	if err != nil {
		t.Fatal(err)
	}
	firstBefore, firstAfter, err := splitManaged(first)
	if err != nil {
		t.Fatal(err)
	}
	secondBefore, secondAfter, err := splitManaged(second)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(firstBefore, secondBefore) || !bytes.Equal(firstAfter, secondAfter) {
		t.Fatal("unmanaged bytes changed")
	}
}

func TestRenderRoutingFailsClosedForMalformedMarkers(t *testing.T) {
	policy, _, _ := ImportLegacy(routingFixture(t))
	for _, body := range [][]byte{
		[]byte("// BEGIN KEENWG DOMAIN POLICY\n{}"),
		[]byte("// END KEENWG DOMAIN POLICY\n{}"),
		[]byte("// BEGIN KEENWG DOMAIN POLICY\n// END KEENWG DOMAIN POLICY\n// END KEENWG DOMAIN POLICY"),
	} {
		if _, err := RenderRouting(body, policy); err == nil {
			t.Fatalf("malformed markers accepted: %q", body)
		}
	}
}

func TestRenderRoutingEmitsCIDRAsIPMatcherForDirectAndVPN(t *testing.T) {
	current := routingFixture(t)
	policy, _, err := ImportLegacy(current)
	if err != nil {
		t.Fatal(err)
	}
	direct, err := CanonicalizeRule(Rule{Kind: "cidr", Value: "192.0.2.0/24", Effect: "direct", Label: "Direct net", Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	vpn, err := CanonicalizeRule(Rule{Kind: "cidr", Value: "198.51.100.0/24", Effect: "vpn", Label: "VPN net", Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	policy.Rules = append(policy.Rules, direct, vpn)
	rendered, err := RenderRouting(current, policy)
	if err != nil {
		t.Fatal(err)
	}
	for _, required := range []string{`"ip": [`, `"192.0.2.0/24"`, `"198.51.100.0/24"`, `"outboundTag": "direct"`, `"outboundTag": "vless-reality"`} {
		if !bytes.Contains(rendered, []byte(required)) {
			t.Fatalf("missing %s in %s", required, rendered)
		}
	}
}
