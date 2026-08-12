package app

import (
	"errors"
	"os"
	"path/filepath"
	"strings"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/config"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/subscriptionconfig"
)

type subscriptionMigrationDependencies struct {
	openStore     func(path, legacyURL string) error
	replaceConfig func(path string, cfg config.Config) error
}

func MigrateSubscriptionConfiguration(configPath, root string, cfg config.Config) (config.Config, error) {
	return migrateSubscriptionConfiguration(configPath, root, cfg, subscriptionMigrationDependencies{
		openStore: func(path, legacyURL string) error {
			_, err := subscriptionconfig.New(path, legacyURL)
			return err
		},
		replaceConfig: replaceConfigAtomic,
	})
}

func migrateSubscriptionConfiguration(
	configPath, root string,
	cfg config.Config,
	dependencies subscriptionMigrationDependencies,
) (config.Config, error) {
	if err := validateTestRoot(root); err != nil {
		return config.Config{}, err
	}
	if err := validateMigrationConfigPath(configPath, root); err != nil {
		return config.Config{}, err
	}
	if dependencies.openStore == nil || dependencies.replaceConfig == nil {
		return config.Config{}, errors.New("subscription migration unavailable")
	}
	runtimeConfig := rootedConfig(cfg, root)
	storePath := filepath.Join(filepath.Dir(runtimeConfig.SubscriptionCache), "subscription-source.json")
	if err := dependencies.openStore(storePath, cfg.SubscriptionURL); err != nil {
		return config.Config{}, err
	}
	if cfg.SubscriptionURL == "" {
		return cfg, nil
	}
	next := cfg
	next.SubscriptionURL = ""
	if err := dependencies.replaceConfig(configPath, next); err != nil {
		return config.Config{}, err
	}
	return next, nil
}

func validateMigrationConfigPath(path, root string) error {
	if path == "" || !filepath.IsAbs(path) || filepath.Clean(path) != path {
		return errors.New("unsafe companion config path")
	}
	if root == "" {
		return nil
	}
	relative, err := filepath.Rel(root, path)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return errors.New("unsafe companion config path")
	}
	return nil
}

func replaceConfigAtomic(target string, cfg config.Config) error {
	if err := safeExistingConfig(target); err != nil {
		return err
	}
	directory := filepath.Dir(target)
	temporary, err := os.CreateTemp(directory, ".keenwg-config-*")
	if err != nil {
		return errors.New("config replace unavailable")
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	closeWithError := func(message string) error {
		_ = temporary.Close()
		return errors.New(message)
	}
	if err := temporary.Chmod(0o600); err != nil {
		return closeWithError("config permissions unavailable")
	}
	if err := config.Encode(temporary, cfg); err != nil {
		_ = temporary.Close()
		return err
	}
	if err := temporary.Sync(); err != nil {
		return closeWithError("config sync failed")
	}
	if err := temporary.Close(); err != nil {
		return errors.New("config write failed")
	}
	if err := safeExistingConfig(target); err != nil {
		return err
	}
	if err := os.Rename(temporaryPath, target); err != nil {
		return errors.New("config replace failed")
	}
	if err := os.Chmod(target, 0o600); err != nil {
		return errors.New("config permissions unavailable")
	}
	if parent, err := os.Open(directory); err == nil {
		_ = parent.Sync()
		_ = parent.Close()
	}
	return nil
}

func safeExistingConfig(path string) error {
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 {
		return errors.New("companion config unavailable")
	}
	return nil
}
