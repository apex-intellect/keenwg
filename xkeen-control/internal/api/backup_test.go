package api

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/goldb/keenwg/xkeen-control/internal/auth"
	"github.com/goldb/keenwg/xkeen-control/internal/backup"
)

func TestBackupCreatePreviewAndExactApplyRequireOwner(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	owner := pairDevice(t, devices, auth.ScopeOwner, "Owner")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	manager := &fakeBackupManager{preview: BackupPreview{SchemaVersion: 1, PlanID: "backup-plan-1", SourceVersion: "0.9.0", Entries: []BackupPreviewEntry{{ID: "routes", Bytes: 12}}}}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithBackup(manager))

	forbidden := httptest.NewRecorder()
	secure.ServeHTTP(forbidden, catalogRequest(http.MethodPost, "/v1/backup", viewer.Token, `{"passphrase":"long-secret"}`))
	if forbidden.Code != http.StatusForbidden {
		t.Fatalf("forbidden=%d", forbidden.Code)
	}
	created := httptest.NewRecorder()
	secure.ServeHTTP(created, catalogRequest(http.MethodPost, "/v1/backup", owner.Token, `{"passphrase":"long-secret"}`))
	if created.Code != http.StatusOK || !bytes.Contains(created.Body.Bytes(), []byte(`"archive":"YXJjaGl2ZQ=="`)) {
		t.Fatalf("create=%d %s", created.Code, created.Body.Bytes())
	}
	preview := httptest.NewRecorder()
	secure.ServeHTTP(preview, catalogRequest(http.MethodPost, "/v1/backup/preview", owner.Token, `{"archive":"YXJjaGl2ZQ==","passphrase":"long-secret"}`))
	if preview.Code != http.StatusOK || bytes.Contains(preview.Body.Bytes(), []byte("secret-data")) {
		t.Fatalf("preview=%d %s", preview.Code, preview.Body.Bytes())
	}
	apply := httptest.NewRecorder()
	secure.ServeHTTP(apply, catalogRequest(http.MethodPost, "/v1/backup/apply", owner.Token, `{"archive":"YXJjaGl2ZQ==","passphrase":"long-secret","reviewed_plan_id":"backup-plan-1"}`))
	if apply.Code != http.StatusOK || manager.reviewed != "backup-plan-1" || !bytes.Contains(apply.Body.Bytes(), []byte(`"skipped_foreign":[]`)) || bytes.Contains(apply.Body.Bytes(), []byte(`"SkippedForeign"`)) {
		t.Fatalf("apply=%d reviewed=%q", apply.Code, manager.reviewed)
	}
}

type fakeBackupManager struct {
	preview  BackupPreview
	reviewed string
}

func (f *fakeBackupManager) Create(context.Context, []byte) ([]byte, BackupPreview, error) {
	return []byte("archive"), f.preview, nil
}
func (f *fakeBackupManager) Preview(context.Context, []byte, []byte) (BackupPreview, error) {
	return f.preview, nil
}
func (f *fakeBackupManager) Apply(_ context.Context, _ []byte, _ []byte, reviewed string) (backup.ApplyResult, error) {
	f.reviewed = reviewed
	return backup.ApplyResult{Applied: []string{"routes"}, SkippedForeign: []string{}}, nil
}
