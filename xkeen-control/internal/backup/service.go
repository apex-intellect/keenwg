package backup

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"time"
)

type Resource struct {
	ID, Path string
	Owned    bool
}
type SummaryEntry struct {
	ID    string
	Bytes int
	Owned bool
}
type Summary struct {
	SchemaVersion         int
	PlanID, SourceVersion string
	Entries               []SummaryEntry
}

type FileService struct {
	version     string
	resources   []Resource
	recoveryDir string
	random      io.Reader
	now         func() time.Time
}

func NewFileService(version string, resources []Resource, recoveryDir string) (*FileService, error) {
	seen := map[string]bool{}
	for _, resource := range resources {
		if !validID(resource.ID) || resource.Path == "" || seen[resource.ID] {
			return nil, errors.New("invalid_backup_resource")
		}
		seen[resource.ID] = true
	}
	copyResources := append([]Resource(nil), resources...)
	sort.Slice(copyResources, func(i, j int) bool { return copyResources[i].ID < copyResources[j].ID })
	return &FileService{version: version, resources: copyResources, recoveryDir: recoveryDir, random: rand.Reader, now: time.Now}, nil
}

func (s *FileService) Create(ctx context.Context, passphrase []byte) ([]byte, Summary, error) {
	inputs := make([]Input, 0, len(s.resources))
	for _, resource := range s.resources {
		if err := ctx.Err(); err != nil {
			return nil, Summary{}, err
		}
		data, err := os.ReadFile(resource.Path)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return nil, Summary{}, err
		}
		inputs = append(inputs, Input{ID: resource.ID, Data: data, Owned: resource.Owned})
	}
	blob, err := Create(s.version, inputs, passphrase, s.random, s.now())
	if err != nil {
		return nil, Summary{}, err
	}
	plan, err := Preview(blob, passphrase, s.version)
	if err != nil {
		return nil, Summary{}, err
	}
	return blob, summary(blob, plan), nil
}

func (s *FileService) Preview(_ context.Context, blob, passphrase []byte) (Summary, error) {
	plan, err := Preview(blob, passphrase, s.version)
	if err != nil {
		return Summary{}, err
	}
	return summary(blob, plan), nil
}

func (s *FileService) Apply(ctx context.Context, blob, passphrase []byte, reviewedPlanID string) (ApplyResult, error) {
	plan, err := Preview(blob, passphrase, s.version)
	if err != nil {
		return ApplyResult{}, err
	}
	if reviewedPlanID == "" || summary(blob, plan).PlanID != reviewedPlanID {
		return ApplyResult{}, errors.New("stale_backup_plan")
	}
	target, err := newFileTarget(s.resources, s.recoveryDir)
	if err != nil {
		return ApplyResult{}, err
	}
	return Apply(ctx, target, plan)
}

func summary(blob []byte, plan Plan) Summary {
	hash := sha256.Sum256(blob)
	result := Summary{SchemaVersion: plan.SchemaVersion, PlanID: fmt.Sprintf("backup-%x", hash[:12]), SourceVersion: plan.SourceVersion, Entries: make([]SummaryEntry, 0, len(plan.Entries))}
	for _, entry := range plan.Entries {
		result.Entries = append(result.Entries, SummaryEntry{entry.ID, len(entry.Data), entry.Owned})
	}
	return result
}

type fileTarget struct {
	resources     map[string]Resource
	recoveryDir   string
	recoveryFiles []string
}

func newFileTarget(resources []Resource, recoveryDir string) (*fileTarget, error) {
	if recoveryDir == "" {
		return nil, errors.New("recovery_dir_required")
	}
	values := map[string]Resource{}
	for _, r := range resources {
		values[r.ID] = r
	}
	return &fileTarget{resources: values, recoveryDir: recoveryDir}, nil
}
func (f *fileTarget) resource(id string) (Resource, error) {
	value, ok := f.resources[id]
	if !ok {
		return Resource{}, errors.New("unknown_backup_resource")
	}
	return value, nil
}
func (f *fileTarget) Read(ctx context.Context, id string) ([]byte, bool, error) {
	if err := ctx.Err(); err != nil {
		return nil, false, err
	}
	r, err := f.resource(id)
	if err != nil {
		return nil, false, err
	}
	data, err := os.ReadFile(r.Path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, r.Owned, nil
	}
	return data, r.Owned, err
}
func (f *fileTarget) Apply(ctx context.Context, id string, data []byte) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	r, err := f.resource(id)
	if err != nil {
		return err
	}
	return writeAtomic(r.Path, data, 0o600)
}
func (f *fileTarget) Verify(ctx context.Context, id string, data []byte) error {
	actual, _, err := f.Read(ctx, id)
	if err != nil {
		return err
	}
	if string(actual) != string(data) {
		return errors.New("restore_verify_failed")
	}
	return nil
}
func (f *fileTarget) SaveRecovery(id string, data []byte) error {
	if err := os.MkdirAll(f.recoveryDir, 0o700); err != nil {
		return err
	}
	path := filepath.Join(f.recoveryDir, id+".bak")
	payload := append([]byte{0}, data...)
	if data != nil {
		payload[0] = 1
	}
	if err := writeAtomic(path, payload, 0o600); err != nil {
		return err
	}
	f.recoveryFiles = append(f.recoveryFiles, path)
	return nil
}
func (f *fileTarget) Restore(_ context.Context, id string, data []byte) error {
	r, err := f.resource(id)
	if err != nil {
		return err
	}
	if data == nil {
		err = os.Remove(r.Path)
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}
	return writeAtomic(r.Path, data, 0o600)
}
func (f *fileTarget) ClearRecovery() error {
	for _, path := range f.recoveryFiles {
		if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
	}
	f.recoveryFiles = nil
	return nil
}
func writeAtomic(path string, data []byte, mode os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	file, err := os.CreateTemp(filepath.Dir(path), ".keenwg-restore-*")
	if err != nil {
		return err
	}
	name := file.Name()
	defer os.Remove(name)
	if err = file.Chmod(mode); err == nil {
		_, err = file.Write(data)
	}
	if err == nil {
		err = file.Sync()
	}
	closeErr := file.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	return os.Rename(name, path)
}
