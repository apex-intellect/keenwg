package catalog

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestSnapshotJSONUsesArraysForEmptyCollections(t *testing.T) {
	store, _ := newCatalogStore(t)
	document, err := store.Snapshot(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	body, err := json.Marshal(document)
	if err != nil {
		t.Fatal(err)
	}
	var payload struct {
		Sources json.RawMessage `json:"sources"`
		Nodes   json.RawMessage `json:"nodes"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		t.Fatal(err)
	}
	if string(payload.Sources) != "[]" || string(payload.Nodes) != "[]" {
		t.Fatalf("catalog collections must be arrays: %s", body)
	}
}

func TestSnapshotJSONUsesArraysForEmptyWarnings(t *testing.T) {
	store, _ := newCatalogStore(t)
	document, err := store.ReplaceAdapterProjection(
		context.Background(),
		1,
		"sync-empty-warnings-0001",
		strings.Repeat("a", 64),
		"xkeen",
		[]Source{{
			ID: "xkeen-subscription", GroupID: "primary", Kind: SourceForeign, Label: "XKeen",
			AdapterID: "xkeen", Status: SourceReady, NodeCount: 1, Foreign: true, AdapterStateVersion: 1,
		}},
		[]Node{{
			ID: "xkeen-node", SourceID: "xkeen-subscription", GroupID: "primary", DisplayName: "Netherlands",
			Protocol: ProtocolVLESS, Host: "vpn.example", Port: 443, Active: true, Testable: true, Activatable: true,
		}},
		RecordedResult{Kind: "sync", Result: "committed"},
	)
	if err != nil {
		t.Fatal(err)
	}
	body, err := json.Marshal(document)
	if err != nil {
		t.Fatal(err)
	}
	var payload struct {
		Sources []struct {
			Warnings json.RawMessage `json:"warnings"`
		} `json:"sources"`
		Nodes []struct {
			Warnings json.RawMessage `json:"warnings"`
		} `json:"nodes"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		t.Fatal(err)
	}
	if len(payload.Sources) != 1 || len(payload.Nodes) != 1 ||
		string(payload.Sources[0].Warnings) != "[]" || string(payload.Nodes[0].Warnings) != "[]" {
		t.Fatalf("catalog warnings must be arrays: %s", body)
	}
}

func TestStorePersistsSourceSecretSeparatelyAndZerosInput(t *testing.T) {
	store, paths := newCatalogStore(t)
	secretText := "https://provider.example/sub/private-subscription-id"
	secret := []byte(secretText)
	document, err := store.CreateSource(context.Background(), 1, "create-source-0001", SourceDraft{
		GroupID: "primary", Kind: SourceSubscription, Label: "Provider", AdapterID: "catalog",
	}, secret)
	if err != nil {
		t.Fatal(err)
	}
	if document.StateVersion != 2 || len(document.Sources) != 1 {
		t.Fatalf("document=%+v", document)
	}
	if !bytes.Equal(secret, make([]byte, len(secret))) {
		t.Fatal("source secret input was not zeroed")
	}
	publicBody, err := os.ReadFile(paths.Catalog)
	if err != nil {
		t.Fatal(err)
	}
	privateBody, err := os.ReadFile(paths.Secrets)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(publicBody), secretText) {
		t.Fatal("public catalog contains source secret")
	}
	if !strings.Contains(string(privateBody), secretText) {
		t.Fatal("private source store is missing source secret")
	}
	if runtime.GOOS != "windows" {
		for _, path := range []string{paths.Catalog, paths.Secrets} {
			info, err := os.Stat(path)
			if err != nil || info.Mode().Perm() != 0o600 {
				t.Fatalf("mode for %s = %v err=%v", path, info.Mode().Perm(), err)
			}
		}
	}
}

func TestStoreRejectsStaleStateAndReplaysSameOperation(t *testing.T) {
	store, _ := newCatalogStore(t)
	first, err := store.CreateGroup(context.Background(), 1, "create-group-0001", "Work")
	if err != nil {
		t.Fatal(err)
	}
	replay, err := store.CreateGroup(context.Background(), 1, "create-group-0001", "Work")
	if err != nil || replay.StateVersion != first.StateVersion || len(replay.Groups) != 2 {
		t.Fatalf("replay=%+v err=%v", replay, err)
	}
	if _, err := store.CreateGroup(context.Background(), 1, "create-group-0002", "Backup"); !errors.Is(err, ErrStaleState) {
		t.Fatalf("stale error=%v", err)
	}
	if _, err := store.CreateGroup(context.Background(), 1, "create-group-0001", "Different"); !errors.Is(err, ErrOperationConflict) {
		t.Fatalf("conflicting replay error=%v", err)
	}
}

func TestStoreCatalogRenameFailureRollsBackPrivateAndMemory(t *testing.T) {
	store, paths := newCatalogStore(t)
	secret := []byte("vless://credential@vpn.example:443")
	store.rename = func(oldPath, newPath string) error {
		if newPath == paths.Catalog {
			return errors.New("injected catalog rename failure")
		}
		return os.Rename(oldPath, newPath)
	}
	if _, err := store.CreateSource(context.Background(), 1, "create-source-0002", SourceDraft{
		GroupID: "primary", Kind: SourceShareLink, Label: "VPN", AdapterID: "catalog",
	}, secret); !errors.Is(err, ErrStorage) {
		t.Fatalf("error=%v", err)
	}
	if snapshot, err := store.Snapshot(context.Background()); err != nil || len(snapshot.Sources) != 0 || snapshot.StateVersion != 1 {
		t.Fatalf("snapshot=%+v err=%v", snapshot, err)
	}
	privateBody, err := os.ReadFile(paths.Secrets)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(privateBody), "credential") {
		t.Fatal("failed transaction left a private source behind")
	}
}

func TestStoreRejectsOversizedSecretAndInvalidProjection(t *testing.T) {
	store, _ := newCatalogStore(t)
	oversized := bytes.Repeat([]byte{'a'}, MaxSourceSecretBytes+1)
	if _, err := store.CreateSource(context.Background(), 1, "create-source-0003", SourceDraft{
		GroupID: "primary", Kind: SourceShareLink, Label: "VPN", AdapterID: "catalog",
	}, oversized); !errors.Is(err, ErrLimit) {
		t.Fatalf("oversized error=%v", err)
	}
	if !bytes.Equal(oversized, make([]byte, len(oversized))) {
		t.Fatal("oversized secret input was not zeroed")
	}
}

func TestStoreReplacesOneAdapterProjectionAndKeepsOldNodesOnWriteFailure(t *testing.T) {
	store, paths := newCatalogStore(t)
	first := foreignProjection("engine", 10, "engine-source", "engine-node")
	document, err := store.ReplaceAdapterProjection(context.Background(), 1, "adapter-sync-0001", requestDigest("first"), "engine", first.Sources, first.Nodes,
		RecordedResult{Kind: "refresh", Result: "committed"})
	if err != nil || len(document.Nodes) != 1 || document.Sources[0].AdapterStateVersion != 10 {
		t.Fatalf("document=%+v err=%v", document, err)
	}
	store.rename = func(oldPath, newPath string) error {
		if newPath == paths.Catalog {
			return errors.New("injected")
		}
		return os.Rename(oldPath, newPath)
	}
	second := foreignProjection("engine", 11, "engine-source", "new-node")
	if _, err := store.ReplaceAdapterProjection(context.Background(), document.StateVersion, "adapter-sync-0002", requestDigest("second"), "engine", second.Sources, second.Nodes,
		RecordedResult{Kind: "refresh", Result: "committed"}); !errors.Is(err, ErrStorage) {
		t.Fatalf("replace error=%v", err)
	}
	after, _ := store.Snapshot(context.Background())
	if len(after.Nodes) != 1 || after.Nodes[0].ID != "engine-node" || after.StateVersion != document.StateVersion {
		t.Fatalf("failed replace changed catalog: %+v", after)
	}
}

func TestStorePersistsNonMutatingOperationResultForReplay(t *testing.T) {
	store, _ := newCatalogStore(t)
	digest := requestDigest("test", "node-a")
	result := RecordedResult{Kind: "test", Result: "committed", NodeID: "node-a", Reachable: true, LatencyMS: 25}
	document, err := store.RecordResult(context.Background(), 1, "test-node-0001", digest, result)
	if err != nil || document.StateVersion != 1 {
		t.Fatalf("document=%+v err=%v", document, err)
	}
	got, found, err := store.LookupResult(context.Background(), "test-node-0001", digest)
	if err != nil || !found || got != result {
		t.Fatalf("result=%+v found=%v err=%v", got, found, err)
	}
	if _, err := store.RecordResult(context.Background(), 1, "test-node-0001", requestDigest("different"), result); !errors.Is(err, ErrOperationConflict) {
		t.Fatalf("conflict=%v", err)
	}
}

func TestOwnedProjectionPersistsPrivatePayloadAtomicallyAndErasesInput(t *testing.T) {
	store, paths := newCatalogStore(t)
	document, err := store.CreateSource(context.Background(), 1, "create-owned-0001", SourceDraft{
		GroupID: "primary", Kind: SourceSubscription, Label: "Personal", AdapterID: "catalog",
	}, []byte("https://provider.example/private"))
	if err != nil {
		t.Fatal(err)
	}
	sourceID := document.Sources[0].ID
	node := Node{ID: "owned-node", SourceID: sourceID, GroupID: "primary", DisplayName: "NL", Protocol: ProtocolVLESS,
		Host: "vpn.example", Port: 443, Testable: true, Activatable: true, Warnings: []string{}}
	payload := []byte("vless://private-native-node@vpn.example:443")
	result := RecordedResult{Kind: "refresh", Result: "committed", ObservedUnix: 100}

	updated, err := store.ReplaceOwnedProjection(context.Background(), document.StateVersion, "refresh-owned-0001", requestDigest("owned"), sourceID, []Node{node}, payload, result)
	if err != nil || len(updated.Nodes) != 1 || updated.Sources[0].Status != SourceReady {
		t.Fatalf("updated=%+v err=%v", updated, err)
	}
	if !bytes.Equal(payload, make([]byte, len(payload))) {
		t.Fatal("private payload was not erased")
	}
	private, err := store.SourceProjection(context.Background(), sourceID)
	if err != nil || string(private) != "vless://private-native-node@vpn.example:443" {
		t.Fatalf("private projection=%q err=%v", private, err)
	}
	store.rename = func(oldPath, newPath string) error {
		if newPath == paths.Catalog {
			return errors.New("injected")
		}
		return os.Rename(oldPath, newPath)
	}
	replacement := node
	replacement.ID = "owned-new"
	replacementPayload := []byte("vless://replacement-private@vpn.example:443")
	if _, err := store.ReplaceOwnedProjection(context.Background(), updated.StateVersion, "refresh-owned-0004", requestDigest("replacement"), sourceID, []Node{replacement}, replacementPayload, result); !errors.Is(err, ErrStorage) {
		t.Fatalf("replace error=%v", err)
	}
	after, _ := store.Snapshot(context.Background())
	private, _ = store.SourceProjection(context.Background(), sourceID)
	if len(after.Nodes) != 1 || after.Nodes[0].ID != "owned-node" || string(private) != "vless://private-native-node@vpn.example:443" {
		t.Fatalf("failed atomic replace changed state: catalog=%+v private=%q", after, private)
	}
}

func TestOwnedActivationMarksOnlyXKeenAndCatalogNodes(t *testing.T) {
	store, _ := newCatalogStore(t)
	xkeen := foreignProjection("xkeen", 4, "xkeen-source", "xkeen-node")
	document, err := store.ReplaceAdapterProjection(context.Background(), 1, "sync-xkeen-0001", requestDigest("xkeen"), "xkeen", xkeen.Sources, xkeen.Nodes, RecordedResult{Kind: "sync", Result: "committed"})
	if err != nil {
		t.Fatal(err)
	}
	document, err = store.CreateSource(context.Background(), document.StateVersion, "create-owned-0002", SourceDraft{GroupID: "primary", Kind: SourceShareLink, Label: "Personal", AdapterID: "catalog"}, []byte("private"))
	if err != nil {
		t.Fatal(err)
	}
	sourceID := document.Sources[1].ID
	owned := Node{ID: "owned-active", SourceID: sourceID, GroupID: "primary", DisplayName: "Owned", Protocol: ProtocolVLESS, Host: "owned.example", Port: 443, Testable: true, Activatable: true, Warnings: []string{}}
	document, err = store.ReplaceOwnedProjection(context.Background(), document.StateVersion, "refresh-owned-0002", requestDigest("refresh"), sourceID, []Node{owned}, []byte("payload"), RecordedResult{Kind: "refresh", Result: "committed"})
	if err != nil {
		t.Fatal(err)
	}

	updated, err := store.CommitOwnedActivation(context.Background(), document.StateVersion, "activate-owned-0002", requestDigest("activate"), "owned-active", RecordedResult{Kind: "activate", Result: "committed", NodeID: "owned-active"})
	if err != nil {
		t.Fatal(err)
	}
	active := []string{}
	for _, node := range updated.Nodes {
		if node.Active {
			active = append(active, node.ID)
		}
	}
	if len(active) != 1 || active[0] != "owned-active" {
		t.Fatalf("active nodes=%v catalog=%+v", active, updated)
	}
}

func foreignProjection(adapterID string, version uint64, sourceID, nodeID string) struct {
	Sources []Source
	Nodes   []Node
} {
	return struct {
		Sources []Source
		Nodes   []Node
	}{
		Sources: []Source{{ID: sourceID, GroupID: "primary", Kind: SourceForeign, Label: adapterID, AdapterID: adapterID,
			Status: SourceReady, NodeCount: 1, Warnings: []string{}, Foreign: true, AdapterStateVersion: version}},
		Nodes: []Node{{ID: nodeID, SourceID: sourceID, GroupID: "primary", DisplayName: nodeID, Protocol: ProtocolVLESS,
			Host: "vpn.example", Port: 443, Testable: true, Activatable: true, Warnings: []string{}}},
	}
}

func newCatalogStore(t *testing.T) (*Store, Paths) {
	t.Helper()
	directory := t.TempDir()
	paths := Paths{
		Catalog: filepath.Join(directory, "catalog.json"),
		Secrets: filepath.Join(directory, "catalog-secrets.json"),
	}
	store, err := NewStore(paths, bytes.NewReader(bytes.Repeat([]byte{7}, 4096)))
	if err != nil {
		t.Fatal(err)
	}
	return store, paths
}
