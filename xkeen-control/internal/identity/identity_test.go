package identity

import (
	"crypto/x509"
	"encoding/pem"
	"errors"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

func TestEnsureCreatesStablePinnedIdentityWithIPSANs(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "identity", "certificate.pem")
	keyPath := filepath.Join(dir, "identity", "private-key.pem")
	now := time.Now().UTC().Truncate(time.Second)
	addresses := []net.IP{net.ParseIP("192.168.1.1"), net.ParseIP("10.8.0.1")}

	first, err := Ensure(certPath, keyPath, addresses, now)
	if err != nil {
		t.Fatal(err)
	}
	second, err := Ensure(certPath, keyPath, []net.IP{net.ParseIP("192.168.1.1")}, now.Add(time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if first.SPKIPin == "" || first.SPKIPin != second.SPKIPin {
		t.Fatalf("identity pin changed: %q != %q", first.SPKIPin, second.SPKIPin)
	}
	certificate := readCertificate(t, certPath)
	for _, address := range addresses {
		if err := certificate.VerifyHostname(address.String()); err != nil {
			t.Fatalf("certificate does not contain IP SAN %s: %v", address, err)
		}
	}
	assertPrivateMode(t, certPath)
	assertPrivateMode(t, keyPath)
}

func TestLoadRejectsLoosePrivateKeyPermissions(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows does not expose POSIX group/other mode bits")
	}
	dir := t.TempDir()
	certPath := filepath.Join(dir, "certificate.pem")
	keyPath := filepath.Join(dir, "private-key.pem")
	if _, err := Ensure(certPath, keyPath, []net.IP{net.ParseIP("192.168.1.1")}, time.Now().UTC()); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(keyPath, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := Load(certPath, keyPath); !errors.Is(err, ErrUnsafePermissions) {
		t.Fatalf("Load error = %v, want ErrUnsafePermissions", err)
	}
}

func TestEnsureRejectsSymlinkIdentityPath(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("creating symlinks requires an elevated Windows token")
	}
	dir := t.TempDir()
	outside := filepath.Join(dir, "outside.pem")
	if err := os.WriteFile(outside, []byte("outside"), 0o600); err != nil {
		t.Fatal(err)
	}
	certPath := filepath.Join(dir, "certificate.pem")
	if err := os.Symlink(outside, certPath); err != nil {
		t.Fatal(err)
	}
	if _, err := Ensure(certPath, filepath.Join(dir, "private-key.pem"), []net.IP{net.ParseIP("192.168.1.1")}, time.Now().UTC()); !errors.Is(err, ErrUnsafePath) {
		t.Fatalf("Ensure error = %v, want ErrUnsafePath", err)
	}
}

func TestEnsureRejectsIncompleteIdentity(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "certificate.pem")
	keyPath := filepath.Join(dir, "private-key.pem")
	if err := os.WriteFile(certPath, []byte("partial"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Ensure(certPath, keyPath, []net.IP{net.ParseIP("192.168.1.1")}, time.Now().UTC()); !errors.Is(err, ErrIdentityCorrupt) {
		t.Fatalf("Ensure error = %v, want ErrIdentityCorrupt", err)
	}
}

func readCertificate(t *testing.T, path string) *x509.Certificate {
	t.Helper()
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	block, _ := pem.Decode(body)
	if block == nil || block.Type != "CERTIFICATE" {
		t.Fatal("certificate PEM missing")
	}
	certificate, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatal(err)
	}
	return certificate
}

func assertPrivateMode(t *testing.T, path string) {
	t.Helper()
	if runtime.GOOS == "windows" {
		return
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if got := info.Mode().Perm(); got != 0o600 {
		t.Fatalf("mode for %s = %o, want 600", path, got)
	}
}
