package configupgrade

import (
	"strings"
	"testing"
)

func TestUpgradeV1DropsCleartextRuntimeAndPreservesActiveSettings(t *testing.T) {
	legacy := `{
  "listen_address":"10.8.0.1:18778",
  "secure_listen_address":"192.168.1.1:18779",
  "token":"0123456789abcdef0123456789abcdef",
  "subscription_url":"https://vpn.example.test/sub/private",
  "subscription_cache_path":"/opt/etc/keenwg/xkeen-subscription.json",
  "state_path":"/opt/etc/keenwg/xkeen-state.json",
  "backup_dir":"/opt/etc/keenwg/backups",
  "outbounds_path":"/opt/etc/xray/configs/04_outbounds.json",
  "exclude_path":"/opt/etc/xkeen/ip_exclude.lst",
  "domain_policy_path":"/opt/etc/keenwg/domain-policy.json",
  "domain_policy_backup_path":"/opt/etc/keenwg/domain-policy.json.bak",
  "routing_path":"/opt/etc/xray/configs/05_routing.json",
  "init_script":"/opt/etc/init.d/S05xkeen",
  "xray_binary":"/opt/sbin/xray",
  "asset_dir":"/opt/etc/xray/dat",
  "max_subscription_bytes":262144,
  "max_nodes":128,
  "allow_private_servers":false,
  "legacy_api_enabled":true
}`

	cfg, err := UpgradeV1(strings.NewReader(legacy))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.SchemaVersion != 2 || cfg.SecureListenAddress != "192.168.1.1:18779" || cfg.SubscriptionURL != "https://vpn.example.test/sub/private" {
		t.Fatalf("active settings not preserved: %+v", cfg)
	}
}

func TestUpgradeV1RejectsUnknownFields(t *testing.T) {
	if _, err := UpgradeV1(strings.NewReader(`{"listen_address":"10.8.0.1:18778","shell":"reboot"}`)); err == nil {
		t.Fatal("unknown v1 field accepted")
	}
}

func TestUpgradeV1RejectsInvalidSchemaMarkers(t *testing.T) {
	for _, schema := range []string{"-1", "2"} {
		document := `{"schema_version":` + schema + `,"secure_listen_address":"192.168.1.1:18779"}`
		if _, err := UpgradeV1(strings.NewReader(document)); err == nil {
			t.Fatalf("invalid schema marker accepted: %s", schema)
		}
	}
}
