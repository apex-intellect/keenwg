package coordinator

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
)

const maxRecoveryFileBytes = 2 << 20

var ErrInvalidRecovery = errors.New("invalid_recovery_record")

type FileRecoveryStore struct{ path string }

func NewFileRecoveryStore(path string) *FileRecoveryStore {
	return &FileRecoveryStore{path: filepath.Clean(path)}
}

func (s *FileRecoveryStore) Save(ctx context.Context, record RecoveryRecord) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if s == nil || s.path == "" || !validRecordShape(record) {
		return ErrInvalidRecovery
	}
	if info, err := os.Lstat(s.path); err == nil {
		if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
			return ErrInvalidRecovery
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	body, err := json.Marshal(record)
	if err != nil || len(body) > maxRecoveryFileBytes {
		return ErrInvalidRecovery
	}
	directory := filepath.Dir(s.path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	temp, err := os.CreateTemp(directory, ".keenwg-recovery-*")
	if err != nil {
		return err
	}
	tempPath := temp.Name()
	ok := false
	defer func() {
		_ = temp.Close()
		if !ok {
			_ = os.Remove(tempPath)
		}
	}()
	if err := temp.Chmod(0o600); err != nil {
		return err
	}
	if _, err := temp.Write(body); err != nil {
		return err
	}
	if err := temp.Sync(); err != nil {
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tempPath, s.path); err != nil {
		return err
	}
	if err := os.Chmod(s.path, 0o600); err != nil {
		return err
	}
	ok = true
	return nil
}

func (s *FileRecoveryStore) Load(ctx context.Context) (*RecoveryRecord, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if s == nil || s.path == "" {
		return nil, ErrInvalidRecovery
	}
	info, err := os.Lstat(s.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() || info.Size() > maxRecoveryFileBytes {
		return nil, ErrInvalidRecovery
	}
	file, err := os.Open(s.path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	body, err := io.ReadAll(io.LimitReader(file, maxRecoveryFileBytes+1))
	if err != nil || len(body) > maxRecoveryFileBytes {
		return nil, ErrInvalidRecovery
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	var record RecoveryRecord
	if err := decoder.Decode(&record); err != nil {
		return nil, ErrInvalidRecovery
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return nil, ErrInvalidRecovery
	}
	if !validRecordShape(record) {
		return nil, ErrInvalidRecovery
	}
	copy := cloneRecord(record)
	return &copy, nil
}

func (s *FileRecoveryStore) Delete(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if s == nil || s.path == "" {
		return ErrInvalidRecovery
	}
	info, err := os.Lstat(s.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return ErrInvalidRecovery
	}
	return os.Remove(s.path)
}

func validRecordShape(record RecoveryRecord) bool {
	if record.SchemaVersion != 1 || !safeID.MatchString(record.PlanID) || len(record.Entries) == 0 || len(record.Entries) > 32 {
		return false
	}
	total := 0
	seen := map[string]struct{}{}
	for _, entry := range record.Entries {
		if !safeID.MatchString(entry.Module) || len(entry.Before) == 0 || len(entry.Staged) == 0 {
			return false
		}
		if _, ok := seen[entry.Module]; ok {
			return false
		}
		seen[entry.Module] = struct{}{}
		total += len(entry.Before) + len(entry.Staged)
	}
	return total <= maxRecoveryBytes
}

func cloneRecord(record RecoveryRecord) RecoveryRecord {
	result := RecoveryRecord{SchemaVersion: record.SchemaVersion, PlanID: record.PlanID, Entries: make([]RecoveryEntry, len(record.Entries))}
	for i, entry := range record.Entries {
		result.Entries[i] = RecoveryEntry{Module: entry.Module, Before: append([]byte(nil), entry.Before...), Staged: append([]byte(nil), entry.Staged...)}
	}
	return result
}
