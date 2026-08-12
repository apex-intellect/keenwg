package subscriptionconfig

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sync"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/config"
)

const (
	schemaVersion = 1
	maxFileBytes  = 16 << 10
)

var (
	ErrInvalid = errors.New("invalid_subscription_configuration")
	ErrStorage = errors.New("subscription_configuration_storage")
)

type document struct {
	SchemaVersion   int    `json:"schema_version"`
	SubscriptionURL string `json:"subscription_url"`
}

type Store struct {
	mu      sync.RWMutex
	path    string
	current string
}

func New(path, legacyURL string) (*Store, error) {
	if path == "" || !filepath.IsAbs(path) {
		return nil, ErrStorage
	}
	if legacyURL != "" {
		if err := config.ValidateSubscriptionURL(legacyURL); err != nil {
			return nil, ErrInvalid
		}
	}
	current, exists, err := read(path)
	if err != nil {
		return nil, err
	}
	store := &Store{path: path, current: current}
	if legacyURL != "" && (!exists || current != legacyURL) {
		if err := store.Replace(legacyURL); err != nil {
			return nil, err
		}
	}
	return store, nil
}

func (s *Store) Current() (string, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.current, s.current != ""
}

func (s *Store) Configured() bool {
	_, configured := s.Current()
	return configured
}

func (s *Store) Replace(raw string) error {
	if err := config.ValidateSubscriptionURL(raw); err != nil {
		return ErrInvalid
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := writeAtomic(s.path, document{SchemaVersion: schemaVersion, SubscriptionURL: raw}); err != nil {
		return ErrStorage
	}
	s.current = raw
	return nil
}

func read(path string) (string, bool, error) {
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return "", false, nil
	}
	if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 || info.Size() > maxFileBytes {
		return "", false, ErrStorage
	}
	if runtime.GOOS != "windows" && info.Mode().Perm() != 0o600 {
		return "", false, ErrStorage
	}
	file, err := os.Open(path)
	if err != nil {
		return "", false, ErrStorage
	}
	defer file.Close()
	var value document
	decoder := json.NewDecoder(io.LimitReader(file, maxFileBytes+1))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&value); err != nil {
		return "", false, ErrStorage
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return "", false, ErrStorage
	}
	if value.SchemaVersion != schemaVersion || config.ValidateSubscriptionURL(value.SubscriptionURL) != nil {
		return "", false, ErrStorage
	}
	return value.SubscriptionURL, true, nil
}

func writeAtomic(target string, value document) error {
	if err := safeTarget(target); err != nil {
		return err
	}
	directory := filepath.Dir(target)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	body, err := json.Marshal(value)
	if err != nil || len(body) > maxFileBytes {
		return ErrStorage
	}
	temporary, err := os.CreateTemp(directory, ".keenwg-subscription-*")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	closeWithError := func(cause error) error {
		_ = temporary.Close()
		return cause
	}
	if err := temporary.Chmod(0o600); err != nil {
		return closeWithError(err)
	}
	if _, err := temporary.Write(body); err != nil {
		return closeWithError(err)
	}
	if err := temporary.Sync(); err != nil {
		return closeWithError(err)
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	if err := safeTarget(target); err != nil {
		return err
	}
	if err := os.Rename(temporaryPath, target); err != nil {
		return err
	}
	if err := os.Chmod(target, 0o600); err != nil {
		return err
	}
	if parent, err := os.Open(directory); err == nil {
		_ = parent.Sync()
		_ = parent.Close()
	}
	return nil
}

func safeTarget(path string) error {
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 {
		return ErrStorage
	}
	return nil
}
