package app

import (
	"context"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/config"
)

func TestBootstrapNativeCreatesSecureOnlyConfig(t *testing.T) {
	root := t.TempDir()
	targetPath := filepath.Join(root, "companion.json")
	requestPath := filepath.Join(root, "request.json")
	writeTestFile(t, requestPath, `{"schema_version":1,"secure_listen_address":"10.8.0.1:18779"}`, 0o600)

	result, err := BootstrapNative(targetPath, requestPath, root, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	if result.BaseURL != "https://10.8.0.1:18779" || result.CertificatePin == "" {
		t.Fatalf("invalid bootstrap result: %+v", result)
	}
	body, err := os.ReadFile(targetPath)
	if err != nil {
		t.Fatal(err)
	}
	for _, forbidden := range []string{`"listen_address"`, `"token"`, `"legacy_api_enabled"`} {
		if strings.Contains(string(body), forbidden) {
			t.Fatalf("native config contains obsolete field %q: %s", forbidden, body)
		}
	}
	cfg, err := config.Decode(strings.NewReader(string(body)))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.SchemaVersion != 2 || cfg.SecureListenAddress != "10.8.0.1:18779" || cfg.SubscriptionURL != "" {
		t.Fatalf("unexpected native config: %+v", cfg)
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

func TestCreatePairingOfferAllowsVerifiedSSHRecoveryWhenOwnerAlreadyExists(t *testing.T) {
	root := t.TempDir()
	targetPath := filepath.Join(root, "companion.json")
	requestPath := filepath.Join(root, "request.json")
	writeTestFile(t, requestPath, `{"schema_version":1,"secure_listen_address":"10.8.0.1:18779"}`, 0o600)
	if _, err := BootstrapNative(targetPath, requestPath, root, time.Now().UTC()); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadConfig(targetPath)
	if err != nil {
		t.Fatal(err)
	}
	first, err := CreatePairingOffer(targetPath, root, auth.ScopeOwner, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	store, err := auth.NewFileStore(rootedPath(root, cfg.DeviceStorePath), rootedPath(root, cfg.PairingStorePath))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Exchange(context.Background(), first.Offer.ID, first.Offer.Secret, "Existing owner"); err != nil {
		t.Fatal(err)
	}

	recovery, err := CreatePairingOffer(targetPath, root, auth.ScopeOwner, 5*time.Minute)
	if err != nil {
		t.Fatalf("verified SSH recovery offer: %v", err)
	}
	if recovery.Offer.ID == "" || recovery.Offer.Secret == "" || recovery.Offer.ID == first.Offer.ID {
		t.Fatalf("invalid recovery offer: %+v", recovery)
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
			targetPath := filepath.Join(root, "companion.json")
			requestPath := filepath.Join(root, "request.json")
			writeTestFile(t, requestPath, request, 0o600)
			if _, err := BootstrapNative(targetPath, requestPath, root, time.Now().UTC()); err == nil {
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
