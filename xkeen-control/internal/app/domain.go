package app

import (
	"context"
	"errors"
	"io/fs"
	"os"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/config"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/domainpolicy"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/xray"
)

type domainRuntime struct {
	xray.System
	assetDir string
}

func (d domainRuntime) CheckGeoSite(_ context.Context, name string) error {
	if !xray.GeoSiteAvailable(d.assetDir, name) {
		return domainpolicy.ErrInvalidPolicy
	}
	return nil
}

func BootstrapDomainPolicy(
	ctx context.Context,
	cfg config.Config,
	runtime domainpolicy.RuntimeSystem,
	exists func(string) (bool, error),
	remove func(string) error,
) (*domainpolicy.Service, error) {
	return bootstrapDomainPolicy(ctx, cfg, runtime, exists, remove, false)
}

func BootstrapDomainPolicyForRecovery(ctx context.Context, cfg config.Config, runtime domainpolicy.RuntimeSystem, exists func(string) (bool, error), remove func(string) error) (*domainpolicy.Service, error) {
	return bootstrapDomainPolicy(ctx, cfg, runtime, exists, remove, true)
}

func bootstrapDomainPolicy(
	ctx context.Context,
	cfg config.Config,
	runtime domainpolicy.RuntimeSystem,
	exists func(string) (bool, error),
	remove func(string) error,
	allowPendingRecovery bool,
) (*domainpolicy.Service, error) {
	service := domainpolicy.NewService(cfg.DomainPolicyPath, cfg.DomainPolicyBackup, cfg.RoutingPath, runtime)
	present, err := exists(cfg.DomainPolicyPath)
	if err != nil {
		return nil, errors.New("domain policy unavailable")
	}
	if present {
		status, err := service.Status(ctx)
		if err != nil || (len(status.Warnings) != 0 && !allowPendingRecovery) {
			return nil, errors.New("domain policy requires recovery")
		}
		return service, nil
	}
	originalRouting, err := runtime.ReadFile(cfg.RoutingPath)
	if err != nil {
		return nil, errors.New("domain routing unavailable")
	}
	policy, _, err := domainpolicy.ImportExistingRouting(originalRouting)
	if err != nil {
		return nil, errors.New("domain routing migration rejected")
	}
	for _, rule := range policy.Rules {
		if rule.Kind == "geosite" && rule.Enabled && runtime.CheckGeoSite(ctx, rule.Value) != nil {
			return nil, errors.New("domain routing assets unavailable")
		}
	}
	candidateRouting, err := domainpolicy.RenderRouting(originalRouting, policy)
	if err != nil {
		return nil, errors.New("domain routing migration rejected")
	}
	store := domainpolicy.NewStore(cfg.DomainPolicyPath, cfg.DomainPolicyBackup, runtime)
	if err = store.Save(policy, nil); err == nil {
		err = runtime.WriteAtomic(cfg.RoutingPath, candidateRouting, 0o600)
	}
	if err == nil {
		err = runtime.Validate(ctx)
	}
	if err == nil {
		err = runtime.Restart(ctx)
	}
	if err == nil {
		status, statusErr := service.Status(ctx)
		if statusErr == nil && len(status.Warnings) == 0 {
			return service, nil
		}
		err = statusErr
		if err == nil {
			err = domainpolicy.ErrInvalidPolicy
		}
	}
	rollbackErrors := []error{}
	if restoreErr := runtime.WriteAtomic(cfg.RoutingPath, originalRouting, 0o600); restoreErr != nil {
		rollbackErrors = append(rollbackErrors, restoreErr)
	}
	if removeErr := remove(cfg.DomainPolicyPath); removeErr != nil && !errors.Is(removeErr, fs.ErrNotExist) {
		rollbackErrors = append(rollbackErrors, removeErr)
	}
	if restartErr := runtime.Restart(ctx); restartErr != nil {
		rollbackErrors = append(rollbackErrors, restartErr)
	}
	if len(rollbackErrors) != 0 {
		return nil, errors.New("domain routing migration uncertain")
	}
	return nil, errors.New("domain routing migration rolled back")
}

func fileExists(path string) (bool, error) {
	_, err := os.Stat(path)
	if err == nil {
		return true, nil
	}
	if errors.Is(err, fs.ErrNotExist) {
		return false, nil
	}
	return false, err
}
