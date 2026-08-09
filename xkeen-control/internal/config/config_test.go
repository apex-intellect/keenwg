package config

import (
	"strings"
	"testing"
)

const validConfig = `{
  "listen_address":"10.8.0.1:18778",
  "token":"0123456789abcdef0123456789abcdef",
  "subscription_url":"https://vpn.example.test/sub/private",
  "subscription_cache_path":"/opt/keenwg/xkeen/subscription.json",
  "state_path":"/opt/keenwg/xkeen/state.json",
  "backup_dir":"/opt/keenwg/xkeen/backups",
  "outbounds_path":"/opt/etc/xray/configs/04_outbounds.json",
  "exclude_path":"/opt/etc/xkeen/ip_exclude.lst",
  "domain_policy_path":"/opt/etc/keenwg/domain-policy.json",
  "domain_policy_backup_path":"/opt/etc/keenwg/domain-policy.json.bak",
  "routing_path":"/opt/etc/xray/configs/05_routing.json",
  "init_script":"/opt/etc/init.d/S05xkeen",
  "xray_binary":"/opt/sbin/xray",
  "asset_dir":"/opt/etc/xray/dat",
  "max_subscription_bytes":262144,
  "max_nodes":128
}`

func TestDecodeAcceptsExplicitPrivateListener(t *testing.T) {
	cfg, err := Decode(strings.NewReader(validConfig))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.ListenAddress != "10.8.0.1:18778" || cfg.InitScript != "/opt/etc/init.d/S05xkeen" || cfg.RoutingPath != "/opt/etc/xray/configs/05_routing.json" {
		t.Fatalf("unexpected config: %+v", cfg)
	}
}

func TestDecodeAppliesDiscoveryDefaultsToLegacyConfig(t *testing.T) {
	cfg, err := Decode(strings.NewReader(validConfig))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Discovery.XKeenInitPath != "/opt/etc/init.d/S05xkeen" {
		t.Fatalf("unexpected XKeen discovery path: %q", cfg.Discovery.XKeenInitPath)
	}
	if cfg.Discovery.ASCPath != "/opt/sbin/asc" {
		t.Fatalf("unexpected ASC discovery path: %q", cfg.Discovery.ASCPath)
	}
}

func TestDecodeAcceptsExplicitDiscoveryPaths(t *testing.T) {
	body := strings.Replace(validConfig, "\n}", ",\n  \"discovery\":{\"xkeen_init_path\":\"/opt/etc/init.d/S07xkeen\",\"asc_path\":\"/opt/bin/asc\"}\n}", 1)
	cfg, err := Decode(strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Discovery.XKeenInitPath != "/opt/etc/init.d/S07xkeen" || cfg.Discovery.ASCPath != "/opt/bin/asc" {
		t.Fatalf("explicit discovery paths were not retained: %+v", cfg.Discovery)
	}
}

func TestDecodeAppliesCompanionStorageDefaultsWithoutBreakingLegacyMode(t *testing.T) {
	cfg, err := Decode(strings.NewReader(validConfig))
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.LegacyAPIEnabled || cfg.SecureListenAddress != "" {
		t.Fatalf("legacy mode defaults changed: %+v", cfg)
	}
	if cfg.TLSCertificatePath != "/opt/etc/keenwg/identity/certificate.pem" ||
		cfg.TLSPrivateKeyPath != "/opt/etc/keenwg/identity/private-key.pem" ||
		cfg.DeviceStorePath != "/opt/etc/keenwg/devices.json" ||
		cfg.PairingStorePath != "/opt/etc/keenwg/pairing-offers.json" ||
		cfg.CatalogPath != "/opt/etc/keenwg/catalog.json" ||
		cfg.CatalogSecretsPath != "/opt/etc/keenwg/catalog-secrets.json" ||
		cfg.RecoveryPath != "/opt/etc/keenwg/recovery.json" {
		t.Fatalf("unexpected companion storage defaults: %+v", cfg)
	}
}

func TestDecodeAcceptsSecureCompanionListener(t *testing.T) {
	body := strings.Replace(validConfig, "\n}", ",\n  \"secure_listen_address\":\"10.8.0.1:18779\",\n  \"legacy_api_enabled\":false\n}", 1)
	cfg, err := Decode(strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.SecureListenAddress != "10.8.0.1:18779" || cfg.LegacyAPIEnabled {
		t.Fatalf("secure listener not decoded: %+v", cfg)
	}
}

func TestDecodeAcceptsOptionalLoopbackSingBoxController(t *testing.T) {
	body := strings.Replace(validConfig, "\n}", ",\n  \"singbox\":{\"enabled\":true,\"controller_url\":\"http://127.0.0.1:9090\",\"secret\":\"clash-secret\",\"selector\":\"Main Route\"}\n}", 1)
	cfg, err := Decode(strings.NewReader(body))
	if err != nil || !cfg.SingBox.Enabled || cfg.SingBox.Selector != "Main Route" {
		t.Fatalf("sing-box config=%+v err=%v", cfg.SingBox, err)
	}
}

func TestDecodeAcceptsOptionalLoopbackAWGManager(t *testing.T) {
	body := strings.Replace(validConfig, "\n}", ",\n  \"awg_manager\":{\"enabled\":true,\"base_url\":\"http://127.0.0.1:8080\",\"login\":\"admin\",\"password\":\"secret\"}\n}", 1)
	cfg, err := Decode(strings.NewReader(body))
	if err != nil || !cfg.AWGManager.Enabled || cfg.AWGManager.Login != "admin" {
		t.Fatalf("AWG Manager config=%+v err=%v", cfg.AWGManager, err)
	}
}

func TestDecodeRejectsUnsafeSecureCompanionConfig(t *testing.T) {
	tests := map[string]string{
		"wildcard secure listener": strings.Replace(validConfig, "\n}", ",\n  \"secure_listen_address\":\"0.0.0.0:18779\"\n}", 1),
		"same legacy port":         strings.Replace(validConfig, "\n}", ",\n  \"secure_listen_address\":\"10.8.0.1:18778\"\n}", 1),
		"no enabled listener":      strings.Replace(validConfig, "\n}", ",\n  \"legacy_api_enabled\":false\n}", 1),
		"outside opt key":          strings.Replace(validConfig, "\n}", ",\n  \"secure_listen_address\":\"10.8.0.1:18779\",\n  \"tls_private_key_path\":\"/tmp/key.pem\"\n}", 1),
		"catalog overlaps devices": strings.Replace(validConfig, "\n}", ",\n  \"catalog_path\":\"/opt/etc/keenwg/devices.json\"\n}", 1),
		"sing-box wildcard":        strings.Replace(validConfig, "\n}", ",\n  \"singbox\":{\"enabled\":true,\"controller_url\":\"http://0.0.0.0:9090\",\"secret\":\"secret\",\"selector\":\"Main\"}\n}", 1),
		"sing-box missing secret":  strings.Replace(validConfig, "\n}", ",\n  \"singbox\":{\"enabled\":true,\"controller_url\":\"http://127.0.0.1:9090\",\"selector\":\"Main\"}\n}", 1),
		"AWG Manager public":       strings.Replace(validConfig, "\n}", ",\n  \"awg_manager\":{\"enabled\":true,\"base_url\":\"http://192.0.2.10:8080\",\"login\":\"admin\",\"password\":\"secret\"}\n}", 1),
		"AWG missing password":     strings.Replace(validConfig, "\n}", ",\n  \"awg_manager\":{\"enabled\":true,\"base_url\":\"http://127.0.0.1:8080\",\"login\":\"admin\"}\n}", 1),
	}
	for name, body := range tests {
		t.Run(name, func(t *testing.T) {
			if _, err := Decode(strings.NewReader(body)); err == nil {
				t.Fatal("unsafe secure config accepted")
			}
		})
	}
}

func TestDecodeRejectsUnsafeOrAmbiguousConfig(t *testing.T) {
	tests := map[string]string{
		"unknown field":  strings.Replace(validConfig, "\n}", ",\n  \"extra\":true\n}", 1),
		"wildcard":       strings.Replace(validConfig, "10.8.0.1:18778", "0.0.0.0:18778", 1),
		"public listen":  strings.Replace(validConfig, "10.8.0.1:18778", "8.8.8.8:18778", 1),
		"hostname":       strings.Replace(validConfig, "10.8.0.1:18778", "router.example.test:18778", 1),
		"short token":    strings.Replace(validConfig, "0123456789abcdef0123456789abcdef", "short", 1),
		"http sub":       strings.Replace(validConfig, "https://vpn.example.test", "http://vpn.example.test", 1),
		"userinfo":       strings.Replace(validConfig, "https://vpn.example.test", "https://user@vpn.example.test", 1),
		"fragment":       strings.Replace(validConfig, "/sub/private", "/sub/private#secret", 1),
		"relative path":  strings.Replace(validConfig, "/opt/sbin/xray", "opt/sbin/xray", 1),
		"outside opt":    strings.Replace(validConfig, "/opt/sbin/xray", "/usr/bin/xray", 1),
		"unsafe routing": strings.Replace(validConfig, "/opt/etc/xray/configs/05_routing.json", "../05_routing.json", 1),
		"oversize max":   strings.Replace(validConfig, "262144", "1048577", 1),
		"too many nodes": strings.Replace(validConfig, "\"max_nodes\":128", "\"max_nodes\":513", 1),
		"second object":  validConfig + `{}`,
	}
	for name, body := range tests {
		t.Run(name, func(t *testing.T) {
			if _, err := Decode(strings.NewReader(body)); err == nil {
				t.Fatal("unsafe config accepted")
			}
		})
	}
}

func TestValidateAllowsPrivateCGNATListenerAndOptionalPrivateServers(t *testing.T) {
	body := strings.Replace(validConfig, "10.8.0.1:18778", "100.64.0.1:18778", 1)
	body = strings.Replace(body, "\n}", ",\n  \"allow_private_servers\":true\n}", 1)
	cfg, err := Decode(strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.AllowPrivateServers {
		t.Fatal("allow_private_servers was not decoded")
	}
}
