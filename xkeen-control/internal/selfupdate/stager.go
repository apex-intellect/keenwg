package selfupdate

import (
	"archive/tar"
	"bufio"
	"compress/gzip"
	"crypto/ed25519"
	"crypto/sha256"
	"embed"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
)

var (
	ErrInvalidUpdate = errors.New("invalid update")
	ErrUpdateBusy    = errors.New("update already pending")
	ErrUpdateStorage = errors.New("update storage unavailable")
	operationPattern = regexp.MustCompile(`^[0-9a-f]{32}$`)
)

//go:embed trusted-public-key.txt
var trustedKeyFS embed.FS

type StagerConfig struct {
	UpdateDirectory     string
	CurrentVersion      string
	CurrentBinarySHA256 string
	PublicKey           ed25519.PublicKey
	KeyID               string
	Random              io.Reader
}

type Stager struct {
	updateDirectory, currentVersion, currentBinarySHA256, keyID string
	publicKey                                                   ed25519.PublicKey
	random                                                      io.Reader
}

type AcceptedUpdate struct {
	OperationID   string `json:"operation_id"`
	TargetVersion string `json:"target_version"`
}

type PendingRequest struct {
	SchemaVersion       int      `json:"schema_version"`
	OperationID         string   `json:"operation_id"`
	ArchiveFile         string   `json:"archive_file"`
	CurrentVersion      string   `json:"current_version"`
	CurrentBinarySHA256 string   `json:"current_binary_sha256"`
	TargetVersion       string   `json:"target_version"`
	Manifest            Manifest `json:"manifest"`
}

type Status struct {
	SchemaVersion  int    `json:"schema_version"`
	CurrentVersion string `json:"current_version"`
	Supported      bool   `json:"supported"`
	Phase          string `json:"phase"`
	Result         string `json:"result"`
	TargetVersion  string `json:"target_version,omitempty"`
	Error          string `json:"error,omitempty"`
}

func NewStager(config StagerConfig) *Stager {
	return &Stager{
		updateDirectory: config.UpdateDirectory, currentVersion: config.CurrentVersion,
		currentBinarySHA256: config.CurrentBinarySHA256, publicKey: append(ed25519.PublicKey(nil), config.PublicKey...),
		keyID: config.KeyID, random: config.Random,
	}
}

func TrustedKey() (TrustedPublicKey, ed25519.PublicKey, error) {
	raw, err := trustedKeyFS.ReadFile("trusted-public-key.txt")
	if err != nil {
		return TrustedPublicKey{}, nil, err
	}
	trusted, err := decodeTrustedPublicKey(raw)
	if err != nil {
		return TrustedPublicKey{}, nil, err
	}
	key, err := trusted.Decode()
	return trusted, key, err
}

func (s *Stager) Stage(reader io.Reader) (accepted AcceptedUpdate, resultErr error) {
	if reader == nil || s.random == nil || s.updateDirectory == "" ||
		!sha256Pattern.MatchString(s.currentBinarySHA256) {
		return accepted, ErrInvalidUpdate
	}
	if err := ensurePrivateDirectory(s.updateDirectory); err != nil {
		return accepted, fmt.Errorf("%w", ErrUpdateStorage)
	}
	requestPath := filepath.Join(s.updateDirectory, "pending.json")
	if _, err := os.Lstat(requestPath); err == nil {
		return accepted, ErrUpdateBusy
	} else if !errors.Is(err, os.ErrNotExist) {
		return accepted, ErrUpdateStorage
	}
	uploadID, err := randomHex(s.random)
	if err != nil {
		return accepted, ErrUpdateStorage
	}
	uploadPath := filepath.Join(s.updateDirectory, ".upload-"+uploadID)
	file, err := os.OpenFile(uploadPath, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return accepted, ErrUpdateStorage
	}
	keepArchive := false
	defer func() {
		if !keepArchive {
			_ = os.Remove(uploadPath)
		}
	}()

	var prefix [4]byte
	if _, err = io.ReadFull(reader, prefix[:]); err != nil {
		_ = file.Close()
		return accepted, ErrInvalidUpdate
	}
	manifestSize := int64(binary.BigEndian.Uint32(prefix[:]))
	if manifestSize < 1 || manifestSize > MaximumManifestBytes {
		_ = file.Close()
		return accepted, ErrInvalidUpdate
	}
	manifestRaw := make([]byte, manifestSize)
	if _, err = io.ReadFull(reader, manifestRaw); err != nil {
		_ = file.Close()
		return accepted, ErrInvalidUpdate
	}
	manifest, err := DecodeManifest(strings.NewReader(string(manifestRaw)))
	clear(manifestRaw)
	if err != nil {
		_ = file.Close()
		return accepted, ErrInvalidUpdate
	}
	hasher := sha256.New()
	written, copyErr := io.CopyN(io.MultiWriter(file, hasher), reader, manifest.ArchiveSize)
	if copyErr != nil || written != manifest.ArchiveSize {
		_ = file.Close()
		return accepted, ErrInvalidUpdate
	}
	extra, readErr := io.ReadAll(io.LimitReader(reader, 1))
	if readErr != nil || len(extra) != 0 {
		_ = file.Close()
		return accepted, ErrInvalidUpdate
	}
	if err = file.Sync(); err == nil {
		err = file.Close()
	} else {
		_ = file.Close()
	}
	if err != nil {
		return accepted, ErrUpdateStorage
	}
	var archiveHash [sha256.Size]byte
	copy(archiveHash[:], hasher.Sum(nil))
	if err := manifest.Verify(s.publicKey, s.keyID, archiveHash, manifest.ArchiveSize); err != nil {
		return accepted, ErrInvalidUpdate
	}
	comparison, err := CompareVersions(manifest.Version, s.currentVersion)
	if err != nil || comparison < 0 || (comparison == 0 && manifest.BinarySHA256 == s.currentBinarySHA256) {
		return accepted, ErrInvalidUpdate
	}
	if err := ValidateBundle(uploadPath, manifest); err != nil {
		return accepted, ErrInvalidUpdate
	}
	operationID, err := randomHex(s.random)
	if err != nil {
		return accepted, ErrUpdateStorage
	}
	archiveFile := "pending-" + operationID + ".tar.gz"
	archivePath := filepath.Join(s.updateDirectory, archiveFile)
	if err := os.Rename(uploadPath, archivePath); err != nil {
		return accepted, ErrUpdateStorage
	}
	uploadPath = archivePath
	request := PendingRequest{
		SchemaVersion: 1, OperationID: operationID, ArchiveFile: archiveFile,
		CurrentVersion: s.currentVersion, CurrentBinarySHA256: s.currentBinarySHA256,
		TargetVersion: manifest.Version, Manifest: manifest,
	}
	if err := writeJSONAtomic(requestPath, request, 0o600); err != nil {
		return accepted, ErrUpdateStorage
	}
	keepArchive = true
	return AcceptedUpdate{OperationID: operationID, TargetVersion: manifest.Version}, nil
}

func ReadPendingRequest(requestPath, updateDirectory string) (PendingRequest, error) {
	if filepath.Clean(requestPath) != filepath.Join(filepath.Clean(updateDirectory), "pending.json") {
		return PendingRequest{}, ErrInvalidUpdate
	}
	info, err := os.Lstat(requestPath)
	if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 ||
		(runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0) {
		return PendingRequest{}, ErrInvalidUpdate
	}
	raw, err := os.ReadFile(requestPath)
	if err != nil || len(raw) == 0 || len(raw) > MaximumManifestBytes {
		return PendingRequest{}, ErrInvalidUpdate
	}
	decoder := json.NewDecoder(strings.NewReader(string(raw)))
	decoder.DisallowUnknownFields()
	var request PendingRequest
	if err := decoder.Decode(&request); err != nil || requireJSONEOF(decoder) != nil {
		return PendingRequest{}, ErrInvalidUpdate
	}
	if request.SchemaVersion != 1 || !operationPattern.MatchString(request.OperationID) ||
		request.ArchiveFile != "pending-"+request.OperationID+".tar.gz" ||
		request.TargetVersion != request.Manifest.Version || !semverPattern.MatchString(request.CurrentVersion) ||
		!sha256Pattern.MatchString(request.CurrentBinarySHA256) {
		return PendingRequest{}, ErrInvalidUpdate
	}
	return request, nil
}

func VerifyPendingRequest(request PendingRequest, updateDirectory string, publicKey ed25519.PublicKey, keyID string) (string, error) {
	if request.ArchiveFile != filepath.Base(request.ArchiveFile) {
		return "", ErrInvalidUpdate
	}
	archivePath := filepath.Join(updateDirectory, request.ArchiveFile)
	info, err := os.Lstat(archivePath)
	if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 ||
		(runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0) || info.Size() != request.Manifest.ArchiveSize {
		return "", ErrInvalidUpdate
	}
	file, err := os.Open(archivePath)
	if err != nil {
		return "", ErrInvalidUpdate
	}
	hasher := sha256.New()
	size, hashErr := io.Copy(hasher, io.LimitReader(file, MaximumArchiveBytes+1))
	closeErr := file.Close()
	if hashErr != nil || closeErr != nil {
		return "", ErrInvalidUpdate
	}
	var sum [sha256.Size]byte
	copy(sum[:], hasher.Sum(nil))
	if err := request.Manifest.Verify(publicKey, keyID, sum, size); err != nil {
		return "", ErrInvalidUpdate
	}
	if err := ValidateBundle(archivePath, request.Manifest); err != nil {
		return "", ErrInvalidUpdate
	}
	return archivePath, nil
}

func ValidateBundle(archivePath string, manifest Manifest) error {
	file, err := os.Open(archivePath)
	if err != nil {
		return err
	}
	defer file.Close()
	gzipReader, err := gzip.NewReader(io.LimitReader(file, MaximumArchiveBytes+1))
	if err != nil {
		return err
	}
	defer gzipReader.Close()
	tarReader := tar.NewReader(gzipReader)
	actual := map[string]string{}
	bodies := map[string][]byte{}
	for count := 0; ; count++ {
		if count > 64 {
			return errors.New("too many bundle entries")
		}
		header, err := tarReader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		if header.Typeflag != tar.TypeReg && header.Typeflag != tar.TypeRegA {
			return errors.New("non-regular bundle entry")
		}
		if header.Name == "" || path.Base(header.Name) != header.Name || strings.Contains(header.Name, "\\") || header.Size < 0 || header.Size > MaximumArchiveBytes {
			return errors.New("unsafe bundle path")
		}
		if _, duplicate := actual[header.Name]; duplicate {
			return errors.New("duplicate bundle entry")
		}
		body, err := io.ReadAll(io.LimitReader(tarReader, header.Size+1))
		if err != nil || int64(len(body)) != header.Size {
			return errors.New("truncated bundle entry")
		}
		sum := sha256.Sum256(body)
		actual[header.Name] = hex.EncodeToString(sum[:])
		bodies[header.Name] = body
	}
	required := []string{"VERSION", "SHA256SUMS", "keenwg-companion", "keenwg-updater", "install-companion.sh", "S96keenwg-companion", "uninstall-companion.sh", "cleanup-obsolete-controller.sh", "companion.config.example.json"}
	for _, name := range required {
		if _, ok := actual[name]; !ok {
			return fmt.Errorf("missing %s", name)
		}
	}
	if string(bodies["VERSION"]) != manifest.Version+"\n" || actual["keenwg-companion"] != manifest.BinarySHA256 {
		return errors.New("bundle identity mismatch")
	}
	expected, err := parseChecksums(bodies["SHA256SUMS"])
	if err != nil {
		return err
	}
	for name, sum := range actual {
		if name == "SHA256SUMS" {
			continue
		}
		if expected[name] != sum {
			return errors.New("bundle checksum mismatch")
		}
	}
	for name := range expected {
		if name == "SHA256SUMS" || actual[name] == "" {
			return errors.New("checksum for absent file")
		}
	}
	return nil
}

func ExtractBundle(archivePath, destination string) error {
	if err := ensurePrivateDirectory(destination); err != nil {
		return err
	}
	file, err := os.Open(archivePath)
	if err != nil {
		return err
	}
	defer file.Close()
	gzipReader, err := gzip.NewReader(file)
	if err != nil {
		return err
	}
	defer gzipReader.Close()
	tarReader := tar.NewReader(gzipReader)
	for {
		header, err := tarReader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		if header.Typeflag != tar.TypeReg && header.Typeflag != tar.TypeRegA || path.Base(header.Name) != header.Name {
			return ErrInvalidUpdate
		}
		target := filepath.Join(destination, header.Name)
		mode := os.FileMode(0o600)
		if header.Name == "keenwg-companion" || header.Name == "keenwg-updater" || strings.HasSuffix(header.Name, ".sh") || strings.HasPrefix(header.Name, "S96") {
			mode = 0o700
		}
		output, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_EXCL, mode)
		if err != nil {
			return err
		}
		_, copyErr := io.CopyN(output, tarReader, header.Size)
		if syncErr := output.Sync(); copyErr == nil {
			copyErr = syncErr
		}
		if closeErr := output.Close(); copyErr == nil {
			copyErr = closeErr
		}
		if copyErr != nil {
			return copyErr
		}
	}
	return nil
}

func ReadStatus(path string) (Status, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return Status{}, err
	}
	decoder := json.NewDecoder(strings.NewReader(string(raw)))
	decoder.DisallowUnknownFields()
	var status Status
	if err := decoder.Decode(&status); err != nil || requireJSONEOF(decoder) != nil || status.SchemaVersion != 1 {
		return Status{}, ErrInvalidUpdate
	}
	return status, nil
}

func WriteStatus(path string, status Status) error {
	status.SchemaVersion = 1
	return writeJSONAtomic(path, status, 0o600)
}

func parseChecksums(body []byte) (map[string]string, error) {
	result := map[string]string{}
	scanner := bufio.NewScanner(bytesReader(body))
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) != 2 || !sha256Pattern.MatchString(fields[0]) || path.Base(strings.TrimPrefix(fields[1], "*")) != strings.TrimPrefix(fields[1], "*") {
			return nil, errors.New("invalid checksum document")
		}
		name := strings.TrimPrefix(fields[1], "*")
		if _, exists := result[name]; exists {
			return nil, errors.New("duplicate checksum")
		}
		result[name] = fields[0]
	}
	if err := scanner.Err(); err != nil || len(result) == 0 {
		return nil, errors.New("invalid checksum document")
	}
	return result, nil
}

func bytesReader(body []byte) io.Reader { return strings.NewReader(string(body)) }

func randomHex(reader io.Reader) (string, error) {
	var value [16]byte
	if _, err := io.ReadFull(reader, value[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(value[:]), nil
}

func ensurePrivateDirectory(directory string) error {
	if filepath.Clean(directory) != directory || !filepath.IsAbs(directory) {
		return errors.New("unsafe update directory")
	}
	if info, err := os.Lstat(directory); err == nil {
		if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return errors.New("unsafe update directory")
		}
		return os.Chmod(directory, 0o700)
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	return os.Chmod(directory, 0o700)
}

func writeJSONAtomic(path string, value any, mode os.FileMode) error {
	body, err := json.Marshal(value)
	if err != nil {
		return err
	}
	body = append(body, '\n')
	directory := filepath.Dir(path)
	file, err := os.CreateTemp(directory, ".state-")
	if err != nil {
		return err
	}
	temporary := file.Name()
	committed := false
	defer func() {
		if !committed {
			_ = os.Remove(temporary)
		}
	}()
	if err = file.Chmod(mode); err == nil {
		_, err = file.Write(body)
	}
	if err == nil {
		err = file.Sync()
	}
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	if err = os.Rename(temporary, path); err != nil {
		return err
	}
	committed = true
	return nil
}
