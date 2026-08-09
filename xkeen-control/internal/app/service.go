package app

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"errors"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/goldb/keenwg/xkeen-control/internal/adapter"
	"github.com/goldb/keenwg/xkeen-control/internal/api"
	"github.com/goldb/keenwg/xkeen-control/internal/auth"
	"github.com/goldb/keenwg/xkeen-control/internal/backup"
	"github.com/goldb/keenwg/xkeen-control/internal/capability"
	"github.com/goldb/keenwg/xkeen-control/internal/catalog"
	"github.com/goldb/keenwg/xkeen-control/internal/config"
	"github.com/goldb/keenwg/xkeen-control/internal/connection"
	modulecoordinator "github.com/goldb/keenwg/xkeen-control/internal/coordinator"
	"github.com/goldb/keenwg/xkeen-control/internal/diagnostics"
	"github.com/goldb/keenwg/xkeen-control/internal/domainpolicy"
	"github.com/goldb/keenwg/xkeen-control/internal/exclusions"
	"github.com/goldb/keenwg/xkeen-control/internal/identity"
	"github.com/goldb/keenwg/xkeen-control/internal/ownedsource"
	"github.com/goldb/keenwg/xkeen-control/internal/routegraph"
	"github.com/goldb/keenwg/xkeen-control/internal/scenario"
	"github.com/goldb/keenwg/xkeen-control/internal/state"
	"github.com/goldb/keenwg/xkeen-control/internal/subscription"
	"github.com/goldb/keenwg/xkeen-control/internal/support"
	"github.com/goldb/keenwg/xkeen-control/internal/transaction"
	"github.com/goldb/keenwg/xkeen-control/internal/xray"
)

type controllerRuntime struct {
	handler *api.Server
	engine  *transaction.Engine
	domains *domainpolicy.Service
}

func RunLegacy(ctx context.Context, cfg config.Config, version string, store *state.Store, system xray.System) error {
	runtime, err := buildController(ctx, cfg, version, store, system)
	if err != nil {
		return err
	}
	if err := runtime.engine.Recover(ctx); err != nil {
		return err
	}
	listener, err := net.Listen("tcp4", cfg.ListenAddress)
	if err != nil {
		return err
	}
	server := hardenedServer(runtime.handler)
	err = runHTTPServers(ctx, []*http.Server{server}, []net.Listener{listener})
	shutdownContext, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if shutdownErr := runtime.handler.Shutdown(shutdownContext); err == nil {
		err = shutdownErr
	}
	return err
}

func RunCompanion(ctx context.Context, cfg config.Config, version, root string) error {
	if cfg.SecureListenAddress == "" {
		return config.ErrInvalidConfig
	}
	if err := validateTestRoot(root); err != nil {
		return err
	}
	runtimeConfig := rootedConfig(cfg, root)
	recoveryPending, err := fileExists(runtimeConfig.RecoveryPath)
	if err != nil {
		return err
	}
	store := state.New(state.Paths{Subscription: runtimeConfig.SubscriptionCache, State: runtimeConfig.StatePath, BackupDir: runtimeConfig.BackupDir}, rand.Reader)
	system := xray.NewSystem(runtimeConfig)
	controller, err := buildControllerMode(ctx, runtimeConfig, version, store, system, recoveryPending)
	if err != nil {
		return err
	}
	if err := controller.engine.Recover(ctx); err != nil {
		return err
	}
	routeModule := scenario.NewDomainModule(controller.domains)
	recoveryCoordinator, err := modulecoordinator.New([]modulecoordinator.Module{routeModule}, modulecoordinator.NewFileRecoveryStore(runtimeConfig.RecoveryPath))
	if err != nil {
		return err
	}
	scenarioService, err := scenario.NewService(scenario.DefaultPresets(), scenario.NewRouteStateProvider(controller.domains), recoveryCoordinator)
	if err != nil {
		return err
	}
	certificate, _, err := identity.Load(runtimeConfig.TLSCertificatePath, runtimeConfig.TLSPrivateKeyPath)
	if err != nil {
		return err
	}
	deviceStore, err := auth.NewFileStore(runtimeConfig.DeviceStorePath, runtimeConfig.PairingStorePath)
	if err != nil {
		return err
	}
	catalogStore, err := catalog.NewStore(catalog.Paths{
		Catalog: runtimeConfig.CatalogPath, Secrets: runtimeConfig.CatalogSecretsPath,
	}, rand.Reader)
	if err != nil {
		return err
	}
	connectionAdapters := []adapter.Adapter{
		adapter.NewXKeenAdapter(store, controller.engine, diagnostics.NewDefault(), nil),
	}
	var singBoxProbe func(context.Context) (bool, bool, string)
	if runtimeConfig.SingBox.Enabled {
		singBox := adapter.NewSingBoxAdapter(adapter.SingBoxOptions{
			ControllerURL: runtimeConfig.SingBox.ControllerURL,
			Secret:        runtimeConfig.SingBox.Secret,
			Selector:      runtimeConfig.SingBox.Selector,
		}, nil)
		connectionAdapters = append(connectionAdapters, singBox)
		singBoxProbe = func(ctx context.Context) (bool, bool, string) {
			discovery := singBox.Discover(ctx)
			if !discovery.Available {
				return false, false, discovery.Reason
			}
			if _, err := singBox.Snapshot(ctx); err != nil {
				if errors.Is(err, adapter.ErrUnsupportedSchema) {
					return false, false, "singbox_schema_unsupported"
				}
				return false, false, "singbox_unavailable"
			}
			return true, discovery.Writable, discovery.Reason
		}
	}
	var awgManagerProbe func(context.Context) (bool, bool, string)
	if runtimeConfig.AWGManager.Enabled {
		awgManager := adapter.NewAWGManagerAdapter(adapter.AWGManagerOptions{
			BaseURL:  runtimeConfig.AWGManager.BaseURL,
			Login:    runtimeConfig.AWGManager.Login,
			Password: runtimeConfig.AWGManager.Password,
		}, nil)
		connectionAdapters = append(connectionAdapters, awgManager)
		defer func() {
			logoutContext, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			_ = awgManager.Close(logoutContext)
		}()
		awgManagerProbe = func(ctx context.Context) (bool, bool, string) {
			discovery := awgManager.Discover(ctx)
			if !discovery.Available {
				return false, false, discovery.Reason
			}
			if _, err := awgManager.Snapshot(ctx); err != nil {
				if errors.Is(err, adapter.ErrUnsupportedSchema) {
					return false, false, "awg_response_unsupported"
				}
				return false, false, "awg_unavailable"
			}
			return true, discovery.Writable, discovery.Reason
		}
	}
	registry, err := adapter.NewRegistry(connectionAdapters...)
	if err != nil {
		return err
	}
	ownedProcessor := ownedsource.NewProcessor(
		&subscription.Fetcher{Client: &http.Client{Timeout: 30 * time.Second}},
		diagnostics.NewDefault(), store, controller.engine, nil,
	)
	coordinator := connection.NewCoordinator(catalogStore, registry, nil, ownedProcessor)
	for _, adapterID := range registry.AdapterIDs() {
		// Optional engines are isolated: an unavailable adapter must not suppress
		// XKeen, WireGuard, or another healthy connection module.
		_ = coordinator.SyncAdapter(ctx, adapterID)
	}
	detector := capability.NewDetectorWithPaths("", capability.Paths{
		XKeenInitPath:        runtimeConfig.Discovery.XKeenInitPath,
		ASCPath:              runtimeConfig.Discovery.ASCPath,
		CollectorPath:        rootedPath(root, "/opt/etc/init.d/S95keenwg"),
		SingBoxConfigured:    runtimeConfig.SingBox.Enabled,
		AWGManagerConfigured: runtimeConfig.AWGManager.Enabled,
		SingBoxProbe:         singBoxProbe,
		AWGManagerProbe:      awgManagerProbe,
	})
	routeService := routegraph.NewService(&routeEvidenceProvider{
		domains: controller.domains, catalog: catalogStore, adapters: registry,
		resolver: net.DefaultResolver, now: time.Now,
	})
	backupService, err := backup.NewFileService(version, []backup.Resource{
		{ID: "catalog", Path: runtimeConfig.CatalogPath, Owned: true},
		{ID: "catalog-secrets", Path: runtimeConfig.CatalogSecretsPath, Owned: true},
		{ID: "controller-state", Path: runtimeConfig.StatePath, Owned: true},
		{ID: "device-store", Path: runtimeConfig.DeviceStorePath, Owned: true},
		{ID: "domain-policy", Path: runtimeConfig.DomainPolicyPath, Owned: true},
		{ID: "pairing-store", Path: runtimeConfig.PairingStorePath, Owned: true},
		{ID: "subscription-cache", Path: runtimeConfig.SubscriptionCache, Owned: true},
		{ID: "tls-certificate", Path: runtimeConfig.TLSCertificatePath, Owned: true},
		{ID: "tls-private-key", Path: runtimeConfig.TLSPrivateKeyPath, Owned: true},
		{ID: "xkeen-exclusions", Path: runtimeConfig.ExcludePath, Owned: true},
	}, filepath.Join(runtimeConfig.BackupDir, "restore-1.0"))
	if err != nil {
		return err
	}
	secureHandler := api.NewSecure(controller.handler, deviceStore, detector,
		api.WithCatalog(catalogStore), api.WithConnectionCoordinator(coordinator), api.WithRouteExplainer(routeService), api.WithScenarios(scenarioService), api.WithRecovery(recoveryCoordinator),
		api.WithSupport(newSupportReporter(store, version, support.NewDefault())), api.WithBackup(backupManager{backupService}))
	secureListener, err := net.Listen("tcp4", cfg.SecureListenAddress)
	if err != nil {
		return err
	}
	tlsListener := tls.NewListener(secureListener, &tls.Config{
		Certificates: []tls.Certificate{certificate},
		MinVersion:   tls.VersionTLS12,
	})
	servers := []*http.Server{hardenedServer(secureHandler)}
	listeners := []net.Listener{tlsListener}
	if cfg.LegacyAPIEnabled {
		legacyListener, listenErr := net.Listen("tcp4", cfg.ListenAddress)
		if listenErr != nil {
			_ = tlsListener.Close()
			return listenErr
		}
		servers = append(servers, hardenedServer(controller.handler))
		listeners = append(listeners, legacyListener)
	}
	err = runHTTPServers(ctx, servers, listeners)
	shutdownContext, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if shutdownErr := controller.handler.Shutdown(shutdownContext); err == nil {
		err = shutdownErr
	}
	return err
}

func buildController(ctx context.Context, cfg config.Config, version string, store *state.Store, system xray.System) (controllerRuntime, error) {
	return buildControllerMode(ctx, cfg, version, store, system, false)
}

func buildControllerMode(ctx context.Context, cfg config.Config, version string, store *state.Store, system xray.System, allowDomainRecovery bool) (controllerRuntime, error) {
	client := &http.Client{Timeout: 30 * time.Second}
	fetcher := &subscription.Fetcher{Client: client}
	engine := transaction.New(cfg, fetcher, subscription.Parse, store, system, time.Now)
	bootstrap := BootstrapDomainPolicy
	if allowDomainRecovery {
		bootstrap = BootstrapDomainPolicyForRecovery
	}
	domainService, err := bootstrap(ctx, cfg, domainRuntime{System: system, assetDir: cfg.AssetDir}, fileExists, os.Remove)
	if err != nil {
		return controllerRuntime{}, err
	}
	handler := api.New(cfg.Token, version, engine, store,
		api.WithExclusions(exclusions.New(cfg.ExcludePath, system)),
		api.WithDomainPolicy(domainService),
	)
	return controllerRuntime{handler: handler, engine: engine, domains: domainService}, nil
}

func hardenedServer(handler http.Handler) *http.Server {
	return &http.Server{
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    8 << 10,
	}
}

func rootedConfig(cfg config.Config, root string) config.Config {
	if root == "" {
		return cfg
	}
	paths := []*string{
		&cfg.SubscriptionCache, &cfg.StatePath, &cfg.BackupDir, &cfg.OutboundsPath, &cfg.ExcludePath,
		&cfg.DomainPolicyPath, &cfg.DomainPolicyBackup, &cfg.RoutingPath, &cfg.InitScript, &cfg.XrayBinary,
		&cfg.AssetDir, &cfg.Discovery.XKeenInitPath, &cfg.Discovery.ASCPath, &cfg.TLSCertificatePath,
		&cfg.TLSPrivateKeyPath, &cfg.DeviceStorePath, &cfg.PairingStorePath,
		&cfg.CatalogPath, &cfg.CatalogSecretsPath,
		&cfg.RecoveryPath,
	}
	for _, value := range paths {
		*value = rootedPath(root, *value)
	}
	return cfg
}
