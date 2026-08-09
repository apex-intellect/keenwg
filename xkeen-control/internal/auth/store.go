package auth

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	storeSchemaVersion = 1
	maxStoreBytes      = 1 << 20
	maxOfferTTL        = 15 * time.Minute
)

type FileStore struct {
	mu         sync.Mutex
	devicePath string
	offerPath  string
	devices    []Device
	offers     []Offer
	now        func() time.Time
	random     io.Reader
}

type deviceDocument struct {
	SchemaVersion int            `json:"schema_version"`
	Devices       []deviceRecord `json:"devices"`
}

type deviceRecord struct {
	ID        string    `json:"id"`
	Label     string    `json:"label"`
	Scope     Scope     `json:"scope"`
	TokenHash string    `json:"token_hash"`
	CreatedAt time.Time `json:"created_at"`
	LastUsed  time.Time `json:"last_used,omitempty"`
}

type offerDocument struct {
	SchemaVersion int           `json:"schema_version"`
	Offers        []offerRecord `json:"offers"`
}

type offerRecord struct {
	ID         string     `json:"id"`
	SecretHash string     `json:"secret_hash"`
	Scope      Scope      `json:"scope"`
	ExpiresAt  time.Time  `json:"expires_at"`
	UsedAt     *time.Time `json:"used_at,omitempty"`
}

func NewFileStore(devicePath, offerPath string) (*FileStore, error) {
	store := &FileStore{
		devicePath: devicePath,
		offerPath:  offerPath,
		now:        time.Now,
		random:     rand.Reader,
		devices:    []Device{},
		offers:     []Offer{},
	}
	if devicePath == "" || offerPath == "" || devicePath == offerPath {
		return nil, ErrStoreIO
	}
	devices, err := loadDevices(devicePath)
	if err != nil {
		return nil, err
	}
	offers, err := loadOffers(offerPath)
	if err != nil {
		return nil, err
	}
	store.devices = devices
	store.offers = offers
	return store, nil
}

func (s *FileStore) Authenticate(ctx context.Context, token string) (Device, error) {
	if err := ctx.Err(); err != nil {
		return Device{}, err
	}
	digest := sha256.Sum256([]byte(token))
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.devices {
		if subtle.ConstantTimeCompare(digest[:], s.devices[i].TokenHash[:]) != 1 {
			continue
		}
		now := s.now().UTC()
		if s.devices[i].LastUsed.IsZero() || now.Sub(s.devices[i].LastUsed) >= time.Minute {
			previous := s.devices[i].LastUsed
			s.devices[i].LastUsed = now
			if err := s.persistDevices(); err != nil {
				s.devices[i].LastUsed = previous
				return Device{}, err
			}
		}
		return publicDevice(s.devices[i]), nil
	}
	return Device{}, ErrUnauthorized
}

func (s *FileStore) ListDevices(ctx context.Context) ([]Device, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	result := make([]Device, len(s.devices))
	for i := range s.devices {
		result[i] = publicDevice(s.devices[i])
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].CreatedAt.Equal(result[j].CreatedAt) {
			return result[i].ID < result[j].ID
		}
		return result[i].CreatedAt.Before(result[j].CreatedAt)
	})
	return result, nil
}

func (s *FileStore) RevokeDevice(ctx context.Context, id string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	index := -1
	owners := 0
	for i := range s.devices {
		if s.devices[i].Scope == ScopeOwner {
			owners++
		}
		if s.devices[i].ID == id {
			index = i
		}
	}
	if index < 0 {
		return ErrDeviceNotFound
	}
	if s.devices[index].Scope == ScopeOwner && owners == 1 {
		return ErrLastOwner
	}
	removed := s.devices[index]
	s.devices = append(s.devices[:index], s.devices[index+1:]...)
	if err := s.persistDevices(); err != nil {
		s.devices = append(s.devices, Device{})
		copy(s.devices[index+1:], s.devices[index:])
		s.devices[index] = removed
		return err
	}
	return nil
}

func (s *FileStore) persistDevices() error {
	records := make([]deviceRecord, len(s.devices))
	for i, device := range s.devices {
		records[i] = deviceRecord{
			ID: device.ID, Label: device.Label, Scope: device.Scope,
			TokenHash: hex.EncodeToString(device.TokenHash[:]),
			CreatedAt: device.CreatedAt, LastUsed: device.LastUsed,
		}
	}
	return writeJSONAtomic(s.devicePath, deviceDocument{SchemaVersion: storeSchemaVersion, Devices: records})
}

func (s *FileStore) persistOffers() error {
	records := make([]offerRecord, len(s.offers))
	for i, offer := range s.offers {
		records[i] = offerRecord{
			ID: offer.ID, SecretHash: hex.EncodeToString(offer.SecretHash[:]), Scope: offer.Scope,
			ExpiresAt: offer.ExpiresAt, UsedAt: offer.UsedAt,
		}
	}
	return writeJSONAtomic(s.offerPath, offerDocument{SchemaVersion: storeSchemaVersion, Offers: records})
}

func loadDevices(path string) ([]Device, error) {
	var document deviceDocument
	found, err := readJSONStrict(path, &document)
	if err != nil || !found {
		return []Device{}, err
	}
	if document.SchemaVersion != storeSchemaVersion || document.Devices == nil {
		return nil, ErrStoreCorrupt
	}
	result := make([]Device, len(document.Devices))
	seen := make(map[string]struct{}, len(document.Devices))
	for i, record := range document.Devices {
		hash, err := decodeHash(record.TokenHash)
		if err != nil || record.ID == "" || !validScope(record.Scope) || !validLabel(record.Label) || record.CreatedAt.IsZero() {
			return nil, ErrStoreCorrupt
		}
		if _, exists := seen[record.ID]; exists {
			return nil, ErrStoreCorrupt
		}
		seen[record.ID] = struct{}{}
		result[i] = Device{ID: record.ID, Label: record.Label, Scope: record.Scope, TokenHash: hash, CreatedAt: record.CreatedAt, LastUsed: record.LastUsed}
	}
	return result, nil
}

func loadOffers(path string) ([]Offer, error) {
	var document offerDocument
	found, err := readJSONStrict(path, &document)
	if err != nil || !found {
		return []Offer{}, err
	}
	if document.SchemaVersion != storeSchemaVersion || document.Offers == nil {
		return nil, ErrStoreCorrupt
	}
	result := make([]Offer, len(document.Offers))
	seen := make(map[string]struct{}, len(document.Offers))
	for i, record := range document.Offers {
		hash, err := decodeHash(record.SecretHash)
		if err != nil || record.ID == "" || !validScope(record.Scope) || record.ExpiresAt.IsZero() {
			return nil, ErrStoreCorrupt
		}
		if _, exists := seen[record.ID]; exists {
			return nil, ErrStoreCorrupt
		}
		seen[record.ID] = struct{}{}
		result[i] = Offer{ID: record.ID, SecretHash: hash, Scope: record.Scope, ExpiresAt: record.ExpiresAt, UsedAt: record.UsedAt}
	}
	return result, nil
}

func readJSONStrict(path string, destination any) (bool, error) {
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("%w: stat", ErrStoreIO)
	}
	if !info.Mode().IsRegular() {
		return false, ErrStoreCorrupt
	}
	file, err := os.Open(path)
	if err != nil {
		return false, fmt.Errorf("%w: open", ErrStoreIO)
	}
	defer file.Close()
	decoder := json.NewDecoder(io.LimitReader(file, maxStoreBytes))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		return false, ErrStoreCorrupt
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return false, ErrStoreCorrupt
	}
	return true, nil
}

func writeJSONAtomic(target string, value any) error {
	if info, err := os.Lstat(target); err == nil && !info.Mode().IsRegular() {
		return ErrStoreCorrupt
	} else if err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("%w: stat", ErrStoreIO)
	}
	directory := filepath.Dir(target)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return fmt.Errorf("%w: mkdir", ErrStoreIO)
	}
	if err := os.Chmod(directory, 0o700); err != nil {
		return fmt.Errorf("%w: chmod directory", ErrStoreIO)
	}
	file, err := os.CreateTemp(directory, "."+filepath.Base(target)+".tmp-*")
	if err != nil {
		return fmt.Errorf("%w: create temp", ErrStoreIO)
	}
	temporary := file.Name()
	closed := false
	defer func() {
		if !closed {
			_ = file.Close()
		}
		_ = os.Remove(temporary)
	}()
	if err := file.Chmod(0o600); err != nil {
		return fmt.Errorf("%w: chmod temp", ErrStoreIO)
	}
	encoder := json.NewEncoder(file)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(value); err != nil {
		return fmt.Errorf("%w: encode", ErrStoreIO)
	}
	if err := file.Sync(); err != nil {
		return fmt.Errorf("%w: sync", ErrStoreIO)
	}
	if err := file.Close(); err != nil {
		return fmt.Errorf("%w: close", ErrStoreIO)
	}
	closed = true
	if err := os.Rename(temporary, target); err != nil {
		return fmt.Errorf("%w: rename", ErrStoreIO)
	}
	if err := os.Chmod(target, 0o600); err != nil {
		return fmt.Errorf("%w: chmod target", ErrStoreIO)
	}
	if parent, err := os.Open(directory); err == nil {
		_ = parent.Sync()
		_ = parent.Close()
	}
	return nil
}

func decodeHash(value string) ([32]byte, error) {
	var result [32]byte
	decoded, err := hex.DecodeString(value)
	if err != nil || len(decoded) != len(result) {
		return result, ErrStoreCorrupt
	}
	copy(result[:], decoded)
	return result, nil
}

func randomText(reader io.Reader, bytes int) (string, error) {
	buffer := make([]byte, bytes)
	if _, err := io.ReadFull(reader, buffer); err != nil {
		return "", fmt.Errorf("%w: random", ErrStoreIO)
	}
	return base64.RawURLEncoding.EncodeToString(buffer), nil
}

func validScope(scope Scope) bool {
	return scope == ScopeOwner || scope == ScopeOperator || scope == ScopeViewer
}

func validLabel(label string) bool {
	return label == strings.TrimSpace(label) && utf8.ValidString(label) && utf8.RuneCountInString(label) >= 1 && utf8.RuneCountInString(label) <= 64
}

func publicDevice(device Device) Device {
	device.TokenHash = [32]byte{}
	return device
}
