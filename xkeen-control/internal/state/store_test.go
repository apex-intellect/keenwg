package state

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/model"
)

func TestSaveSubscriptionReusesRandomIDWithoutDerivingSecrets(t *testing.T) {
	dir := t.TempDir()
	store := New(Paths{
		Subscription: filepath.Join(dir, "subscription.json"),
		State:        filepath.Join(dir, "state.json"),
		BackupDir:    filepath.Join(dir, "backups"),
	}, bytes.NewReader(bytes.Repeat([]byte{0xab}, 64)))
	first := testNode("nl1.example.test", "private-canonical-uri-a")
	saved1, err := store.SaveSubscription([]model.Node{first}, time.Unix(100, 0))
	if err != nil {
		t.Fatal(err)
	}
	saved2, err := store.SaveSubscription([]model.Node{first}, time.Unix(200, 0))
	if err != nil {
		t.Fatal(err)
	}
	if saved1.Nodes[0].ID != saved2.Nodes[0].ID || saved1.Nodes[0].ID != strings.Repeat("ab", 16) {
		t.Fatalf("ids=%q/%q", saved1.Nodes[0].ID, saved2.Nodes[0].ID)
	}
	if first.ID != "" {
		t.Fatal("input node was mutated")
	}
	info, err := os.Stat(filepath.Join(dir, "subscription.json"))
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" && info.Mode().Perm() != 0o600 {
		t.Fatalf("mode=%v", info.Mode().Perm())
	}
}

func TestSaveSubscriptionAssignsDistinctOpaqueIDs(t *testing.T) {
	dir := t.TempDir()
	random := append(bytes.Repeat([]byte{0x11}, 16), bytes.Repeat([]byte{0x22}, 16)...)
	store := New(Paths{Subscription: filepath.Join(dir, "subscription.json"), State: filepath.Join(dir, "state.json")}, bytes.NewReader(random))
	saved, err := store.SaveSubscription([]model.Node{
		testNode("nl1.example.test", "private-a"),
		testNode("de1.example.test", "private-b"),
	}, time.Unix(100, 0))
	if err != nil {
		t.Fatal(err)
	}
	if saved.Nodes[0].ID != strings.Repeat("11", 16) || saved.Nodes[1].ID != strings.Repeat("22", 16) {
		t.Fatalf("ids=%q/%q", saved.Nodes[0].ID, saved.Nodes[1].ID)
	}
}

func TestAtomicRenameFailurePreservesPreviousJSON(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "subscription.json")
	store := New(Paths{Subscription: path, State: filepath.Join(dir, "state.json")}, bytes.NewReader(bytes.Repeat([]byte{0xab}, 64)))
	if _, err := store.SaveSubscription([]model.Node{testNode("nl1.example.test", "private-a")}, time.Unix(100, 0)); err != nil {
		t.Fatal(err)
	}
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	store.rename = func(string, string) error { return errors.New("injected rename failure") }
	if _, err := store.SaveSubscription([]model.Node{testNode("de1.example.test", "private-b")}, time.Unix(200, 0)); !errors.Is(err, ErrStorage) {
		t.Fatalf("err=%v", err)
	}
	after, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(before, after) {
		t.Fatalf("previous file changed: err=%v", err)
	}
}

func TestLoadRejectsUnknownFieldsAndDuplicateNodeIDs(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "subscription.json")
	store := New(Paths{Subscription: path, State: filepath.Join(dir, "state.json")}, bytes.NewReader(nil))
	for name, body := range map[string]string{
		"unknown":   `{"refreshed_at":1,"nodes":[],"private_extra":"secret"}`,
		"duplicate": `{"refreshed_at":1,"nodes":[` + storedNodeJSON(strings.Repeat("ab", 16), "private-a") + `,` + storedNodeJSON(strings.Repeat("ab", 16), "private-b") + `]}`,
	} {
		t.Run(name, func(t *testing.T) {
			if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := store.LoadSubscription(); !errors.Is(err, ErrInvalidState) {
				t.Fatalf("err=%v", err)
			}
		})
	}
}

func TestControllerStateIsMonotonicAndRequiresSnapshotForRunningOperation(t *testing.T) {
	dir := t.TempDir()
	store := New(Paths{Subscription: filepath.Join(dir, "subscription.json"), State: filepath.Join(dir, "state.json")}, bytes.NewReader(nil))
	initial := model.ControllerState{StateVersion: 7}
	if err := store.SaveControllerState(initial); err != nil {
		t.Fatal(err)
	}
	if err := store.SaveControllerState(model.ControllerState{StateVersion: 6}); !errors.Is(err, ErrStaleState) {
		t.Fatalf("rollback version err=%v", err)
	}
	running := model.Operation{IdempotencyKey: "11111111-1111-4111-8111-111111111111", Kind: "select", State: model.OperationRunning, StartedAt: 100}
	if err := store.SaveControllerState(model.ControllerState{StateVersion: 7, Operations: []model.Operation{running}}); !errors.Is(err, ErrInvalidState) {
		t.Fatalf("missing snapshot err=%v", err)
	}
}

func TestBeginAndUpdateOperationKeepOnlyLatestHundredTerminalRecords(t *testing.T) {
	dir := t.TempDir()
	store := New(Paths{Subscription: filepath.Join(dir, "subscription.json"), State: filepath.Join(dir, "state.json")}, bytes.NewReader(nil))
	if err := store.SaveControllerState(model.ControllerState{StateVersion: 1}); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 101; i++ {
		key := operationKey(i)
		op := model.Operation{IdempotencyKey: key, Kind: "refresh", State: model.OperationQueued, StartedAt: int64(i + 1)}
		snapshot := &model.TransactionSnapshot{OperationKey: key, Kind: "refresh", Phase: "queued"}
		if err := store.BeginOperation(op, snapshot); err != nil {
			t.Fatalf("begin %d: %v", i, err)
		}
		finished := int64(i + 2)
		op.State = model.OperationTerminal
		op.Result = model.ResultSuccess
		op.FinishedAt = &finished
		if err := store.UpdateOperation(op, nil); err != nil {
			t.Fatalf("update %d: %v", i, err)
		}
	}
	got, err := store.LoadControllerState()
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Operations) != 100 || got.Operations[0].IdempotencyKey != operationKey(1) || got.InProgress != nil {
		t.Fatalf("operations=%d first=%q in_progress=%+v", len(got.Operations), got.Operations[0].IdempotencyKey, got.InProgress)
	}
}

func testNode(host, canonical string) model.Node {
	return model.Node{
		CanonicalURI: canonical,
		DisplayName:  host,
		Host:         host,
		Port:         443,
		UUID:         "11111111-1111-4111-8111-111111111111",
		PublicKey:    "synthetic-key",
		ShortID:      "0123456789abcdef",
		SNI:          "sni.example.test",
		Fingerprint:  "firefox",
		Transport:    "tcp",
		Security:     "reality",
		Flow:         "xtls-rprx-vision",
	}
}

func storedNodeJSON(id, canonical string) string {
	return `{"id":"` + id + `","canonical_uri":"` + canonical + `","display_name":"node","host":"node.example.test","port":443,"uuid":"11111111-1111-4111-8111-111111111111","public_key":"key","short_id":"01","sni":"sni.example.test","fingerprint":"firefox","transport":"tcp","security":"reality","flow":"xtls-rprx-vision"}`
}

func operationKey(i int) string {
	return "00000000-0000-4000-8000-" + leftPad12(i)
}

func leftPad12(i int) string {
	s := strings.Repeat("0", 12) + strconv.Itoa(i)
	return s[len(s)-12:]
}
