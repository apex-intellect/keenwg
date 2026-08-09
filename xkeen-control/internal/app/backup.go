package app

import (
	"context"

	"github.com/goldb/keenwg/xkeen-control/internal/api"
	"github.com/goldb/keenwg/xkeen-control/internal/backup"
)

type backupManager struct{ service *backup.FileService }

func (m backupManager) Create(ctx context.Context, passphrase []byte) ([]byte, api.BackupPreview, error) {
	blob, summary, err := m.service.Create(ctx, passphrase)
	return blob, toAPIPreview(summary), err
}
func (m backupManager) Preview(ctx context.Context, blob, passphrase []byte) (api.BackupPreview, error) {
	summary, err := m.service.Preview(ctx, blob, passphrase)
	return toAPIPreview(summary), err
}
func (m backupManager) Apply(ctx context.Context, blob, passphrase []byte, reviewed string) (backup.ApplyResult, error) {
	return m.service.Apply(ctx, blob, passphrase, reviewed)
}
func toAPIPreview(summary backup.Summary) api.BackupPreview {
	result := api.BackupPreview{SchemaVersion: summary.SchemaVersion, PlanID: summary.PlanID, SourceVersion: summary.SourceVersion, Entries: make([]api.BackupPreviewEntry, 0, len(summary.Entries))}
	for _, entry := range summary.Entries {
		result.Entries = append(result.Entries, api.BackupPreviewEntry{ID: entry.ID, Bytes: entry.Bytes, Owned: entry.Owned})
	}
	return result
}
