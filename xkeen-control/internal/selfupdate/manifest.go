package selfupdate

import (
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"regexp"
	"strconv"
	"strings"
)

const (
	ManifestSchemaVersion = 1
	MaximumManifestBytes  = 16 * 1024
	MaximumArchiveBytes   = 16 * 1024 * 1024
)

var (
	semverPattern = regexp.MustCompile(`^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?$`)
	sha256Pattern = regexp.MustCompile(`^[0-9a-f]{64}$`)
	keyIDPattern  = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{2,63}$`)
)

type Manifest struct {
	SchemaVersion int    `json:"schema_version"`
	Version       string `json:"version"`
	Architecture  string `json:"architecture"`
	ArchiveSHA256 string `json:"archive_sha256"`
	ArchiveSize   int64  `json:"archive_size"`
	BinarySHA256  string `json:"binary_sha256"`
	KeyID         string `json:"key_id"`
	Signature     string `json:"signature"`
}

type TrustedPublicKey struct {
	SchemaVersion int    `json:"schema_version"`
	KeyID         string `json:"key_id"`
	PublicKey     string `json:"public_key"`
}

func (m Manifest) CanonicalBytes() []byte {
	return []byte(fmt.Sprintf("keenwg-update-v1\n%d\n%s\n%s\n%s\n%d\n%s\n%s\n",
		m.SchemaVersion, m.Version, m.Architecture, m.ArchiveSHA256,
		m.ArchiveSize, m.BinarySHA256, m.KeyID))
}

func (m Manifest) Verify(publicKey ed25519.PublicKey, expectedKeyID string, archiveHash [sha256.Size]byte, archiveSize int64) error {
	if err := m.validate(true); err != nil {
		return err
	}
	if len(publicKey) != ed25519.PublicKeySize || m.KeyID != expectedKeyID {
		return errors.New("untrusted publisher")
	}
	if m.ArchiveSize != archiveSize || m.ArchiveSHA256 != hex.EncodeToString(archiveHash[:]) {
		return errors.New("archive does not match manifest")
	}
	signature, err := base64.RawStdEncoding.DecodeString(m.Signature)
	if err != nil || len(signature) != ed25519.SignatureSize {
		return errors.New("invalid signature")
	}
	if !ed25519.Verify(publicKey, m.CanonicalBytes(), signature) {
		return errors.New("publisher signature mismatch")
	}
	return nil
}

func DecodeManifest(reader io.Reader) (Manifest, error) {
	m, err := decodeManifest(reader)
	if err != nil {
		return Manifest{}, err
	}
	if err := m.validate(true); err != nil {
		return Manifest{}, err
	}
	return m, nil
}

func DecodeManifestForSigning(reader io.Reader) (Manifest, error) {
	m, err := decodeManifest(reader)
	if err != nil {
		return Manifest{}, err
	}
	if m.SchemaVersion != ManifestSchemaVersion || !semverPattern.MatchString(m.Version) ||
		m.Architecture != "arm64" || !sha256Pattern.MatchString(m.BinarySHA256) ||
		!keyIDPattern.MatchString(m.KeyID) || m.Signature != "" ||
		(m.ArchiveSHA256 != "" && !sha256Pattern.MatchString(m.ArchiveSHA256)) ||
		m.ArchiveSize < 0 || m.ArchiveSize > MaximumArchiveBytes {
		return Manifest{}, errors.New("invalid unsigned manifest")
	}
	return m, nil
}

func (m Manifest) validate(requireSignature bool) error {
	if m.SchemaVersion != ManifestSchemaVersion || !semverPattern.MatchString(m.Version) ||
		m.Architecture != "arm64" || !sha256Pattern.MatchString(m.ArchiveSHA256) ||
		m.ArchiveSize < 1 || m.ArchiveSize > MaximumArchiveBytes ||
		!sha256Pattern.MatchString(m.BinarySHA256) || !keyIDPattern.MatchString(m.KeyID) {
		return errors.New("invalid update manifest")
	}
	if requireSignature {
		signature, err := base64.RawStdEncoding.DecodeString(m.Signature)
		if err != nil || len(signature) != ed25519.SignatureSize {
			return errors.New("invalid update signature")
		}
	}
	return nil
}

func decodeManifest(reader io.Reader) (Manifest, error) {
	raw, err := io.ReadAll(io.LimitReader(reader, MaximumManifestBytes+1))
	if err != nil || len(raw) == 0 || len(raw) > MaximumManifestBytes {
		return Manifest{}, errors.New("invalid manifest size")
	}
	if err := rejectDuplicateTopLevelKeys(raw); err != nil {
		return Manifest{}, err
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	var manifest Manifest
	if err := decoder.Decode(&manifest); err != nil {
		return Manifest{}, errors.New("invalid manifest JSON")
	}
	if err := requireJSONEOF(decoder); err != nil {
		return Manifest{}, err
	}
	return manifest, nil
}

func rejectDuplicateTopLevelKeys(raw []byte) error {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	first, err := decoder.Token()
	if err != nil || first != json.Delim('{') {
		return errors.New("manifest must be an object")
	}
	seen := map[string]struct{}{}
	for decoder.More() {
		token, err := decoder.Token()
		if err != nil {
			return errors.New("invalid manifest JSON")
		}
		key, ok := token.(string)
		if !ok {
			return errors.New("invalid manifest key")
		}
		if _, exists := seen[key]; exists {
			return errors.New("duplicate manifest key")
		}
		seen[key] = struct{}{}
		var value json.RawMessage
		if err := decoder.Decode(&value); err != nil {
			return errors.New("invalid manifest value")
		}
	}
	if token, err := decoder.Token(); err != nil || token != json.Delim('}') {
		return errors.New("invalid manifest JSON")
	}
	return requireJSONEOF(decoder)
}

func requireJSONEOF(decoder *json.Decoder) error {
	var extra json.RawMessage
	if err := decoder.Decode(&extra); err != io.EOF {
		return errors.New("extra manifest content")
	}
	return nil
}

func ReadTrustedPublicKey(path string) (TrustedPublicKey, error) {
	raw, err := os.ReadFile(path)
	if err != nil || len(raw) == 0 || len(raw) > 4096 {
		return TrustedPublicKey{}, errors.New("trusted key unavailable")
	}
	if err := rejectDuplicateTopLevelKeys(raw); err != nil {
		return TrustedPublicKey{}, err
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	var trusted TrustedPublicKey
	if err := decoder.Decode(&trusted); err != nil || requireJSONEOF(decoder) != nil ||
		trusted.SchemaVersion != 1 || !keyIDPattern.MatchString(trusted.KeyID) {
		return TrustedPublicKey{}, errors.New("invalid trusted key document")
	}
	key, err := trusted.Decode()
	if err != nil || len(key) != ed25519.PublicKeySize {
		return TrustedPublicKey{}, errors.New("invalid trusted public key")
	}
	return trusted, nil
}

func (t TrustedPublicKey) Decode() (ed25519.PublicKey, error) {
	decoded, err := base64.RawStdEncoding.DecodeString(t.PublicKey)
	if err != nil || len(decoded) != ed25519.PublicKeySize {
		return nil, errors.New("invalid public key")
	}
	return ed25519.PublicKey(decoded), nil
}

func CompareVersions(left, right string) (int, error) {
	parse := func(value string) ([3]uint64, string, error) {
		match := semverPattern.FindStringSubmatch(value)
		if match == nil {
			return [3]uint64{}, "", errors.New("invalid semantic version")
		}
		var result [3]uint64
		for i := range result {
			parsed, err := strconv.ParseUint(match[i+1], 10, 64)
			if err != nil {
				return [3]uint64{}, "", err
			}
			result[i] = parsed
		}
		prerelease := ""
		if dash := strings.IndexByte(value, '-'); dash >= 0 {
			prerelease = value[dash+1:]
		}
		return result, prerelease, nil
	}
	l, lp, err := parse(left)
	if err != nil {
		return 0, err
	}
	r, rp, err := parse(right)
	if err != nil {
		return 0, err
	}
	for i := range l {
		if l[i] < r[i] {
			return -1, nil
		}
		if l[i] > r[i] {
			return 1, nil
		}
	}
	if lp == rp {
		return 0, nil
	}
	if lp == "" {
		return 1, nil
	}
	if rp == "" {
		return -1, nil
	}
	if lp < rp {
		return -1, nil
	}
	return 1, nil
}
