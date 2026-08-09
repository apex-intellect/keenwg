package catalog

import (
	"strings"
	"testing"
)

func TestNodeIdentityIncludesTransportAndVariant(t *testing.T) {
	tcp := Node{
		Protocol: ProtocolVLESS, Host: "VPN.Example", Port: 443,
		Transport: "tcp", Security: "reality", ServerName: "CDN.Example",
		VariantFingerprint: "path-a",
	}
	websocket := tcp
	websocket.Transport = "ws"
	otherPath := tcp
	otherPath.VariantFingerprint = "path-b"
	if NodeIdentity(tcp) == NodeIdentity(websocket) {
		t.Fatal("different transports collided")
	}
	if NodeIdentity(tcp) == NodeIdentity(otherPath) {
		t.Fatal("different transport variants collided")
	}
}

func TestNodeIdentityNormalizesHostAndContainsNoEndpointText(t *testing.T) {
	upper := Node{Protocol: ProtocolTrojan, Host: "XN--E1AFMKFD.XN--P1AI", Port: 443, Transport: "tcp", Security: "tls"}
	lower := upper
	lower.Host = "xn--e1afmkfd.xn--p1ai."
	first := NodeIdentity(upper)
	second := NodeIdentity(lower)
	if first != second {
		t.Fatalf("host case or trailing dot changed identity: %q != %q", first, second)
	}
	for _, forbidden := range []string{"xn--e1afmkfd", "443", "trojan"} {
		if strings.Contains(first, forbidden) {
			t.Fatalf("identity exposed canonical input %q: %s", forbidden, first)
		}
	}
}

func TestDocumentValidationRejectsDuplicateIDsAndInvalidEndpoints(t *testing.T) {
	valid := Document{
		SchemaVersion: SchemaVersion,
		Groups:        []Group{{ID: "primary", Label: "Primary", Order: 0}},
		Sources:       []Source{{ID: "source-1", GroupID: "primary", Kind: SourceSubscription, Label: "Provider", AdapterID: "xkeen"}},
		Nodes:         []Node{{ID: "node-1", SourceID: "source-1", GroupID: "primary", DisplayName: "NL 1", Protocol: ProtocolVLESS, Host: "vpn.example", Port: 443}},
	}
	if err := valid.Validate(); err != nil {
		t.Fatal(err)
	}
	duplicate := valid
	duplicate.Nodes = append(append([]Node(nil), valid.Nodes...), valid.Nodes[0])
	if err := duplicate.Validate(); err == nil {
		t.Fatal("duplicate node ID was accepted")
	}
	invalid := valid
	invalid.Nodes = append([]Node(nil), valid.Nodes...)
	invalid.Nodes[0].Host = "https://vpn.example"
	if err := invalid.Validate(); err == nil {
		t.Fatal("URL was accepted as a node host")
	}
}
