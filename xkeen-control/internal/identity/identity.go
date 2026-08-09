package identity

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"time"
)

var (
	ErrUnsafePath        = errors.New("unsafe_identity_path")
	ErrUnsafePermissions = errors.New("unsafe_identity_permissions")
	ErrIdentityCorrupt   = errors.New("identity_corrupt")
	ErrInvalidAddress    = errors.New("invalid_identity_address")
	ErrIdentityIO        = errors.New("identity_io")
)

type Identity struct {
	CertificatePath string
	PrivateKeyPath  string
	SPKIPin         string
}

func Ensure(certPath, keyPath string, addresses []net.IP, now time.Time) (Identity, error) {
	certExists, err := safePathState(certPath)
	if err != nil {
		return Identity{}, err
	}
	keyExists, err := safePathState(keyPath)
	if err != nil {
		return Identity{}, err
	}
	if certExists && keyExists {
		_, pin, err := Load(certPath, keyPath)
		return Identity{CertificatePath: certPath, PrivateKeyPath: keyPath, SPKIPin: pin}, err
	}
	if certExists != keyExists {
		return Identity{}, ErrIdentityCorrupt
	}

	uniqueAddresses, err := normalizeAddresses(addresses)
	if err != nil {
		return Identity{}, err
	}
	privateKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return Identity{}, fmt.Errorf("%w: generate key", ErrIdentityIO)
	}
	serialBytes := make([]byte, 16)
	if _, err := rand.Read(serialBytes); err != nil {
		return Identity{}, fmt.Errorf("%w: generate serial", ErrIdentityIO)
	}
	serialBytes[0] &= 0x7f
	serialBytes[len(serialBytes)-1] |= 1
	template := &x509.Certificate{
		SerialNumber:          new(big.Int).SetBytes(serialBytes),
		Subject:               pkix.Name{CommonName: "KeenWG Companion"},
		NotBefore:             now.UTC().Add(-5 * time.Minute),
		NotAfter:              now.UTC().AddDate(5, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IPAddresses:           uniqueAddresses,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &privateKey.PublicKey, privateKey)
	if err != nil {
		return Identity{}, fmt.Errorf("%w: create certificate", ErrIdentityIO)
	}
	keyDER, err := x509.MarshalECPrivateKey(privateKey)
	if err != nil {
		return Identity{}, fmt.Errorf("%w: marshal key", ErrIdentityIO)
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})

	keyTemp, err := stagePrivate(keyPath, keyPEM)
	if err != nil {
		return Identity{}, err
	}
	defer os.Remove(keyTemp)
	certTemp, err := stagePrivate(certPath, certPEM)
	if err != nil {
		return Identity{}, err
	}
	defer os.Remove(certTemp)
	if err := os.Rename(keyTemp, keyPath); err != nil {
		return Identity{}, fmt.Errorf("%w: publish key", ErrIdentityIO)
	}
	if err := os.Rename(certTemp, certPath); err != nil {
		_ = os.Remove(keyPath)
		return Identity{}, fmt.Errorf("%w: publish certificate", ErrIdentityIO)
	}
	if err := syncDirectory(filepath.Dir(certPath)); err != nil {
		return Identity{}, err
	}
	_, pin, err := Load(certPath, keyPath)
	if err != nil {
		return Identity{}, err
	}
	return Identity{CertificatePath: certPath, PrivateKeyPath: keyPath, SPKIPin: pin}, nil
}

func Load(certPath, keyPath string) (tls.Certificate, string, error) {
	for _, path := range []string{certPath, keyPath} {
		info, err := os.Lstat(path)
		if err != nil || !info.Mode().IsRegular() {
			return tls.Certificate{}, "", ErrIdentityCorrupt
		}
		if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
			return tls.Certificate{}, "", ErrUnsafePermissions
		}
	}
	certPEM, err := readBounded(certPath, 64*1024)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	keyPEM, err := readBounded(keyPath, 64*1024)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	pair, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil || len(pair.Certificate) != 1 {
		return tls.Certificate{}, "", ErrIdentityCorrupt
	}
	certificate, err := x509.ParseCertificate(pair.Certificate[0])
	if err != nil || certificate.IsCA || time.Now().Before(certificate.NotBefore) || time.Now().After(certificate.NotAfter) {
		return tls.Certificate{}, "", ErrIdentityCorrupt
	}
	if err := certificate.CheckSignature(certificate.SignatureAlgorithm, certificate.RawTBSCertificate, certificate.Signature); err != nil {
		return tls.Certificate{}, "", ErrIdentityCorrupt
	}
	if !containsServerAuth(certificate.ExtKeyUsage) {
		return tls.Certificate{}, "", ErrIdentityCorrupt
	}
	pair.Leaf = certificate
	digest := sha256.Sum256(certificate.RawSubjectPublicKeyInfo)
	pin := "sha256/" + base64.StdEncoding.EncodeToString(digest[:])
	return pair, pin, nil
}

func normalizeAddresses(addresses []net.IP) ([]net.IP, error) {
	seen := make(map[string]struct{}, len(addresses))
	result := make([]net.IP, 0, len(addresses))
	for _, address := range addresses {
		if address == nil || address.IsUnspecified() || address.IsMulticast() {
			return nil, ErrInvalidAddress
		}
		if ipv4 := address.To4(); ipv4 != nil {
			address = append(net.IP(nil), ipv4...)
		} else if ipv6 := address.To16(); ipv6 != nil {
			address = append(net.IP(nil), ipv6...)
		} else {
			return nil, ErrInvalidAddress
		}
		key := address.String()
		if _, exists := seen[key]; exists {
			continue
		}
		seen[key] = struct{}{}
		result = append(result, address)
	}
	if len(result) == 0 {
		return nil, ErrInvalidAddress
	}
	return result, nil
}

func safePathState(path string) (bool, error) {
	if path == "" || !filepath.IsAbs(path) || filepath.Clean(path) != path {
		return false, ErrUnsafePath
	}
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("%w: stat", ErrIdentityIO)
	}
	if !info.Mode().IsRegular() {
		return false, ErrUnsafePath
	}
	return true, nil
}

func stagePrivate(target string, body []byte) (string, error) {
	if exists, err := safePathState(target); err != nil || exists {
		if err != nil {
			return "", err
		}
		return "", ErrIdentityCorrupt
	}
	directory := filepath.Dir(target)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return "", fmt.Errorf("%w: mkdir", ErrIdentityIO)
	}
	if err := os.Chmod(directory, 0o700); err != nil {
		return "", fmt.Errorf("%w: chmod directory", ErrIdentityIO)
	}
	file, err := os.CreateTemp(directory, "."+filepath.Base(target)+".tmp-*")
	if err != nil {
		return "", fmt.Errorf("%w: create temp", ErrIdentityIO)
	}
	temporary := file.Name()
	success := false
	defer func() {
		_ = file.Close()
		if !success {
			_ = os.Remove(temporary)
		}
	}()
	if err := file.Chmod(0o600); err != nil {
		return "", fmt.Errorf("%w: chmod", ErrIdentityIO)
	}
	if _, err := file.Write(body); err != nil {
		return "", fmt.Errorf("%w: write", ErrIdentityIO)
	}
	if err := file.Sync(); err != nil {
		return "", fmt.Errorf("%w: sync", ErrIdentityIO)
	}
	if err := file.Close(); err != nil {
		return "", fmt.Errorf("%w: close", ErrIdentityIO)
	}
	success = true
	return temporary, nil
}

func readBounded(path string, limit int64) ([]byte, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("%w: open", ErrIdentityIO)
	}
	defer file.Close()
	body, err := io.ReadAll(io.LimitReader(file, limit+1))
	if err != nil {
		return nil, fmt.Errorf("%w: read", ErrIdentityIO)
	}
	if int64(len(body)) > limit {
		return nil, ErrIdentityCorrupt
	}
	block, rest := pem.Decode(body)
	if block == nil || len(bytes.TrimSpace(rest)) != 0 {
		return nil, ErrIdentityCorrupt
	}
	return body, nil
}

func containsServerAuth(usages []x509.ExtKeyUsage) bool {
	for _, usage := range usages {
		if usage == x509.ExtKeyUsageServerAuth {
			return true
		}
	}
	return false
}

func syncDirectory(path string) error {
	directory, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("%w: open directory", ErrIdentityIO)
	}
	defer directory.Close()
	if err := directory.Sync(); err != nil {
		if runtime.GOOS == "windows" {
			return nil
		}
		return fmt.Errorf("%w: sync directory", ErrIdentityIO)
	}
	return nil
}
