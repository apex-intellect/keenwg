package config

import (
	"bytes"
	"strings"
	"testing"
)

const validConfig = `{
  "schema_version":2,
  "secure_listen_address":"10.8.0.1:18779",
  "subscription_url":"",
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

func TestDecodeAcceptsSecureOnlySchemaV2AndAppliesDefaults(t *testing.T) {
	cfg, err := Decode(strings.NewReader(validConfig))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.SchemaVersion != 2 || cfg.SecureListenAddress != "10.8.0.1:18779" || cfg.SubscriptionURL != "" {
		t.Fatalf("unexpected config: %+v", cfg)
	}
	if cfg.Discovery.XKeenInitPath != "/opt/etc/init.d/S05xkeen" || cfg.Discovery.ASCPath != "/opt/sbin/asc" ||
		cfg.TLSCertificatePath != "/opt/etc/keenwg/identity/certificate.pem" || cfg.DeviceStorePath != "/opt/etc/keenwg/devices.json" ||
		cfg.CatalogPath != "/opt/etc/keenwg/catalog.json" || cfg.RecoveryPath != "/opt/etc/keenwg/recovery.json" {
		t.Fatalf("unexpected defaults: %+v", cfg)
	}
}

func TestEncodeNeverEmitsRemovedControllerFields(t *testing.T) {
	cfg, err := Decode(strings.NewReader(validConfig))
	if err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	if err := Encode(&output, cfg); err != nil {
		t.Fatal(err)
	}
	for _, field := range []string{`"listen_address"`, `"token"`, `"legacy_api_enabled"`} {
		if strings.Contains(output.String(), field) {
			t.Fatalf("encoded v2 config contains %s: %s", field, output.String())
		}
	}
}

func TestDecodeAcceptsOptionalLocalAdapters(t *testing.T) {
	body := strings.Replace(validConfig, "\n}", `,
  "discovery":{"xkeen_init_path":"/opt/etc/init.d/S07xkeen","asc_path":"/opt/bin/asc"},
  "singbox":{"enabled":true,"controller_url":"http://127.0.0.1:9090","secret":"local-secret","selector":"Main Route"},
  "awg_manager":{"enabled":true,"base_url":"http://127.0.0.1:8080","login":"admin","password":"local-password"}
}`, 1)
	cfg, err := Decode(strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Discovery.XKeenInitPath != "/opt/etc/init.d/S07xkeen" || !cfg.SingBox.Enabled || !cfg.AWGManager.Enabled {
		t.Fatalf("optional adapters not retained: %+v", cfg)
	}
}

func TestDecodeRejectsRemovedFieldsAndSchemaV1(t *testing.T) {
	for name, body := range map[string]string{
		"schema v1":        strings.Replace(validConfig, `"schema_version":2`, `"schema_version":1`, 1),
		"missing schema":   strings.Replace(validConfig, "  \"schema_version\":2,\n", "", 1),
		"listen address":   strings.Replace(validConfig, "\n}", ",\n  \"listen_address\":\"10.8.0.1:18778\"\n}", 1),
		"shared token":     strings.Replace(validConfig, "\n}", ",\n  \"token\":\"obsolete\"\n}", 1),
		"legacy API flag":  strings.Replace(validConfig, "\n}", ",\n  \"legacy_api_enabled\":true\n}", 1),
		"unknown property": strings.Replace(validConfig, "\n}", ",\n  \"shell\":\"reboot\"\n}", 1),
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := Decode(strings.NewReader(body)); err == nil {
				t.Fatal("obsolete or unknown config accepted")
			}
		})
	}
}

func TestDecodeRejectsUnsafeConfig(t *testing.T) {
	for name, body := range map[string]string{
		"wildcard listener": strings.Replace(validConfig, "10.8.0.1:18779", "0.0.0.0:18779", 1),
		"public listener":   strings.Replace(validConfig, "10.8.0.1:18779", "8.8.8.8:18779", 1),
		"hostname listener": strings.Replace(validConfig, "10.8.0.1:18779", "router.example.test:18779", 1),
		"HTTP subscription": strings.Replace(validConfig, `"subscription_url":""`, `"subscription_url":"http://vpn.example.test/sub"`, 1),
		"outside opt":       strings.Replace(validConfig, "/opt/sbin/xray", "/usr/bin/xray", 1),
		"relative path":     strings.Replace(validConfig, "/opt/sbin/xray", "opt/sbin/xray", 1),
		"oversize maximum":  strings.Replace(validConfig, "262144", "1048577", 1),
		"too many nodes":    strings.Replace(validConfig, `"max_nodes":128`, `"max_nodes":513`, 1),
		"second object":     validConfig + `{}`,
		"sing-box wildcard": strings.Replace(validConfig, "\n}", ",\n  \"singbox\":{\"enabled\":true,\"controller_url\":\"http://0.0.0.0:9090\",\"secret\":\"secret\",\"selector\":\"Main\"}\n}", 1),
		"AWG public":        strings.Replace(validConfig, "\n}", ",\n  \"awg_manager\":{\"enabled\":true,\"base_url\":\"http://192.0.2.10:8080\",\"login\":\"admin\",\"password\":\"secret\"}\n}", 1),
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := Decode(strings.NewReader(body)); err == nil {
				t.Fatal("unsafe config accepted")
			}
		})
	}
}

func TestValidateAllowsPrivateCGNATListenerAndOptionalPrivateServers(t *testing.T) {
	body := strings.Replace(validConfig, "10.8.0.1:18779", "100.64.0.1:18779", 1)
	body = strings.Replace(body, "\n}", ",\n  \"allow_private_servers\":true\n}", 1)
	cfg, err := Decode(strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.AllowPrivateServers {
		t.Fatal("allow_private_servers was not decoded")
	}
}
