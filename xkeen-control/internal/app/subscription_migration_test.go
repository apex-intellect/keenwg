package app

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/config"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/subscriptionconfig"
)

func TestMigrateSubscriptionConfigurationCommitsSecretBeforeClearingLegacyConfig(t *testing.T) {
	root := t.TempDir()
	configPath := filepath.Join(root, "companion.json")
	cfg := migrationConfig("https://vpn.example.test/sub/private")
	writeMigrationConfig(t, configPath, cfg)

	next, err := MigrateSubscriptionConfiguration(configPath, root, cfg)
	if err != nil {
		t.Fatal(err)
	}
	if next.SubscriptionURL != "" {
		t.Fatal("runtime config retained legacy subscription URL")
	}
	persisted, err := LoadConfig(configPath)
	if err != nil {
		t.Fatal(err)
	}
	if persisted.SubscriptionURL != "" {
		t.Fatal("companion config retained legacy subscription URL")
	}
	store, err := subscriptionconfig.New(migrationStorePath(root, cfg), "")
	if err != nil {
		t.Fatal(err)
	}
	if got, ok := store.Current(); !ok || got != "https://vpn.example.test/sub/private" {
		t.Fatalf("migrated URL=%q configured=%t", got, ok)
	}
}

func TestMigrateSubscriptionConfigurationDoesNotClearConfigWhenStoreCommitFails(t *testing.T) {
	root := t.TempDir()
	configPath := filepath.Join(root, "companion.json")
	cfg := migrationConfig("https://vpn.example.test/sub/private")
	writeMigrationConfig(t, configPath, cfg)
	replaced := false

	_, err := migrateSubscriptionConfiguration(configPath, root, cfg, subscriptionMigrationDependencies{
		openStore: func(string, string) error { return subscriptionconfig.ErrStorage },
		replaceConfig: func(string, config.Config) error {
			replaced = true
			return nil
		},
	})
	if !errors.Is(err, subscriptionconfig.ErrStorage) || replaced {
		t.Fatalf("error=%v configReplaced=%t", err, replaced)
	}
	persisted, loadErr := LoadConfig(configPath)
	if loadErr != nil || persisted.SubscriptionURL != cfg.SubscriptionURL {
		t.Fatalf("legacy config changed after failed store commit: cfg=%+v err=%v", persisted, loadErr)
	}
}

func TestMigrateSubscriptionConfigurationRetriesAfterConfigReplaceFailure(t *testing.T) {
	root := t.TempDir()
	configPath := filepath.Join(root, "companion.json")
	cfg := migrationConfig("https://vpn.example.test/sub/private")
	writeMigrationConfig(t, configPath, cfg)

	_, err := migrateSubscriptionConfiguration(configPath, root, cfg, subscriptionMigrationDependencies{
		openStore: func(path, legacy string) error {
			_, openErr := subscriptionconfig.New(path, legacy)
			return openErr
		},
		replaceConfig: func(string, config.Config) error { return errors.New("injected config failure") },
	})
	if err == nil {
		t.Fatal("config failure was ignored")
	}
	if persisted, loadErr := LoadConfig(configPath); loadErr != nil || persisted.SubscriptionURL != cfg.SubscriptionURL {
		t.Fatalf("legacy config was discarded: cfg=%+v err=%v", persisted, loadErr)
	}
	if store, openErr := subscriptionconfig.New(migrationStorePath(root, cfg), ""); openErr != nil {
		t.Fatal(openErr)
	} else if got, ok := store.Current(); !ok || got != cfg.SubscriptionURL {
		t.Fatalf("store was not committed before config failure: %q %t", got, ok)
	}

	next, retryErr := MigrateSubscriptionConfiguration(configPath, root, cfg)
	if retryErr != nil || next.SubscriptionURL != "" {
		t.Fatalf("retry failed: cfg=%+v err=%v", next, retryErr)
	}
}

func TestMigrateSubscriptionConfigurationIsNoOpAfterMigration(t *testing.T) {
	root := t.TempDir()
	configPath := filepath.Join(root, "companion.json")
	cfg := migrationConfig("")
	writeMigrationConfig(t, configPath, cfg)
	before, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatal(err)
	}

	next, err := MigrateSubscriptionConfiguration(configPath, root, cfg)
	if err != nil || next.SubscriptionURL != "" {
		t.Fatalf("no-op failed: cfg=%+v err=%v", next, err)
	}
	after, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != string(before) {
		t.Fatal("already migrated config was rewritten")
	}
	if _, err := os.Stat(migrationStorePath(root, cfg)); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("empty migration created a secret file: %v", err)
	}
}

func migrationConfig(subscriptionURL string) config.Config {
	cfg := config.NewSecure("10.8.0.1:18779")
	cfg.SubscriptionURL = subscriptionURL
	return cfg
}

func migrationStorePath(root string, cfg config.Config) string {
	return filepath.Join(filepath.Dir(rootedPath(root, cfg.SubscriptionCache)), "subscription-source.json")
}

func writeMigrationConfig(t *testing.T, path string, cfg config.Config) {
	t.Helper()
	file, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	if err := config.Encode(file, cfg); err != nil {
		_ = file.Close()
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
}
