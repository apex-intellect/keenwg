package makebundle

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

func TestBuildIsDeterministicSortedAndUsesExplicitModes(t *testing.T) {
	input := t.TempDir()
	writeBundleFixture(t, input, "z-config.json", "{}", 0o600)
	writeBundleFixture(t, input, "keenwg-companion", "binary", 0o755)
	writeBundleFixture(t, input, "VERSION", "0.7.0\n", 0o644)
	first := filepath.Join(t.TempDir(), "first.tar.gz")
	second := filepath.Join(t.TempDir(), "second.tar.gz")
	if err := Build(input, first); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(filepath.Join(input, "VERSION"), time.Now(), time.Now().Add(24*time.Hour)); err != nil {
		t.Fatal(err)
	}
	if err := Build(input, second); err != nil {
		t.Fatal(err)
	}
	firstBytes, _ := os.ReadFile(first)
	secondBytes, _ := os.ReadFile(second)
	if !bytes.Equal(firstBytes, secondBytes) {
		t.Fatal("archive changed after source mtime changed")
	}

	entries := readArchiveEntries(t, first)
	want := []archiveEntry{{"VERSION", 0o644}, {"keenwg-companion", 0o755}, {"z-config.json", 0o600}}
	if !reflect.DeepEqual(entries, want) {
		t.Fatalf("entries=%v want=%v", entries, want)
	}
}

type archiveEntry struct {
	name string
	mode int64
}

func readArchiveEntries(t *testing.T, path string) []archiveEntry {
	t.Helper()
	file, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	gz, err := gzip.NewReader(file)
	if err != nil {
		t.Fatal(err)
	}
	defer gz.Close()
	reader := tar.NewReader(gz)
	var result []archiveEntry
	for {
		header, err := reader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		result = append(result, archiveEntry{header.Name, header.Mode})
	}
	return result
}

func writeBundleFixture(t *testing.T, root, name, body string, mode os.FileMode) {
	t.Helper()
	path := filepath.Join(root, name)
	if err := os.WriteFile(path, []byte(body), mode); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(path, mode); err != nil {
		t.Fatal(err)
	}
}
