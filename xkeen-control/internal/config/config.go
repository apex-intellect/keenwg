package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"net/url"
	"path"
	"strconv"
	"strings"
)

var ErrInvalidConfig = errors.New("invalid_config")

type Config struct {
	ListenAddress       string           `json:"listen_address"`
	SecureListenAddress string           `json:"secure_listen_address,omitempty"`
	Token               string           `json:"token"`
	SubscriptionURL     string           `json:"subscription_url"`
	SubscriptionCache   string           `json:"subscription_cache_path"`
	StatePath           string           `json:"state_path"`
	BackupDir           string           `json:"backup_dir"`
	OutboundsPath       string           `json:"outbounds_path"`
	ExcludePath         string           `json:"exclude_path"`
	DomainPolicyPath    string           `json:"domain_policy_path"`
	DomainPolicyBackup  string           `json:"domain_policy_backup_path"`
	RoutingPath         string           `json:"routing_path"`
	InitScript          string           `json:"init_script"`
	XrayBinary          string           `json:"xray_binary"`
	AssetDir            string           `json:"asset_dir"`
	MaxSubscriptionSize int64            `json:"max_subscription_bytes"`
	MaxNodes            int              `json:"max_nodes"`
	AllowPrivateServers bool             `json:"allow_private_servers"`
	Discovery           DiscoveryConfig  `json:"discovery,omitempty"`
	TLSCertificatePath  string           `json:"tls_certificate_path,omitempty"`
	TLSPrivateKeyPath   string           `json:"tls_private_key_path,omitempty"`
	DeviceStorePath     string           `json:"device_store_path,omitempty"`
	PairingStorePath    string           `json:"pairing_store_path,omitempty"`
	CatalogPath         string           `json:"catalog_path,omitempty"`
	CatalogSecretsPath  string           `json:"catalog_secrets_path,omitempty"`
	RecoveryPath        string           `json:"recovery_path,omitempty"`
	LegacyAPIEnabled    bool             `json:"legacy_api_enabled"`
	SingBox             SingBoxConfig    `json:"singbox,omitempty"`
	AWGManager          AWGManagerConfig `json:"awg_manager,omitempty"`
}

type DiscoveryConfig struct {
	XKeenInitPath string `json:"xkeen_init_path,omitempty"`
	ASCPath       string `json:"asc_path,omitempty"`
}

type SingBoxConfig struct {
	Enabled       bool   `json:"enabled"`
	ControllerURL string `json:"controller_url,omitempty"`
	Secret        string `json:"secret,omitempty"`
	Selector      string `json:"selector,omitempty"`
}

type AWGManagerConfig struct {
	Enabled  bool   `json:"enabled"`
	BaseURL  string `json:"base_url,omitempty"`
	Login    string `json:"login,omitempty"`
	Password string `json:"password,omitempty"`
}

func Decode(r io.Reader) (Config, error) {
	cfg := Config{LegacyAPIEnabled: true}
	decoder := json.NewDecoder(r)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&cfg); err != nil {
		return Config{}, invalid(err)
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return Config{}, invalid(err)
	}
	cfg.applyDefaults()
	if err := cfg.Validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

func (c Config) Validate() error {
	if err := validateListener(c.ListenAddress); err != nil {
		return err
	}
	if c.SecureListenAddress != "" {
		if err := validateListener(c.SecureListenAddress); err != nil {
			return err
		}
		if c.SecureListenAddress == c.ListenAddress {
			return invalid(nil)
		}
	} else if !c.LegacyAPIEnabled {
		return invalid(nil)
	}
	if len(c.Token) < 32 || len(c.Token) > 256 || strings.TrimSpace(c.Token) != c.Token {
		return invalid(nil)
	}
	if err := validateSubscriptionURL(c.SubscriptionURL); err != nil {
		return err
	}
	for _, value := range []string{
		c.SubscriptionCache,
		c.StatePath,
		c.BackupDir,
		c.OutboundsPath,
		c.ExcludePath,
		c.DomainPolicyPath,
		c.DomainPolicyBackup,
		c.RoutingPath,
		c.InitScript,
		c.XrayBinary,
		c.AssetDir,
		c.Discovery.XKeenInitPath,
		c.Discovery.ASCPath,
		c.TLSCertificatePath,
		c.TLSPrivateKeyPath,
		c.DeviceStorePath,
		c.PairingStorePath,
		c.CatalogPath,
		c.CatalogSecretsPath,
		c.RecoveryPath,
	} {
		if !isOptPath(value) {
			return invalid(nil)
		}
	}
	if c.MaxSubscriptionSize < 1 || c.MaxSubscriptionSize > 1_048_576 {
		return invalid(nil)
	}
	if c.MaxNodes < 1 || c.MaxNodes > 512 {
		return invalid(nil)
	}
	if err := validateSingBox(c.SingBox); err != nil {
		return err
	}
	if err := validateAWGManager(c.AWGManager); err != nil {
		return err
	}
	if c.TLSCertificatePath == c.TLSPrivateKeyPath || !distinctPaths(
		c.DeviceStorePath, c.PairingStorePath, c.CatalogPath, c.CatalogSecretsPath, c.RecoveryPath,
	) {
		return invalid(nil)
	}
	return nil
}

func validateAWGManager(config AWGManagerConfig) error {
	configured := config.BaseURL != "" || config.Login != "" || config.Password != ""
	if !configured {
		if config.Enabled {
			return invalid(nil)
		}
		return nil
	}
	parsed, err := url.Parse(config.BaseURL)
	if err != nil || parsed.Scheme != "http" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" ||
		(parsed.Path != "" && parsed.Path != "/") || parsed.Port() == "" {
		return invalid(err)
	}
	address, addressErr := netip.ParseAddr(parsed.Hostname())
	port, portErr := strconv.Atoi(parsed.Port())
	if addressErr != nil || !address.IsLoopback() || portErr != nil || port < 1 || port > 65535 ||
		config.Login == "" || len(config.Login) > 128 || strings.TrimSpace(config.Login) != config.Login ||
		config.Password == "" || len(config.Password) > 512 || strings.ContainsAny(config.Login+config.Password, "\r\n\x00") {
		return invalid(nil)
	}
	return nil
}

func validateSingBox(config SingBoxConfig) error {
	configured := config.ControllerURL != "" || config.Secret != "" || config.Selector != ""
	if !configured {
		if config.Enabled {
			return invalid(nil)
		}
		return nil
	}
	parsed, err := url.Parse(config.ControllerURL)
	if err != nil || parsed.Scheme != "http" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" ||
		(parsed.Path != "" && parsed.Path != "/") || parsed.Port() == "" {
		return invalid(err)
	}
	address, addressErr := netip.ParseAddr(parsed.Hostname())
	port, portErr := strconv.Atoi(parsed.Port())
	if addressErr != nil || !address.IsLoopback() || portErr != nil || port < 1 || port > 65535 ||
		config.Secret == "" || len(config.Secret) > 256 || strings.TrimSpace(config.Secret) != config.Secret ||
		strings.ContainsAny(config.Secret, "\r\n\x00 \t") || config.Selector == "" ||
		len([]rune(config.Selector)) > 128 || strings.TrimSpace(config.Selector) != config.Selector ||
		strings.ContainsAny(config.Selector, "/?#\r\n\x00") {
		return invalid(nil)
	}
	return nil
}

func distinctPaths(values ...string) bool {
	seen := make(map[string]struct{}, len(values))
	for _, value := range values {
		if _, exists := seen[value]; exists {
			return false
		}
		seen[value] = struct{}{}
	}
	return true
}

func (c *Config) applyDefaults() {
	if c.Discovery.XKeenInitPath == "" {
		c.Discovery.XKeenInitPath = "/opt/etc/init.d/S05xkeen"
	}
	if c.Discovery.ASCPath == "" {
		c.Discovery.ASCPath = "/opt/sbin/asc"
	}
	if c.TLSCertificatePath == "" {
		c.TLSCertificatePath = "/opt/etc/keenwg/identity/certificate.pem"
	}
	if c.TLSPrivateKeyPath == "" {
		c.TLSPrivateKeyPath = "/opt/etc/keenwg/identity/private-key.pem"
	}
	if c.DeviceStorePath == "" {
		c.DeviceStorePath = "/opt/etc/keenwg/devices.json"
	}
	if c.PairingStorePath == "" {
		c.PairingStorePath = "/opt/etc/keenwg/pairing-offers.json"
	}
	if c.CatalogPath == "" {
		c.CatalogPath = "/opt/etc/keenwg/catalog.json"
	}
	if c.CatalogSecretsPath == "" {
		c.CatalogSecretsPath = "/opt/etc/keenwg/catalog-secrets.json"
	}
	if c.RecoveryPath == "" {
		c.RecoveryPath = "/opt/etc/keenwg/recovery.json"
	}
}

func validateListener(raw string) error {
	host, portText, err := net.SplitHostPort(raw)
	if err != nil || strings.Contains(host, "%") {
		return invalid(err)
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		return invalid(err)
	}
	addr, err := netip.ParseAddr(host)
	if err != nil || !addr.Is4() || !isPrivateListener(addr) {
		return invalid(err)
	}
	return nil
}

func isPrivateListener(addr netip.Addr) bool {
	if addr.IsPrivate() {
		return true
	}
	cgnat := netip.MustParsePrefix("100.64.0.0/10")
	return cgnat.Contains(addr)
}

func validateSubscriptionURL(raw string) error {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.Fragment != "" {
		return invalid(err)
	}
	if u.Hostname() == "" {
		return invalid(nil)
	}
	return nil
}

func isOptPath(value string) bool {
	if value == "" || strings.Contains(value, "\\") || !path.IsAbs(value) {
		return false
	}
	clean := path.Clean(value)
	return clean == value && strings.HasPrefix(clean, "/opt/")
}

func invalid(cause error) error {
	if cause == nil {
		return ErrInvalidConfig
	}
	return fmt.Errorf("%w", ErrInvalidConfig)
}
