package app

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/goldb/keenwg/xkeen-control/internal/auth"
	"github.com/goldb/keenwg/xkeen-control/internal/config"
)

func TestBootstrapFromLegacyCreatesSecureConfigIdentityAndFirstOffer(t *testing.T) {
	root := t.TempDir()
	legacyPath := filepath.Join(root, "legacy.json")
	targetPath := filepath.Join(root, "companion.json")
	requestPath := filepath.Join(root, "request.json")
	writeTestFile(t, legacyPath, legacyConfigJSON(), 0o600)
	writeTestFile(t, requestPath, `{"schema_version":1,"secure_listen_address":"10.8.0.1:18779"}`, 0o600)

	result, err := BootstrapFromLegacy(legacyPath, targetPath, requestPath, root, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	if result.CertificatePin == "" || result.BaseURL != "https://10.8.0.1:18779" {
		t.Fatalf("invalid bootstrap result: %+v", result)
	}
	file, err := os.Open(targetPath)
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.Decode(file)
	_ = file.Close()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.SecureListenAddress != "10.8.0.1:18779" || !cfg.LegacyAPIEnabled || cfg.Token != "0123456789abcdef0123456789abcdef" {
		t.Fatalf("legacy settings were not migrated: %+v", cfg)
	}
	if _, err := os.Stat(rootedPath(root, cfg.TLSCertificatePath)); err != nil {
		t.Fatalf("certificate not created: %v", err)
	}
	if _, err := os.Stat(rootedPath(root, cfg.TLSPrivateKeyPath)); err != nil {
		t.Fatalf("private key not created: %v", err)
	}

	offer, err := CreatePairingOffer(targetPath, root, auth.ScopeOwner, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if offer.BaseURL != result.BaseURL || offer.CertificatePin != result.CertificatePin || offer.Offer.ID == "" || offer.Offer.Secret == "" {
		t.Fatalf("invalid first pairing offer: %+v", offer)
	}
	if persisted, err := os.ReadFile(rootedPath(root, cfg.PairingStorePath)); err != nil {
		t.Fatal(err)
	} else if strings.Contains(string(persisted), offer.Offer.Secret) {
		t.Fatal("pairing store contains plaintext offer secret")
	}
	assertPrivateAppFile(t, targetPath)
}

func TestBootstrapFromLegacyIsCreateOnly(t *testing.T) {
	root := t.TempDir()
	legacyPath := filepath.Join(root, "legacy.json")
	targetPath := filepath.Join(root, "companion.json")
	requestPath := filepath.Join(root, "request.json")
	writeTestFile(t, legacyPath, legacyConfigJSON(), 0o600)
	writeTestFile(t, targetPath, "owned", 0o600)
	writeTestFile(t, requestPath, `{"schema_version":1,"secure_listen_address":"10.8.0.1:18779"}`, 0o600)
	if _, err := BootstrapFromLegacy(legacyPath, targetPath, requestPath, root, time.Now().UTC()); err == nil {
		t.Fatal("existing companion config was overwritten")
	}
	body, err := os.ReadFile(targetPath)
	if err != nil || string(body) != "owned" {
		t.Fatalf("target changed: body=%q err=%v", body, err)
	}
}

func TestRunHTTPServersShutsDownEveryListenerOnCancellation(t *testing.T) {
	listeners := []net.Listener{newLocalListener(t), newLocalListener(t)}
	servers := make([]*http.Server, len(listeners))
	for i := range servers {
		servers[i] = &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusNoContent)
		})}
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- runHTTPServers(ctx, servers, listeners) }()
	for _, listener := range listeners {
		waitForHTTP(t, "http://"+listener.Addr().String())
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("servers did not shut down")
	}
	for _, listener := range listeners {
		connection, err := net.DialTimeout("tcp", listener.Addr().String(), 100*time.Millisecond)
		if err == nil {
			_ = connection.Close()
			t.Fatalf("listener %s still accepts connections", listener.Addr())
		}
	}
}

func TestBootstrapRequestRejectsUnknownFieldsAndWildcardListener(t *testing.T) {
	for name, request := range map[string]string{
		"unknown":  `{"schema_version":1,"secure_listen_address":"10.8.0.1:18779","shell":"reboot"}`,
		"wildcard": `{"schema_version":1,"secure_listen_address":"0.0.0.0:18779"}`,
	} {
		t.Run(name, func(t *testing.T) {
			root := t.TempDir()
			legacyPath := filepath.Join(root, "legacy.json")
			targetPath := filepath.Join(root, "companion.json")
			requestPath := filepath.Join(root, "request.json")
			writeTestFile(t, legacyPath, legacyConfigJSON(), 0o600)
			writeTestFile(t, requestPath, request, 0o600)
			if _, err := BootstrapFromLegacy(legacyPath, targetPath, requestPath, root, time.Now().UTC()); err == nil {
				t.Fatal("unsafe bootstrap request accepted")
			}
		})
	}
}

func newLocalListener(t *testing.T) net.Listener {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	return listener
}

func waitForHTTP(t *testing.T, url string) {
	t.Helper()
	client := &http.Client{Timeout: 100 * time.Millisecond}
	for attempt := 0; attempt < 30; attempt++ {
		response, err := client.Get(url)
		if err == nil {
			_ = response.Body.Close()
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("server %s did not become ready", url)
}

func writeTestFile(t *testing.T, path, body string, mode os.FileMode) {
	t.Helper()
	if err := os.WriteFile(path, []byte(body), mode); err != nil {
		t.Fatal(err)
	}
}

func assertPrivateAppFile(t *testing.T, path string) {
	t.Helper()
	if runtime.GOOS == "windows" {
		return
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("mode=%o want=600", info.Mode().Perm())
	}
}

func legacyConfigJSON() string {
	value := map[string]any{
		"listen_address": "10.8.0.1:18778", "token": "0123456789abcdef0123456789abcdef",
		"subscription_url":        "https://vpn.example.test/sub/private",
		"subscription_cache_path": "/opt/etc/keenwg/xkeen-subscription.json", "state_path": "/opt/etc/keenwg/xkeen-state.json",
		"backup_dir": "/opt/etc/keenwg/backups", "outbounds_path": "/opt/etc/xray/configs/04_outbounds.json",
		"exclude_path": "/opt/etc/xkeen/ip_exclude.lst", "domain_policy_path": "/opt/etc/keenwg/domain-policy.json",
		"domain_policy_backup_path": "/opt/etc/keenwg/domain-policy.json.bak", "routing_path": "/opt/etc/xray/configs/05_routing.json",
		"init_script": "/opt/etc/init.d/S05xkeen", "xray_binary": "/opt/sbin/xray", "asset_dir": "/opt/etc/xray/dat",
		"max_subscription_bytes": 262144, "max_nodes": 128, "allow_private_servers": false,
	}
	body, _ := json.Marshal(value)
	return string(body)
}
