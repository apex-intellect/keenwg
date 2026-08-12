package main

import (
	"crypto/ed25519"
	"encoding/base64"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/selfupdate"
)

func TestGenerateAndSignRoundTrip(t *testing.T) {
	dir := t.TempDir()
	privatePath := filepath.Join(dir, "seed.b64")
	publicPath := filepath.Join(dir, "public.txt")
	if err := run([]string{"-generate-key", "-private-key", privatePath, "-public-key", publicPath, "-key-id", "release-test"}); err != nil {
		t.Fatal(err)
	}
	seedText, err := os.ReadFile(privatePath)
	if err != nil {
		t.Fatal(err)
	}
	seed, err := base64.RawStdEncoding.DecodeString(strings.TrimSpace(string(seedText)))
	if err != nil || len(seed) != ed25519.SeedSize {
		t.Fatal("invalid generated seed")
	}
	archivePath := filepath.Join(dir, "bundle.tgz")
	if err := os.WriteFile(archivePath, []byte("reviewed archive"), 0o600); err != nil {
		t.Fatal(err)
	}
	manifestPath := filepath.Join(dir, "manifest.json")
	unsigned := `{"schema_version":1,"version":"2.2.0","architecture":"arm64","archive_sha256":"","archive_size":0,"binary_sha256":"` + strings.Repeat("a", 64) + `","key_id":"release-test","signature":""}`
	if err := os.WriteFile(manifestPath, []byte(unsigned), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := run([]string{"-manifest", manifestPath, "-archive", archivePath, "-private-key", privatePath}); err != nil {
		t.Fatal(err)
	}
	file, err := os.Open(manifestPath)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	manifest, err := selfupdate.DecodeManifest(file)
	if err != nil {
		t.Fatal(err)
	}
	trusted, err := selfupdate.ReadTrustedPublicKey(publicPath)
	if err != nil {
		t.Fatal(err)
	}
	if manifest.KeyID != trusted.KeyID || manifest.Signature == "" {
		t.Fatal("manifest was not signed")
	}
}

func TestGenerationRefusesOverwrite(t *testing.T) {
	dir := t.TempDir()
	privatePath := filepath.Join(dir, "seed.b64")
	publicPath := filepath.Join(dir, "public.txt")
	args := []string{"-generate-key", "-private-key", privatePath, "-public-key", publicPath, "-key-id", "release-test"}
	if err := run(args); err != nil {
		t.Fatal(err)
	}
	if err := run(args); err == nil {
		t.Fatal("existing keys overwritten")
	}
}
