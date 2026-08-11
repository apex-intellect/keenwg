package configupgrade

import (
	"encoding/json"
	"errors"
	"io"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/config"
)

type configV1 struct {
	SchemaVersion       int                     `json:"schema_version,omitempty"`
	ListenAddress       string                  `json:"listen_address"`
	SecureListenAddress string                  `json:"secure_listen_address,omitempty"`
	Token               string                  `json:"token"`
	SubscriptionURL     string                  `json:"subscription_url"`
	SubscriptionCache   string                  `json:"subscription_cache_path"`
	StatePath           string                  `json:"state_path"`
	BackupDir           string                  `json:"backup_dir"`
	OutboundsPath       string                  `json:"outbounds_path"`
	ExcludePath         string                  `json:"exclude_path"`
	DomainPolicyPath    string                  `json:"domain_policy_path"`
	DomainPolicyBackup  string                  `json:"domain_policy_backup_path"`
	RoutingPath         string                  `json:"routing_path"`
	InitScript          string                  `json:"init_script"`
	XrayBinary          string                  `json:"xray_binary"`
	AssetDir            string                  `json:"asset_dir"`
	MaxSubscriptionSize int64                   `json:"max_subscription_bytes"`
	MaxNodes            int                     `json:"max_nodes"`
	AllowPrivateServers bool                    `json:"allow_private_servers"`
	Discovery           config.DiscoveryConfig  `json:"discovery,omitempty"`
	TLSCertificatePath  string                  `json:"tls_certificate_path,omitempty"`
	TLSPrivateKeyPath   string                  `json:"tls_private_key_path,omitempty"`
	DeviceStorePath     string                  `json:"device_store_path,omitempty"`
	PairingStorePath    string                  `json:"pairing_store_path,omitempty"`
	CatalogPath         string                  `json:"catalog_path,omitempty"`
	CatalogSecretsPath  string                  `json:"catalog_secrets_path,omitempty"`
	RecoveryPath        string                  `json:"recovery_path,omitempty"`
	LegacyAPIEnabled    bool                    `json:"legacy_api_enabled"`
	SingBox             config.SingBoxConfig    `json:"singbox,omitempty"`
	AWGManager          config.AWGManagerConfig `json:"awg_manager,omitempty"`
}

func UpgradeV1(reader io.Reader) (config.Config, error) {
	decoder := json.NewDecoder(io.LimitReader(reader, 1<<20))
	decoder.DisallowUnknownFields()
	var previous configV1
	if err := decoder.Decode(&previous); err != nil {
		return config.Config{}, config.ErrInvalidConfig
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return config.Config{}, config.ErrInvalidConfig
	}
	if (previous.SchemaVersion != 0 && previous.SchemaVersion != 1) || previous.SecureListenAddress == "" {
		return config.Config{}, config.ErrInvalidConfig
	}

	next := config.NewSecure(previous.SecureListenAddress)
	next.SubscriptionURL = previous.SubscriptionURL
	copyString(&next.SubscriptionCache, previous.SubscriptionCache)
	copyString(&next.StatePath, previous.StatePath)
	copyString(&next.BackupDir, previous.BackupDir)
	copyString(&next.OutboundsPath, previous.OutboundsPath)
	copyString(&next.ExcludePath, previous.ExcludePath)
	copyString(&next.DomainPolicyPath, previous.DomainPolicyPath)
	copyString(&next.DomainPolicyBackup, previous.DomainPolicyBackup)
	copyString(&next.RoutingPath, previous.RoutingPath)
	copyString(&next.InitScript, previous.InitScript)
	copyString(&next.XrayBinary, previous.XrayBinary)
	copyString(&next.AssetDir, previous.AssetDir)
	copyString(&next.TLSCertificatePath, previous.TLSCertificatePath)
	copyString(&next.TLSPrivateKeyPath, previous.TLSPrivateKeyPath)
	copyString(&next.DeviceStorePath, previous.DeviceStorePath)
	copyString(&next.PairingStorePath, previous.PairingStorePath)
	copyString(&next.CatalogPath, previous.CatalogPath)
	copyString(&next.CatalogSecretsPath, previous.CatalogSecretsPath)
	copyString(&next.RecoveryPath, previous.RecoveryPath)
	if previous.MaxSubscriptionSize != 0 {
		next.MaxSubscriptionSize = previous.MaxSubscriptionSize
	}
	if previous.MaxNodes != 0 {
		next.MaxNodes = previous.MaxNodes
	}
	next.AllowPrivateServers = previous.AllowPrivateServers
	copyString(&next.Discovery.XKeenInitPath, previous.Discovery.XKeenInitPath)
	copyString(&next.Discovery.ASCPath, previous.Discovery.ASCPath)
	next.SingBox = previous.SingBox
	next.AWGManager = previous.AWGManager
	if err := next.Validate(); err != nil {
		return config.Config{}, config.ErrInvalidConfig
	}
	return next, nil
}

func copyString(target *string, value string) {
	if value != "" {
		*target = value
	}
}
