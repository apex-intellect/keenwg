package app

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/config"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/configupgrade"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/identity"
)

const maxBootstrapDocument = 64 << 10

type BootstrapResult struct {
	BaseURL        string
	CertificatePin string
}

type PairingOfferResult struct {
	BaseURL        string          `json:"base_url"`
	CertificatePin string          `json:"certificate_pin"`
	Offer          auth.PlainOffer `json:"offer"`
}

type bootstrapRequest struct {
	SchemaVersion       int    `json:"schema_version"`
	SecureListenAddress string `json:"secure_listen_address"`
}

func BootstrapNative(targetPath, requestPath, root string, now time.Time) (BootstrapResult, error) {
	if err := validateTestRoot(root); err != nil {
		return BootstrapResult{}, err
	}
	if _, err := os.Lstat(targetPath); err == nil {
		return BootstrapResult{}, errors.New("companion config already exists")
	} else if !errors.Is(err, os.ErrNotExist) {
		return BootstrapResult{}, errors.New("companion config unavailable")
	}
	request, err := loadBootstrapRequest(requestPath)
	if err != nil {
		return BootstrapResult{}, err
	}
	cfg := config.NewSecure(request.SecureListenAddress)
	if err := cfg.Validate(); err != nil {
		return BootstrapResult{}, err
	}
	created, err := identity.Ensure(
		rootedPath(root, cfg.TLSCertificatePath),
		rootedPath(root, cfg.TLSPrivateKeyPath),
		[]net.IP{net.ParseIP(strings.Split(cfg.SecureListenAddress, ":")[0])}, now,
	)
	if err != nil {
		return BootstrapResult{}, err
	}
	if err := writeConfigCreateOnly(targetPath, cfg); err != nil {
		return BootstrapResult{}, err
	}
	return BootstrapResult{BaseURL: secureBaseURL(cfg.SecureListenAddress), CertificatePin: created.SPKIPin}, nil
}

func UpgradeCompanionConfig(targetPath, root string) error {
	if err := validateTestRoot(root); err != nil {
		return err
	}
	if !filepath.IsAbs(targetPath) || filepath.Clean(targetPath) != targetPath {
		return errors.New("unsafe companion config path")
	}
	if root != "" {
		prefix := root + string(filepath.Separator)
		if !strings.HasPrefix(targetPath, prefix) {
			return errors.New("unsafe companion config path")
		}
	}
	info, err := os.Lstat(targetPath)
	if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 {
		return errors.New("companion config unavailable")
	}
	source, err := os.Open(targetPath)
	if err != nil {
		return errors.New("companion config unavailable")
	}
	next, upgradeErr := configupgrade.UpgradeV1(source)
	closeErr := source.Close()
	if upgradeErr != nil {
		return upgradeErr
	}
	if closeErr != nil {
		return errors.New("companion config unavailable")
	}
	temporary := targetPath + ".upgrade"
	file, err := os.OpenFile(temporary, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return errors.New("companion config upgrade unavailable")
	}
	committed := false
	defer func() {
		_ = file.Close()
		if !committed {
			_ = os.Remove(temporary)
		}
	}()
	if err := file.Chmod(0o600); err != nil {
		return errors.New("config permissions unavailable")
	}
	if err := config.Encode(file, next); err != nil {
		return err
	}
	if err := file.Sync(); err != nil {
		return errors.New("config sync failed")
	}
	if err := file.Close(); err != nil {
		return errors.New("config write failed")
	}
	if err := os.Rename(temporary, targetPath); err != nil {
		if runtime.GOOS != "windows" {
			return errors.New("config replace failed")
		}
		if err := os.Remove(targetPath); err != nil {
			return errors.New("config replace failed")
		}
		if err := os.Rename(temporary, targetPath); err != nil {
			return errors.New("config replace failed")
		}
	}
	committed = true
	return nil
}

func CreatePairingOffer(configPath, root string, scope auth.Scope, ttl time.Duration) (PairingOfferResult, error) {
	if err := validateTestRoot(root); err != nil {
		return PairingOfferResult{}, err
	}
	cfg, err := LoadConfig(configPath)
	if err != nil {
		return PairingOfferResult{}, err
	}
	if cfg.SecureListenAddress == "" {
		return PairingOfferResult{}, config.ErrInvalidConfig
	}
	_, pin, err := identity.Load(rootedPath(root, cfg.TLSCertificatePath), rootedPath(root, cfg.TLSPrivateKeyPath))
	if err != nil {
		return PairingOfferResult{}, err
	}
	store, err := auth.NewFileStore(rootedPath(root, cfg.DeviceStorePath), rootedPath(root, cfg.PairingStorePath))
	if err != nil {
		return PairingOfferResult{}, err
	}
	if scope != auth.ScopeOwner {
		return PairingOfferResult{}, auth.ErrInvalidScope
	}
	ctx := context.Background()
	devices, err := store.ListDevices(ctx)
	if err != nil {
		return PairingOfferResult{}, err
	}
	var offer auth.PlainOffer
	if len(devices) == 0 {
		offer, err = store.CreateBootstrapOffer(ctx, scope, ttl)
	} else {
		// This recovery branch is reachable only through the local root-owned
		// CLI after Android has verified the router's SSH host key and
		// authenticated over SSH. The HTTPS endpoint still requires an
		// existing owner token to issue another invitation.
		offer, err = store.CreateOffer(ctx, auth.ScopeOwner, scope, ttl)
	}
	if err != nil {
		return PairingOfferResult{}, err
	}
	return PairingOfferResult{BaseURL: secureBaseURL(cfg.SecureListenAddress), CertificatePin: pin, Offer: offer}, nil
}

func LoadConfig(path string) (config.Config, error) {
	file, err := os.Open(path)
	if err != nil {
		return config.Config{}, errors.New("config unavailable")
	}
	defer file.Close()
	cfg, err := config.Decode(io.LimitReader(file, maxBootstrapDocument))
	if err != nil {
		return config.Config{}, err
	}
	return cfg, nil
}

func CheckCompanion(configPath, root string) error {
	if err := validateTestRoot(root); err != nil {
		return err
	}
	cfg, err := LoadConfig(configPath)
	if err != nil {
		return err
	}
	if cfg.SecureListenAddress == "" {
		return config.ErrInvalidConfig
	}
	if _, _, err := identity.Load(rootedPath(root, cfg.TLSCertificatePath), rootedPath(root, cfg.TLSPrivateKeyPath)); err != nil {
		return err
	}
	_, err = auth.NewFileStore(rootedPath(root, cfg.DeviceStorePath), rootedPath(root, cfg.PairingStorePath))
	return err
}

func loadBootstrapRequest(path string) (bootstrapRequest, error) {
	file, err := os.Open(path)
	if err != nil {
		return bootstrapRequest{}, errors.New("bootstrap request unavailable")
	}
	defer file.Close()
	decoder := json.NewDecoder(io.LimitReader(file, 4<<10))
	decoder.DisallowUnknownFields()
	var request bootstrapRequest
	if err := decoder.Decode(&request); err != nil {
		return bootstrapRequest{}, config.ErrInvalidConfig
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return bootstrapRequest{}, config.ErrInvalidConfig
	}
	if request.SchemaVersion != 1 || request.SecureListenAddress == "" {
		return bootstrapRequest{}, config.ErrInvalidConfig
	}
	return request, nil
}

func writeConfigCreateOnly(path string, cfg config.Config) error {
	if path == "" || !filepath.IsAbs(path) || filepath.Clean(path) != path {
		return errors.New("unsafe config path")
	}
	body, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return errors.New("config encoding failed")
	}
	body = append(body, '\n')
	directory := filepath.Dir(path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return errors.New("config directory unavailable")
	}
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return errors.New("companion config already exists")
	}
	success := false
	defer func() {
		_ = file.Close()
		if !success {
			_ = os.Remove(path)
		}
	}()
	if err := file.Chmod(0o600); err != nil {
		return errors.New("config permissions unavailable")
	}
	if _, err := file.Write(body); err != nil {
		return errors.New("config write failed")
	}
	if err := file.Sync(); err != nil {
		return errors.New("config sync failed")
	}
	if err := file.Close(); err != nil {
		return errors.New("config close failed")
	}
	success = true
	return nil
}

func rootedPath(root, path string) string {
	if root == "" {
		return filepath.FromSlash(path)
	}
	return filepath.Join(root, filepath.FromSlash(strings.TrimPrefix(path, "/")))
}

func validateTestRoot(root string) error {
	if root == "" {
		return nil
	}
	if !filepath.IsAbs(root) || filepath.Clean(root) != root || root == filepath.VolumeName(root)+string(filepath.Separator) {
		return errors.New("unsafe destination root")
	}
	info, err := os.Stat(root)
	if err != nil || !info.IsDir() {
		return errors.New("destination root unavailable")
	}
	return nil
}

func secureBaseURL(address string) string {
	return (&url.URL{Scheme: "https", Host: address}).String()
}
