package model

import (
	"bytes"
	"encoding/json"
	"testing"
)

func TestSanitizeNodeExposesOnlyPublicFields(t *testing.T) {
	node := Node{
		ID:           "aabbccddeeff00112233445566778899",
		CanonicalURI: "vless://private-uri",
		DisplayName:  "Нидерланды 1",
		Country:      "Нидерланды",
		Flag:         "🇳🇱",
		Host:         "nl1.example.test",
		Port:         443,
		UUID:         "11111111-1111-4111-8111-111111111111",
		PublicKey:    "private-public-key",
		ShortID:      "0123456789abcdef",
		SNI:          "private-sni.example.test",
		SpiderX:      "/private-spider",
		Fingerprint:  "firefox",
		Transport:    "tcp",
		Security:     "reality",
		Flow:         "xtls-rprx-vision",
		Warnings:     []string{"example_warning"},
	}

	public := SanitizeNode(node, true)
	if !public.Active || public.ID != node.ID || public.DisplayName != node.DisplayName {
		t.Fatalf("unexpected public node: %+v", public)
	}
	body, err := json.Marshal(public)
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{node.CanonicalURI, node.UUID, node.PublicKey, node.ShortID, node.SNI, node.SpiderX} {
		if bytes.Contains(body, []byte(secret)) {
			t.Fatalf("secret leaked in public JSON: %s", body)
		}
	}
}
