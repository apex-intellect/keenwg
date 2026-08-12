package selfupdate

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestStagerAcceptsSignedUpgradeAndWritesPrivateRequest(t *testing.T) {
	fixture := newStageFixture(t, "2.2.0")
	accepted, err := fixture.stager.Stage(bytes.NewReader(fixture.envelope))
	if err != nil {
		t.Fatal(err)
	}
	if accepted.TargetVersion != "2.2.0" || len(accepted.OperationID) != 32 {
		t.Fatalf("unexpected acceptance: %+v", accepted)
	}
	requestPath := filepath.Join(fixture.dir, "pending.json")
	info, err := os.Stat(requestPath)
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" && info.Mode().Perm() != 0o600 {
		t.Fatalf("request mode = %o", info.Mode().Perm())
	}
	request, err := ReadPendingRequest(requestPath, fixture.dir)
	if err != nil {
		t.Fatal(err)
	}
	if request.OperationID != accepted.OperationID || request.TargetVersion != "2.2.0" {
		t.Fatal("request mismatch")
	}
}

func TestStagerRejectsTamperDowngradeAndSameBuild(t *testing.T) {
	for name, mutate := range map[string]func(*stageFixture){
		"tampered":  func(f *stageFixture) { f.envelope[len(f.envelope)-1] ^= 1 },
		"downgrade": func(*stageFixture) {},
		"same-build": func(f *stageFixture) {
			f.stager.currentVersion = "2.2.0"
			f.stager.currentBinarySHA256 = f.manifest.BinarySHA256
		},
	} {
		t.Run(name, func(t *testing.T) {
			version := "2.2.0"
			if name == "downgrade" {
				version = "2.1.0"
			}
			fixture := newStageFixture(t, version)
			if name == "downgrade" {
				fixture.stager.currentVersion = "2.2.0"
			}
			mutate(fixture)
			if _, err := fixture.stager.Stage(bytes.NewReader(fixture.envelope)); err == nil {
				t.Fatal("invalid update accepted")
			}
			assertNoPendingArtifacts(t, fixture.dir)
		})
	}
}

func TestStagerAllowsSignedSameVersionRepairWithDifferentBinary(t *testing.T) {
	fixture := newStageFixture(t, "2.2.0")
	fixture.stager.currentVersion = "2.2.0"
	fixture.stager.currentBinarySHA256 = strings.Repeat("f", 64)
	if _, err := fixture.stager.Stage(bytes.NewReader(fixture.envelope)); err != nil {
		t.Fatal(err)
	}
}

func TestStagerRejectsUnsafeOrIncompleteBundlesAndCleansUpload(t *testing.T) {
	cases := []map[string][]byte{
		bundleFiles("2.2.0", map[string][]byte{"../escape": []byte("bad")}),
		bundleFiles("2.2.0", map[string][]byte{"keenwg-updater": nil}),
		bundleFiles("2.2.0", map[string][]byte{"install-companion.sh": nil}),
		bundleFiles("2.2.0", map[string][]byte{"VERSION": []byte("9.9.9\n")}),
	}
	for index, files := range cases {
		t.Run(string(rune('a'+index)), func(t *testing.T) {
			fixture := newStageFixtureWithFiles(t, "2.2.0", files)
			if _, err := fixture.stager.Stage(bytes.NewReader(fixture.envelope)); err == nil {
				t.Fatal("unsafe bundle accepted")
			}
			assertNoPendingArtifacts(t, fixture.dir)
		})
	}
}

func TestStagerRejectsTruncatedExtraAndOversizedEnvelope(t *testing.T) {
	fixture := newStageFixture(t, "2.2.0")
	for _, envelope := range [][]byte{
		fixture.envelope[:len(fixture.envelope)-1],
		append(append([]byte{}, fixture.envelope...), 0),
		append([]byte{0, 0, 0x40, 1}, bytes.Repeat([]byte{'x'}, MaximumManifestBytes+1)...),
	} {
		if _, err := fixture.stager.Stage(bytes.NewReader(envelope)); err == nil {
			t.Fatal("invalid envelope accepted")
		}
		assertNoPendingArtifacts(t, fixture.dir)
	}
}

type stageFixture struct {
	dir      string
	stager   *Stager
	envelope []byte
	manifest Manifest
}

func newStageFixture(t *testing.T, version string) *stageFixture {
	t.Helper()
	return newStageFixtureWithFiles(t, version, bundleFiles(version, nil))
}

func newStageFixtureWithFiles(t *testing.T, version string, files map[string][]byte) *stageFixture {
	t.Helper()
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	archive := makeArchive(t, files)
	archiveHash := sha256.Sum256(archive)
	binaryHash := sha256.Sum256(files["keenwg-companion"])
	manifest := Manifest{1, version, "arm64", hex.EncodeToString(archiveHash[:]), int64(len(archive)), hex.EncodeToString(binaryHash[:]), "release-test", ""}
	manifest.Signature = base64.RawStdEncoding.EncodeToString(ed25519.Sign(private, manifest.CanonicalBytes()))
	manifestJSON, err := json.Marshal(manifest)
	if err != nil {
		t.Fatal(err)
	}
	envelope := make([]byte, 4+len(manifestJSON)+len(archive))
	binary.BigEndian.PutUint32(envelope[:4], uint32(len(manifestJSON)))
	copy(envelope[4:], manifestJSON)
	copy(envelope[4+len(manifestJSON):], archive)
	dir := t.TempDir()
	return &stageFixture{dir: dir, stager: NewStager(StagerConfig{
		UpdateDirectory: dir, CurrentVersion: "2.1.0", CurrentBinarySHA256: strings.Repeat("0", 64),
		PublicKey: public, KeyID: "release-test", Random: rand.Reader,
	}), envelope: envelope, manifest: manifest}
}

func bundleFiles(version string, overrides map[string][]byte) map[string][]byte {
	files := map[string][]byte{
		"keenwg-companion": []byte("companion-binary"), "keenwg-updater": []byte("updater-binary"),
		"VERSION": []byte(version + "\n"), "install-companion.sh": []byte("#!/bin/sh\nexit 0\n"),
		"S96keenwg-companion": []byte("#!/bin/sh\nexit 0\n"), "uninstall-companion.sh": []byte("#!/bin/sh\nexit 0\n"),
		"cleanup-obsolete-controller.sh": []byte("#!/bin/sh\nexit 0\n"), "companion.config.example.json": []byte("{}\n"),
	}
	for name, body := range overrides {
		if body == nil {
			delete(files, name)
		} else {
			files[name] = body
		}
	}
	var sums strings.Builder
	for name, body := range files {
		sum := sha256.Sum256(body)
		sums.WriteString(hex.EncodeToString(sum[:]) + "  " + name + "\n")
	}
	files["SHA256SUMS"] = []byte(sums.String())
	return files
}

func makeArchive(t *testing.T, files map[string][]byte) []byte {
	t.Helper()
	var output bytes.Buffer
	gz := gzip.NewWriter(&output)
	tw := tar.NewWriter(gz)
	for name, body := range files {
		if err := tw.WriteHeader(&tar.Header{Name: name, Mode: 0o755, Size: int64(len(body)), Typeflag: tar.TypeReg}); err != nil {
			t.Fatal(err)
		}
		if _, err := tw.Write(body); err != nil {
			t.Fatal(err)
		}
	}
	if err := tw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := gz.Close(); err != nil {
		t.Fatal(err)
	}
	return output.Bytes()
}

func assertNoPendingArtifacts(t *testing.T, dir string) {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), ".upload-") || strings.HasPrefix(entry.Name(), "pending-") || entry.Name() == "pending.json" {
			t.Fatalf("staging artifact remains: %s", entry.Name())
		}
	}
}
