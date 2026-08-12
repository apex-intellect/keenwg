package selfupdate

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"strings"
	"testing"
)

func TestManifestVerifiesCanonicalSignatureAndArchive(t *testing.T) {
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	archive := []byte("reviewed archive")
	sum := sha256.Sum256(archive)
	manifest := Manifest{
		SchemaVersion: 1, Version: "2.2.0", Architecture: "arm64",
		ArchiveSHA256: hex.EncodeToString(sum[:]), ArchiveSize: int64(len(archive)),
		BinarySHA256: strings.Repeat("a", 64), KeyID: "release-2026",
	}
	manifest.Signature = base64.RawStdEncoding.EncodeToString(ed25519.Sign(private, manifest.CanonicalBytes()))
	if err := manifest.Verify(public, "release-2026", sum, int64(len(archive))); err != nil {
		t.Fatal(err)
	}
	manifest.Version = "2.2.1"
	if err := manifest.Verify(public, "release-2026", sum, int64(len(archive))); err == nil {
		t.Fatal("tampered manifest accepted")
	}
}

func TestDecodeManifestRejectsAmbiguityAndInvalidFields(t *testing.T) {
	valid := `{"schema_version":1,"version":"2.2.0","architecture":"arm64","archive_sha256":"` + strings.Repeat("a", 64) + `","archive_size":12,"binary_sha256":"` + strings.Repeat("b", 64) + `","key_id":"release-2026","signature":"` + base64.RawStdEncoding.EncodeToString(make([]byte, ed25519.SignatureSize)) + `"}`
	cases := []string{
		valid + `{}`,
		strings.Replace(valid, `"version":"2.2.0"`, `"version":"2.2.0","version":"2.2.1"`, 1),
		strings.Replace(valid, `"signature":`, `"unknown":1,"signature":`, 1),
		strings.Replace(valid, `"architecture":"arm64"`, `"architecture":"amd64"`, 1),
		strings.Replace(valid, `"version":"2.2.0"`, `"version":"latest"`, 1),
		strings.Replace(valid, strings.Repeat("a", 64), "xyz", 1),
		strings.Replace(valid, `"archive_size":12`, `"archive_size":0`, 1),
	}
	for _, raw := range cases {
		if _, err := DecodeManifest(bytes.NewBufferString(raw)); err == nil {
			t.Fatalf("invalid manifest accepted: %.80s", raw)
		}
	}
}

func TestVerifyRejectsWrongArchiveKeyAndSignature(t *testing.T) {
	public, private, _ := ed25519.GenerateKey(rand.Reader)
	archive := []byte("archive")
	sum := sha256.Sum256(archive)
	manifest := Manifest{1, "2.2.0", "arm64", hex.EncodeToString(sum[:]), int64(len(archive)), strings.Repeat("b", 64), "release-2026", ""}
	manifest.Signature = base64.RawStdEncoding.EncodeToString(ed25519.Sign(private, manifest.CanonicalBytes()))
	wrong := sha256.Sum256([]byte("other"))
	for _, check := range []func() error{
		func() error { return manifest.Verify(public, "other-key", sum, int64(len(archive))) },
		func() error { return manifest.Verify(public, "release-2026", wrong, int64(len(archive))) },
		func() error { return manifest.Verify(public, "release-2026", sum, int64(len(archive))+1) },
	} {
		if err := check(); err == nil {
			t.Fatal("invalid verification input accepted")
		}
	}
	manifest.Signature = "broken"
	if err := manifest.Verify(public, "release-2026", sum, int64(len(archive))); err == nil {
		t.Fatal("bad signature accepted")
	}
}

func TestCanonicalBytesAreStableAndExcludeSignature(t *testing.T) {
	m := Manifest{1, "2.2.0", "arm64", strings.Repeat("a", 64), 123, strings.Repeat("b", 64), "release-2026", "first"}
	first := m.CanonicalBytes()
	m.Signature = "second"
	if !bytes.Equal(first, m.CanonicalBytes()) {
		t.Fatal("signature changed canonical bytes")
	}
	want := "keenwg-update-v1\n1\n2.2.0\narm64\n" + strings.Repeat("a", 64) + "\n123\n" + strings.Repeat("b", 64) + "\nrelease-2026\n"
	if string(first) != want {
		t.Fatalf("unexpected canonical form: %q", first)
	}
}
