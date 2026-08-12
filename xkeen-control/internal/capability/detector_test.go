package capability

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"testing"
)

func TestDetectorReportsIndependentCapabilities(t *testing.T) {
	root := t.TempDir()
	writeProbeFile(t, root, "opt/etc/init.d/S05xkeen", "#!/bin/sh\n# Version: 2.1\n", 0o755)

	got, err := NewDetector(root).Detect(context.Background())
	if err != nil {
		t.Fatal(err)
	}

	wantBytes, err := os.ReadFile(filepath.Join("testdata", "xkeen-only.json"))
	if err != nil {
		t.Fatal(err)
	}
	var want []Capability
	if err := json.Unmarshal(wantBytes, &want); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got.Capabilities, want) {
		t.Fatalf("capabilities mismatch\n got: %#v\nwant: %#v", got.Capabilities, want)
	}
	if got.SchemaVersion != 1 || got.StateVersion == 0 {
		t.Fatalf("invalid capability document metadata: %+v", got)
	}
}

func TestDetectorProducesStableSortedDocument(t *testing.T) {
	root := t.TempDir()
	writeProbeFile(t, root, "opt/etc/init.d/S05xkeen", "#!/bin/sh\n# Version: 2.4\n", 0o755)
	writeProbeFile(t, root, "opt/sbin/asc", "binary", 0o755)

	first, err := NewDetector(root).Detect(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	second, err := NewDetector(root).Detect(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if first.StateVersion != second.StateVersion {
		t.Fatalf("state version changed without a state change: %d != %d", first.StateVersion, second.StateVersion)
	}
	ids := make([]string, 0, len(first.Capabilities))
	for _, item := range first.Capabilities {
		ids = append(ids, item.ID)
	}
	if !sort.StringsAreSorted(ids) {
		t.Fatalf("capabilities are not sorted: %v", ids)
	}
}

func TestDetectorDoesNotInferXKeenFromAnUnsupportedInitScript(t *testing.T) {
	root := t.TempDir()
	writeProbeFile(t, root, "opt/etc/init.d/S05xkeen", "#!/bin/sh\n# Version: 1.7\n", 0o755)

	got, err := NewDetector(root).Detect(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	assertCapability(t, got, ConnectionsXKeen, AccessNone, false, "xkeen_unsupported")
	assertCapability(t, got, ConnectionsCatalog, AccessNone, false, "connection_adapter_not_found")
	assertCapability(t, got, ConnectionsSingBox, AccessNone, false, "singbox_not_configured")
	assertCapability(t, got, ConnectionsAWG, AccessNone, false, "awg_not_configured")
	assertCapability(t, got, AccessWireGuard, AccessNone, false, "ndmq_not_found")
}

func TestDetectorHidesCatalogWithoutAnyConnectionAdapter(t *testing.T) {
	got, err := NewDetector(t.TempDir()).Detect(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	assertCapability(t, got, ConnectionsCatalog, AccessNone, false, "connection_adapter_not_found")
}

func TestDetectorReportsConfiguredSingBoxIndependently(t *testing.T) {
	detector := NewDetectorWithPaths(t.TempDir(), Paths{SingBoxConfigured: true})
	got, err := detector.Detect(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	assertCapability(t, got, ConnectionsSingBox, AccessWrite, true, "")
	assertCapability(t, got, ConnectionsXKeen, AccessNone, false, "xkeen_not_found")
	assertCapability(t, got, ConnectionsCatalog, AccessWrite, true, "")
}

func TestDetectorReportsConfiguredAWGManagerIndependently(t *testing.T) {
	detector := NewDetectorWithPaths(t.TempDir(), Paths{AWGManagerConfigured: true})
	got, err := detector.Detect(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	assertCapability(t, got, ConnectionsAWG, AccessWrite, true, "")
	assertCapability(t, got, ConnectionsSingBox, AccessNone, false, "singbox_not_configured")
	assertCapability(t, got, ConnectionsCatalog, AccessWrite, true, "")
}

func TestDetectorFailsClosedWhenConfiguredAWGOpenAPIIsUnsupported(t *testing.T) {
	detector := NewDetectorWithPaths(t.TempDir(), Paths{
		AWGManagerConfigured: true,
		AWGManagerProbe: func(context.Context) (bool, bool, string) {
			return false, false, "awg_openapi_unsupported"
		},
	})
	got, err := detector.Detect(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	assertCapability(t, got, ConnectionsAWG, AccessNone, false, "awg_openapi_unsupported")
}

func TestDetectorKeepsWireGuardWhenXKeenIsMissing(t *testing.T) {
	root := t.TempDir()
	writeProbeFile(t, root, "opt/bin/ndmq", "binary", 0o755)

	got, err := NewDetector(root).Detect(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	assertCapability(t, got, ConnectionsXKeen, AccessNone, false, "xkeen_not_found")
	assertCapability(t, got, AccessWireGuard, AccessWrite, true, "")
	assertCapability(t, got, NetworkHomeDevices, AccessWrite, true, "")
}

func TestDetectorDoesNotUseASCAsWireGuardPrerequisite(t *testing.T) {
	root := t.TempDir()
	writeProbeFile(t, root, "opt/sbin/asc", "binary", 0o755)

	got, err := NewDetector(root).Detect(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	assertCapability(t, got, AccessWireGuard, AccessNone, false, "ndmq_not_found")
	assertCapability(t, got, NetworkHomeDevices, AccessNone, false, "ndmq_not_found")
}

func writeProbeFile(t *testing.T, root, relative, body string, mode os.FileMode) {
	t.Helper()
	path := filepath.Join(root, filepath.FromSlash(relative))
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(body), mode); err != nil {
		t.Fatal(err)
	}
}

func assertCapability(t *testing.T, document Document, id string, access Access, available bool, reason string) {
	t.Helper()
	for _, item := range document.Capabilities {
		if item.ID == id {
			if item.Access != access || item.Available != available || item.Reason != reason {
				t.Fatalf("unexpected %s capability: %+v", id, item)
			}
			return
		}
	}
	t.Fatalf("capability %s not found", id)
}
