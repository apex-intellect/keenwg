package api

import (
	"context"
	"errors"
	"net/http"

	"github.com/goldb/keenwg/xkeen-control/internal/backup"
)

const maxBackupRequestBody int64 = 6 << 20

type BackupPreviewEntry struct {
	ID    string `json:"id"`
	Bytes int    `json:"bytes"`
	Owned bool   `json:"owned"`
}
type BackupPreview struct {
	SchemaVersion int                  `json:"schema_version"`
	PlanID        string               `json:"plan_id"`
	SourceVersion string               `json:"source_version"`
	Entries       []BackupPreviewEntry `json:"entries"`
}
type BackupManager interface {
	Create(context.Context, []byte) ([]byte, BackupPreview, error)
	Preview(context.Context, []byte, []byte) (BackupPreview, error)
	Apply(context.Context, []byte, []byte, string) (backup.ApplyResult, error)
}

type backupRequest struct {
	SchemaVersion  int    `json:"schema_version,omitempty"`
	Archive        []byte `json:"archive,omitempty"`
	Passphrase     string `json:"passphrase"`
	ReviewedPlanID string `json:"reviewed_plan_id,omitempty"`
}

func (s *SecureServer) handleBackup(w http.ResponseWriter, r *http.Request, action string) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	var request backupRequest
	if !decodeSecureJSONLimit(w, r, &request, maxBackupRequestBody) || (request.SchemaVersion != 0 && request.SchemaVersion != 1) || len(request.Passphrase) < 8 || len(request.Passphrase) > 1024 {
		writeError(w, http.StatusBadRequest, "invalid_backup_request")
		return
	}
	passphrase := []byte(request.Passphrase)
	request.Passphrase = ""
	defer func() {
		for index := range passphrase {
			passphrase[index] = 0
		}
	}()
	switch action {
	case "create":
		archive, preview, err := s.backup.Create(r.Context(), passphrase)
		if err != nil {
			writeBackupError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, struct {
			SchemaVersion int           `json:"schema_version"`
			Archive       []byte        `json:"archive"`
			Preview       BackupPreview `json:"preview"`
		}{1, archive, preview})
	case "preview":
		preview, err := s.backup.Preview(r.Context(), request.Archive, passphrase)
		if err != nil {
			writeBackupError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, preview)
	case "apply":
		if request.ReviewedPlanID == "" {
			writeError(w, http.StatusBadRequest, "review_required")
			return
		}
		result, err := s.backup.Apply(r.Context(), request.Archive, passphrase, request.ReviewedPlanID)
		if err != nil {
			writeBackupError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, result)
	default:
		writeError(w, http.StatusNotFound, "not_found")
	}
}

func writeBackupError(w http.ResponseWriter, err error) {
	status := http.StatusServiceUnavailable
	code := "backup_unavailable"
	switch {
	case errors.Is(err, backup.ErrDecrypt):
		status = http.StatusBadRequest
		code = "backup_decrypt_failed"
	case errors.Is(err, backup.ErrDowngrade):
		status = http.StatusConflict
		code = "backup_from_newer_version"
	case errors.Is(err, backup.ErrTooLarge):
		status = http.StatusRequestEntityTooLarge
		code = "backup_too_large"
	case errors.Is(err, backup.ErrRolledBack):
		status = http.StatusConflict
		code = "restore_rolled_back"
	case errors.Is(err, backup.ErrUncertain):
		code = "restore_uncertain"
	}
	writeError(w, status, code)
}
