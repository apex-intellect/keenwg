package subscriptionconfig

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestStoreMigratesLegacyURLIntoPrivateFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "subscription-source.json")
	store, err := New(path, "https://vpn.example.test/sub/private")
	if err != nil {
		t.Fatal(err)
	}
	if !store.Configured() {
		t.Fatal("configured=false")
	}
	if got, ok := store.Current(); !ok || got != "https://vpn.example.test/sub/private" {
		t.Fatalf("current=%q configured=%t", got, ok)
	}
	if runtime.GOOS != "windows" {
		info, statErr := os.Stat(path)
		if statErr != nil || info.Mode().Perm() != 0o600 {
			t.Fatalf("mode=%v err=%v", info.Mode(), statErr)
		}
	}
}

func TestStoreRejectsInvalidURLAndPreservesPreviousValue(t *testing.T) {
	path := filepath.Join(t.TempDir(), "subscription-source.json")
	store, err := New(path, "https://vpn.example.test/sub/one")
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Replace("http://vpn.example.test/sub/two"); !errors.Is(err, ErrInvalid) {
		t.Fatalf("invalid URL error=%v", err)
	}
	if got, _ := store.Current(); got != "https://vpn.example.test/sub/one" {
		t.Fatalf("current=%q", got)
	}
	reloaded, err := New(path, "")
	if err != nil {
		t.Fatal(err)
	}
	if got, _ := reloaded.Current(); got != "https://vpn.example.test/sub/one" {
		t.Fatalf("persisted=%q", got)
	}
}

func TestStoreWithoutLegacyURLStartsUnconfiguredWithoutCreatingSecretFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "subscription-source.json")
	store, err := New(path, "")
	if err != nil {
		t.Fatal(err)
	}
	if store.Configured() {
		t.Fatal("empty store is configured")
	}
	if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("empty store created a file: %v", err)
	}
}

func TestStoreRejectsUnsafeOrNonStrictExistingFile(t *testing.T) {
	for name, body := range map[string]string{
		"unknown field": `{"schema_version":1,"subscription_url":"https://vpn.example.test/sub/one","debug":true}`,
		"extra JSON":    `{"schema_version":1,"subscription_url":"https://vpn.example.test/sub/one"}{}`,
		"wrong schema":  `{"schema_version":2,"subscription_url":"https://vpn.example.test/sub/one"}`,
		"invalid URL":   `{"schema_version":1,"subscription_url":"http://vpn.example.test/sub/one"}`,
		"oversize":      strings.Repeat("x", maxFileBytes+1),
	} {
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "subscription-source.json")
			if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := New(path, ""); !errors.Is(err, ErrStorage) {
				t.Fatalf("unsafe file error=%v", err)
			}
		})
	}
}

func TestStoreRejectsSymlinkTarget(t *testing.T) {
	directory := t.TempDir()
	realPath := filepath.Join(directory, "real.json")
	linkPath := filepath.Join(directory, "subscription-source.json")
	if err := os.WriteFile(realPath, []byte(`{"schema_version":1,"subscription_url":"https://vpn.example.test/sub/one"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(realPath, linkPath); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	if _, err := New(linkPath, ""); !errors.Is(err, ErrStorage) {
		t.Fatalf("symlink error=%v", err)
	}
}
