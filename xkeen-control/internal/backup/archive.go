package backup

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/scrypt"
)

const (
	SchemaVersion   = 1
	MaxEntryBytes   = 512 << 10
	MaxArchiveBytes = 4 << 20
	maxEntries      = 64
	scryptN         = 1 << 15
)

var (
	ErrTooLarge   = errors.New("backup_too_large")
	ErrDecrypt    = errors.New("backup_decrypt_failed")
	ErrDowngrade  = errors.New("backup_from_newer_version")
	ErrRolledBack = errors.New("restore_rolled_back")
	ErrUncertain  = errors.New("restore_uncertain")
)

type Input struct {
	ID    string
	Data  []byte
	Owned bool
}
type Entry struct {
	ID     string `json:"id"`
	Data   []byte `json:"data"`
	Owned  bool   `json:"owned"`
	SHA256 string `json:"sha256"`
}
type Plan struct {
	SchemaVersion int       `json:"schema_version"`
	SourceVersion string    `json:"source_version"`
	CreatedAt     time.Time `json:"created_at"`
	Entries       []Entry   `json:"entries"`
}
type envelope struct {
	SchemaVersion int    `json:"schema_version"`
	KDF           string `json:"kdf"`
	N             int    `json:"n"`
	R             int    `json:"r"`
	P             int    `json:"p"`
	Salt          []byte `json:"salt"`
	Nonce         []byte `json:"nonce"`
	Ciphertext    []byte `json:"ciphertext"`
}

func Create(version string, inputs []Input, passphrase []byte, random io.Reader, now time.Time) ([]byte, error) {
	if random == nil {
		random = rand.Reader
	}
	if len(passphrase) < 8 || len(passphrase) > 1024 || len(inputs) > maxEntries || !supportedSource(version) {
		return nil, errors.New("invalid_backup_input")
	}
	plan := Plan{SchemaVersion: SchemaVersion, SourceVersion: version, CreatedAt: now.UTC(), Entries: make([]Entry, 0, len(inputs))}
	seen := map[string]bool{}
	for _, input := range inputs {
		if !validID(input.ID) || seen[input.ID] {
			return nil, errors.New("invalid_backup_entry")
		}
		seen[input.ID] = true
		if len(input.Data) > MaxEntryBytes {
			return nil, ErrTooLarge
		}
		hash := sha256.Sum256(input.Data)
		plan.Entries = append(plan.Entries, Entry{ID: input.ID, Data: append([]byte(nil), input.Data...), Owned: input.Owned, SHA256: fmt.Sprintf("%x", hash)})
	}
	plaintext, err := json.Marshal(plan)
	if err != nil {
		return nil, err
	}
	if len(plaintext) > MaxArchiveBytes {
		return nil, ErrTooLarge
	}
	salt := make([]byte, 16)
	nonce := make([]byte, 12)
	if _, err = io.ReadFull(random, salt); err != nil {
		return nil, err
	}
	if _, err = io.ReadFull(random, nonce); err != nil {
		return nil, err
	}
	key, err := scrypt.Key(passphrase, salt, scryptN, 8, 1, 32)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	sealed := gcm.Seal(nil, nonce, plaintext, []byte("KeenWG backup v1"))
	encoded, err := json.Marshal(envelope{SchemaVersion: SchemaVersion, KDF: "scrypt", N: scryptN, R: 8, P: 1, Salt: salt, Nonce: nonce, Ciphertext: sealed})
	if err != nil {
		return nil, err
	}
	if len(encoded) > MaxArchiveBytes {
		return nil, ErrTooLarge
	}
	return encoded, nil
}

func Preview(blob, passphrase []byte, currentVersion string) (Plan, error) {
	var zero Plan
	if len(blob) == 0 || len(blob) > MaxArchiveBytes || len(passphrase) < 8 || len(passphrase) > 1024 {
		return zero, ErrDecrypt
	}
	var env envelope
	decoder := json.NewDecoder(bytes.NewReader(blob))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&env); err != nil || env.SchemaVersion != SchemaVersion || env.KDF != "scrypt" || env.N != scryptN || env.R != 8 || env.P != 1 || len(env.Salt) != 16 || len(env.Nonce) != 12 {
		return zero, ErrDecrypt
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		return zero, ErrDecrypt
	}
	key, err := scrypt.Key(passphrase, env.Salt, env.N, env.R, env.P, 32)
	if err != nil {
		return zero, ErrDecrypt
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return zero, ErrDecrypt
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return zero, ErrDecrypt
	}
	plaintext, err := gcm.Open(nil, env.Nonce, env.Ciphertext, []byte("KeenWG backup v1"))
	if err != nil {
		return zero, ErrDecrypt
	}
	var plan Plan
	decoder = json.NewDecoder(bytes.NewReader(plaintext))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&plan); err != nil || decoder.Decode(&struct{}{}) != io.EOF {
		return zero, errors.New("invalid_backup_manifest")
	}
	if plan.SchemaVersion != SchemaVersion || len(plan.Entries) > maxEntries || !supportedSource(plan.SourceVersion) {
		return zero, errors.New("unsupported_backup")
	}
	if compareVersion(plan.SourceVersion, currentVersion) > 0 {
		return zero, ErrDowngrade
	}
	seen := map[string]bool{}
	for _, entry := range plan.Entries {
		if !validID(entry.ID) || seen[entry.ID] || len(entry.Data) > MaxEntryBytes {
			return zero, errors.New("invalid_backup_entry")
		}
		seen[entry.ID] = true
		hash := sha256.Sum256(entry.Data)
		if entry.SHA256 != fmt.Sprintf("%x", hash) {
			return zero, errors.New("backup_hash_mismatch")
		}
	}
	return plan, nil
}

type Target interface {
	Read(context.Context, string) ([]byte, bool, error)
	Apply(context.Context, string, []byte) error
	Verify(context.Context, string, []byte) error
	SaveRecovery(string, []byte) error
	Restore(context.Context, string, []byte) error
	ClearRecovery() error
}
type ApplyResult struct {
	Applied        []string `json:"applied"`
	SkippedForeign []string `json:"skipped_foreign"`
}
type snapshot struct {
	id   string
	data []byte
}

func Apply(ctx context.Context, target Target, plan Plan) (ApplyResult, error) {
	result := ApplyResult{Applied: []string{}, SkippedForeign: []string{}}
	snapshots := []snapshot{}
	for _, entry := range plan.Entries {
		if !entry.Owned {
			continue
		}
		before, owned, err := target.Read(ctx, entry.ID)
		if err != nil {
			return result, rollback(ctx, target, snapshots, err)
		}
		if !owned {
			result.SkippedForeign = append(result.SkippedForeign, entry.ID)
			continue
		}
		if err := target.SaveRecovery(entry.ID, before); err != nil {
			return result, rollback(ctx, target, snapshots, err)
		}
		snapshots = append(snapshots, snapshot{entry.ID, before})
		if err := target.Apply(ctx, entry.ID, entry.Data); err != nil {
			return result, rollback(ctx, target, snapshots, err)
		}
		if err := target.Verify(ctx, entry.ID, entry.Data); err != nil {
			return result, rollback(ctx, target, snapshots, err)
		}
		result.Applied = append(result.Applied, entry.ID)
	}
	if err := target.ClearRecovery(); err != nil {
		return result, fmt.Errorf("%w: %v", ErrUncertain, err)
	}
	return result, nil
}

func rollback(ctx context.Context, target Target, snapshots []snapshot, cause error) error {
	clean := true
	for index := len(snapshots) - 1; index >= 0; index-- {
		item := snapshots[index]
		if target.Restore(ctx, item.id, item.data) != nil || target.Verify(ctx, item.id, item.data) != nil {
			clean = false
		}
	}
	if clean && target.ClearRecovery() == nil {
		return fmt.Errorf("%w: %v", ErrRolledBack, cause)
	}
	return fmt.Errorf("%w: %v", ErrUncertain, cause)
}

func validID(value string) bool {
	if len(value) < 1 || len(value) > 64 {
		return false
	}
	for _, r := range value {
		if !(r == '.' || r == '-' || r == '_' || r >= 'a' && r <= 'z' || r >= '0' && r <= '9') {
			return false
		}
	}
	return true
}
func supportedSource(value string) bool {
	parts, ok := parseVersion(value)
	return ok && (parts[0] > 0 || parts[1] >= 6)
}
func compareVersion(left, right string) int {
	a, oka := parseVersion(left)
	b, okb := parseVersion(right)
	if !oka || !okb {
		return 1
	}
	for i := range a {
		if a[i] < b[i] {
			return -1
		}
		if a[i] > b[i] {
			return 1
		}
	}
	return 0
}
func parseVersion(value string) ([3]int, bool) {
	var out [3]int
	pieces := strings.Split(value, ".")
	if len(pieces) != 3 {
		return out, false
	}
	for i, piece := range pieces {
		n, err := strconv.Atoi(piece)
		if err != nil || n < 0 {
			return out, false
		}
		out[i] = n
	}
	return out, true
}
