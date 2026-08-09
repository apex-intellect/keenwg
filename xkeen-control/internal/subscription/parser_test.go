package subscription

import (
	"encoding/base64"
	"errors"
	"strings"
	"testing"
)

const nl1URI = "vless://11111111-1111-4111-8111-111111111111@nl1.example.test:443?type=tcp&security=reality&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&fp=firefox&sni=intel.example.test&sid=0123456789abcdef&spx=%2F&flow=xtls-rprx-vision#%F0%9F%87%B3%F0%9F%87%B1%20%D0%9D%D0%B8%D0%B4%D0%B5%D1%80%D0%BB%D0%B0%D0%BD%D0%B4%D1%8B%201"
const nl2URI = "vless://22222222-2222-4222-8222-222222222222@NL2.example.test:8443?security=reality&type=tcp&flow=xtls-rprx-vision&sid=fedcba9876543210&sni=intel.example.test&fp=chrome&pbk=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB&spx=%2F#%F0%9F%87%B3%F0%9F%87%B1%20%D0%9D%D0%B8%D0%B4%D0%B5%D1%80%D0%BB%D0%B0%D0%BD%D0%B4%D1%8B%202"

func TestParseCurrentShapeAndDuplicateCountry(t *testing.T) {
	raw := strings.Join([]string{nl1URI, nl2URI}, "\n")
	encoded := base64.StdEncoding.EncodeToString([]byte(raw))
	got, err := Parse([]byte(encoded), 128)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Nodes) != 2 {
		t.Fatalf("nodes=%+v rejected=%v", got.Nodes, got.Rejected)
	}
	if got.Nodes[0].DisplayName != "🇳🇱 Нидерланды 1" || got.Nodes[0].Country != "Нидерланды" || got.Nodes[1].Host != "nl2.example.test" {
		t.Fatalf("nodes=%+v", got.Nodes)
	}
	if len(got.Nodes[1].Warnings) != 0 {
		t.Fatalf("warnings=%v", got.Nodes[1].Warnings)
	}
	if got.Nodes[0].SpiderX != "/" {
		t.Fatalf("spiderX=%q", got.Nodes[0].SpiderX)
	}
	if !strings.Contains(got.Nodes[1].CanonicalURI, "flow=xtls-rprx-vision&fp=chrome&pbk=") {
		t.Fatalf("query was not canonicalized: %q", got.Nodes[1].CanonicalURI)
	}
}

func TestParseAcceptsRawAndUnpaddedBase64(t *testing.T) {
	for name, payload := range map[string][]byte{
		"raw":       []byte(" \r\n" + nl1URI + "\r\n"),
		"base64raw": []byte(base64.RawStdEncoding.EncodeToString([]byte(nl1URI))),
	} {
		t.Run(name, func(t *testing.T) {
			got, err := Parse(payload, 2)
			if err != nil || len(got.Nodes) != 1 {
				t.Fatalf("got=%+v err=%v", got, err)
			}
		})
	}
}

func TestParseAcceptsCanonicalXrayUUIDOutsideRFCVariantRange(t *testing.T) {
	payload := strings.Replace(nl1URI, "11111111-1111-4111-8111-111111111111", "aaaaaaaa-aaaa-2aaa-eaaa-aaaaaaaaaaaa", 1)
	got, err := Parse([]byte(payload), 1)
	if err != nil || len(got.Nodes) != 1 {
		t.Fatalf("got=%+v err=%v", got, err)
	}
}

func TestParseKeepsValidNodesAndCountsSanitizedRejections(t *testing.T) {
	payload := []byte(strings.Join([]string{
		nl1URI,
		"trojan://secret@example.test:443#bad",
		strings.Replace(nl2URI, "flow=xtls-rprx-vision", "flow=unsupported-secret", 1),
	}, "\n"))
	got, err := Parse(payload, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Nodes) != 1 || got.Rejected["unsupported_scheme"] != 1 || got.Rejected["unsupported_flow"] != 1 {
		t.Fatalf("got=%+v", got)
	}
}

func TestParseRejectsUnsupportedAndNeverLeaksURI(t *testing.T) {
	result, err := Parse([]byte("trojan://secret@example.test:443#bad\n"), 10)
	if !errors.Is(err, ErrNoSupportedNodes) || strings.Contains(err.Error(), "secret") || len(result.Nodes) != 0 {
		t.Fatalf("result=%+v err=%v", result, err)
	}
}

func TestParseRejectsTooManyLinesBeforeReturningNodes(t *testing.T) {
	result, err := Parse([]byte(nl1URI+"\n"+nl2URI), 1)
	if !errors.Is(err, ErrTooManyNodes) || len(result.Nodes) != 0 {
		t.Fatalf("result=%+v err=%v", result, err)
	}
}

func TestParseRejectsMalformedRequiredFieldsWithoutLeakingThem(t *testing.T) {
	mutations := []string{
		strings.Replace(nl1URI, "11111111-1111-4111-8111-111111111111", "not-a-private-uuid", 1),
		strings.Replace(nl1URI, "type=tcp", "type=grpc-secret", 1),
		strings.Replace(nl1URI, "security=reality", "security=tls-secret", 1),
		strings.Replace(nl1URI, "sid=0123456789abcdef", "sid=not-hex-secret", 1),
	}
	for _, payload := range mutations {
		_, err := Parse([]byte(payload), 10)
		if err == nil || strings.Contains(err.Error(), "secret") || strings.Contains(err.Error(), "not-a-private-uuid") {
			t.Fatalf("unsafe error: %v", err)
		}
	}
}
