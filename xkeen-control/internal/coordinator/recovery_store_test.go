package coordinator

import (
	"context"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestFileRecoveryStorePersistsRootOnlyAndReturnsIndependentCopies(t *testing.T) {
	path := filepath.Join(t.TempDir(), "recovery.json")
	store := NewFileRecoveryStore(path)
	record := RecoveryRecord{SchemaVersion: 1, PlanID: "plan-file-0001", Entries: []RecoveryEntry{{Module: "routes", Before: []byte("secret-before"), Staged: []byte("secret-after")}}}
	if err := store.Save(context.Background(), record); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" && info.Mode().Perm() != 0o600 {
		t.Fatalf("mode=%o", info.Mode().Perm())
	}
	record.Entries[0].Before[0] = 'X'
	loaded, err := store.Load(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if string(loaded.Entries[0].Before) != "secret-before" {
		t.Fatalf("loaded=%q", loaded.Entries[0].Before)
	}
	loaded.Entries[0].Before[0] = 'Y'
	again, err := store.Load(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if string(again.Entries[0].Before) != "secret-before" {
		t.Fatalf("again=%q", again.Entries[0].Before)
	}
	if err := store.Delete(context.Background()); err != nil {
		t.Fatal(err)
	}
	if loaded, err := store.Load(context.Background()); err != nil || loaded != nil {
		t.Fatalf("after delete=%+v err=%v", loaded, err)
	}
}

func TestFileRecoveryStoreRejectsCorruptOversizedAndSymlinkTargets(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "recovery.json")
	store := NewFileRecoveryStore(path)
	if err := os.WriteFile(path, []byte(`{"schema_version":1,"plan_id":"bad"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Load(context.Background()); err == nil {
		t.Fatal("corrupt recovery accepted")
	}
	if err := os.WriteFile(path, make([]byte, maxRecoveryFileBytes+1), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Load(context.Background()); err == nil {
		t.Fatal("oversized recovery accepted")
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(directory, "target")
	if err := os.WriteFile(target, []byte("keep"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, path); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	record := RecoveryRecord{SchemaVersion: 1, PlanID: "plan-file-0002", Entries: []RecoveryEntry{{Module: "routes", Before: []byte("before"), Staged: []byte("after")}}}
	if err := store.Save(context.Background(), record); err == nil {
		t.Fatal("symlink target accepted")
	}
	body, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(body) != "keep" {
		t.Fatalf("target overwritten: %q", body)
	}
}
