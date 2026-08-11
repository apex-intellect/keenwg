package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCommandPrintsCompanionVersion(t *testing.T) {
	previousVersion := version
	version = "2.0.0"
	t.Cleanup(func() { version = previousVersion })
	var output bytes.Buffer
	if err := command([]string{"-version"}, &output, ""); err != nil {
		t.Fatal(err)
	}
	if output.String() != "keenwg-companion 2.0.0\n" {
		t.Fatalf("output=%q", output.String())
	}
}

func TestCommandBootstrapsAndPrintsOneFlatPairingObject(t *testing.T) {
	root := t.TempDir()
	targetPath := filepath.Join(root, "companion.json")
	requestPath := filepath.Join(root, "request.json")
	writeCommandFixture(t, requestPath, `{"schema_version":1,"secure_listen_address":"10.8.0.1:18779"}`)
	if err := command([]string{"-config", targetPath, "-bootstrap-request", requestPath}, &bytes.Buffer{}, root); err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	if err := command([]string{"-config", targetPath, "-create-pairing-offer", "owner"}, &output, root); err != nil {
		t.Fatal(err)
	}
	if strings.Count(output.String(), "\n") != 1 {
		t.Fatalf("pairing output must be exactly one JSON line: %q", output.String())
	}
	var response struct {
		BaseURL        string `json:"base_url"`
		CertificatePin string `json:"certificate_pin"`
		OfferID        string `json:"offer_id"`
		Secret         string `json:"secret"`
		ExpiresAt      string `json:"expires_at"`
	}
	if err := json.Unmarshal(output.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.BaseURL != "https://10.8.0.1:18779" || response.CertificatePin == "" || response.OfferID == "" || response.Secret == "" || response.ExpiresAt == "" {
		t.Fatalf("invalid pairing response: %+v", response)
	}
	if bytes.Contains(output.Bytes(), []byte(`"offer":`)) {
		t.Fatalf("pairing response is unexpectedly nested: %s", output.Bytes())
	}
}

func TestCommandBootstrapsWithoutLegacyController(t *testing.T) {
	root := t.TempDir()
	targetPath := filepath.Join(root, "companion.json")
	requestPath := filepath.Join(root, "request.json")
	writeCommandFixture(t, requestPath, `{"schema_version":1,"secure_listen_address":"10.8.0.1:18779"}`)

	if err := command([]string{"-config", targetPath, "-bootstrap-request", requestPath}, &bytes.Buffer{}, root); err != nil {
		t.Fatal(err)
	}
	body, err := os.ReadFile(targetPath)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(body, []byte(`"schema_version": 2`)) || bytes.Contains(body, []byte(`"listen_address"`)) {
		t.Fatalf("unexpected native config: %s", body)
	}
}

func TestCommandUpgradesExistingV1Config(t *testing.T) {
	root := t.TempDir()
	targetPath := filepath.Join(root, "companion.json")
	previous := strings.Replace(companionV1Config(), "\n}", ",\n  \"secure_listen_address\":\"192.168.1.1:18779\",\n  \"legacy_api_enabled\":true\n}", 1)
	writeCommandFixture(t, targetPath, previous)

	if err := command([]string{"-config", targetPath, "-upgrade-config"}, &bytes.Buffer{}, root); err != nil {
		t.Fatal(err)
	}
	body, err := os.ReadFile(targetPath)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(body, []byte(`"schema_version": 2`)) || bytes.Contains(body, []byte(`"legacy_api_enabled"`)) {
		t.Fatalf("unexpected upgraded config: %s", body)
	}
}

func TestCommandRejectsConflictingAndIncompleteModes(t *testing.T) {
	for _, arguments := range [][]string{
		{"-version", "-check"},
		{"-bootstrap-from", "/tmp/legacy.json"},
		{"-bootstrap-request", "/tmp/request.json", "-upgrade-config"},
		{"-upgrade-config", "-check"},
		{"-create-pairing-offer", "operator"},
		{"unexpected"},
	} {
		if err := command(arguments, &bytes.Buffer{}, ""); err == nil {
			t.Fatalf("arguments accepted: %v", arguments)
		}
	}
}

func writeCommandFixture(t *testing.T, path, body string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
}

func companionV1Config() string {
	return `{
  "listen_address":"10.8.0.1:18778",
  "token":"0123456789abcdef0123456789abcdef",
  "subscription_url":"https://vpn.example.test/sub/private",
  "subscription_cache_path":"/opt/etc/keenwg/xkeen-subscription.json",
  "state_path":"/opt/etc/keenwg/xkeen-state.json",
  "backup_dir":"/opt/etc/keenwg/backups",
  "outbounds_path":"/opt/etc/xray/configs/04_outbounds.json",
  "exclude_path":"/opt/etc/xkeen/ip_exclude.lst",
  "domain_policy_path":"/opt/etc/keenwg/domain-policy.json",
  "domain_policy_backup_path":"/opt/etc/keenwg/domain-policy.json.bak",
  "routing_path":"/opt/etc/xray/configs/05_routing.json",
  "init_script":"/opt/etc/init.d/S05xkeen",
  "xray_binary":"/opt/sbin/xray",
  "asset_dir":"/opt/etc/xray/dat",
  "max_subscription_bytes":262144,
  "max_nodes":128
}`
}
