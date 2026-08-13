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

	"github.com/apex-intellect/keenwg/xkeen-control/internal/adapter"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/api"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/backup"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/capability"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/catalog"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/config"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/connection"
	modulecoordinator "github.com/apex-intellect/keenwg/xkeen-control/internal/coordinator"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/diagnostics"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/domainpolicy"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/exclusions"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/historyproxy"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/identity"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/ownedsource"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/routegraph"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/routerlocal"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/scenario"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/state"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/subscription"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/subscriptionconfig"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/support"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/transaction"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/xray"
)

type controllerRuntime struct {
	handler *api.Server
	engine  *transaction.Engine
	domains *domainpolicy.Service
}

func RunCompanion(ctx context.Context, cfg config.Config, version, root string) error {
	if cfg.SecureListenAddress == "" {
		return config.ErrInvalidConfig
	}
	if err := validateTestRoot(root); err != nil {
		return err
	}
	runtimeConfig := rootedConfig(cfg, root)
	subscriptionURLs, err := subscriptionconfig.New(subscriptionConfigurationPath(runtimeConfig), runtimeConfig.SubscriptionURL)
	if err != nil {
		return err
	}
	recoveryPending, err := fileExists(runtimeConfig.RecoveryPath)
	if err != nil {
		return err
	}
	discoveryPaths := capability.Paths{
		XKeenInitPath:        runtimeConfig.Discovery.XKeenInitPath,
		ASCPath:              runtimeConfig.Discovery.ASCPath,
		NDMQPath:             rootedPath(root, "/opt/bin/ndmq"),
		CollectorPath:        rootedPath(root, "/opt/etc/init.d/S95keenwg"),
		SingBoxConfigured:    runtimeConfig.SingBox.Enabled,
		AWGManagerConfigured: runtimeConfig.AWGManager.Enabled,
	}
	discovery, err := capability.NewDetectorWithPaths("", discoveryPaths).Detect(ctx)
	if err != nil {
		return err
	}
	xkeenAvailable := capabilityAvailable(discovery, capability.ConnectionsXKeen)
	store := state.New(state.Paths{Subscription: runtimeConfig.SubscriptionCache, State: runtimeConfig.StatePath, BackupDir: runtimeConfig.BackupDir}, rand.Reader)
	system := xray.NewSystem(runtimeConfig)
	controller, err := buildControllerMode(ctx, runtimeConfig, version, store, system, recoveryPending, xkeenAvailable, subscriptionURLs)
	if err != nil {
		return err
	}
	if controller.engine != nil {
		if err := controller.engine.Recover(ctx); err != nil {
			return err
		}
	}
	var recoveryCoordinator *modulecoordinator.Coordinator
	var scenarioService *scenario.Service
	if controller.domains != nil {
		routeModule := scenario.NewDomainModule(controller.domains)
		recoveryCoordinator, err = modulecoordinator.New([]modulecoordinator.Module{routeModule}, modulecoordinator.NewFileRecoveryStore(runtimeConfig.RecoveryPath))
		if err != nil {
			return err
		}
		scenarioService, err = scenario.NewService(scenario.DefaultPresets(), scenario.NewRouteStateProvider(controller.domains), recoveryCoordinator)
		if err != nil {
			return err
		}
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
	connectionAdapters := make([]adapter.Adapter, 0, 3)
	if controller.engine != nil {
		connectionAdapters = append(connectionAdapters, adapter.NewXKeenAdapter(store, controller.engine, diagnostics.NewDefault(), nil))
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
	ownedProcessors := make([]connection.OwnedProcessor, 0, 1)
	if controller.engine != nil {
		ownedProcessors = append(ownedProcessors, ownedsource.NewProcessor(
			&subscription.Fetcher{Client: &http.Client{Timeout: 30 * time.Second}},
			diagnostics.NewDefault(), store, controller.engine, nil,
		))
	}
	coordinator := connection.NewCoordinator(catalogStore, registry, nil, ownedProcessors...)
	for _, adapterID := range registry.AdapterIDs() {
		// Optional engines are isolated: an unavailable adapter must not suppress
		// XKeen, WireGuard, or another healthy connection module.
		_ = coordinator.SyncAdapter(ctx, adapterID)
	}
	discoveryPaths.SingBoxProbe = singBoxProbe
	discoveryPaths.AWGManagerProbe = awgManagerProbe
	detector := capability.NewDetectorWithPaths("", discoveryPaths)
	var routeService *routegraph.Service
	if controller.domains != nil {
		routeService = routegraph.NewService(&routeEvidenceProvider{
			domains: controller.domains, catalog: catalogStore, adapters: registry,
			resolver: net.DefaultResolver, now: time.Now,
		})
	}
	backupService, err := backup.NewFileService(version, []backup.Resource{
		{ID: "catalog", Path: runtimeConfig.CatalogPath, Owned: true},
		{ID: "catalog-secrets", Path: runtimeConfig.CatalogSecretsPath, Owned: true},
		{ID: "controller-state", Path: runtimeConfig.StatePath, Owned: true},
		{ID: "device-store", Path: runtimeConfig.DeviceStorePath, Owned: true},
		{ID: "domain-policy", Path: runtimeConfig.DomainPolicyPath, Owned: true},
		{ID: "pairing-store", Path: runtimeConfig.PairingStorePath, Owned: true},
		{ID: "subscription-cache", Path: runtimeConfig.SubscriptionCache, Owned: true},
		{ID: "subscription-source", Path: subscriptionConfigurationPath(runtimeConfig), Owned: true},
		{ID: "tls-certificate", Path: runtimeConfig.TLSCertificatePath, Owned: true},
		{ID: "tls-private-key", Path: runtimeConfig.TLSPrivateKeyPath, Owned: true},
		{ID: "xkeen-exclusions", Path: runtimeConfig.ExcludePath, Owned: true},
	}, filepath.Join(runtimeConfig.BackupDir, "restore-1.0"))
	if err != nil {
		return err
	}
	selfUpdater, err := newCompanionSelfUpdater(version, root)
	if err != nil {
		return err
	}
	secureOptions := []api.SecureOption{
		api.WithCatalog(catalogStore),
		api.WithConnectionCoordinator(coordinator),
		api.WithSupport(newSupportReporter(store, version, support.NewDefault())),
		api.WithBackup(backupManager{backupService}),
		api.WithRouterLocal(routerlocal.NewService(routerlocal.ExecRunner{Executable: rootedPath(root, "/opt/bin/ndmq")})),
		api.WithWireGuardHistory(historyproxy.New(rootedPath(root, "/opt/etc/keenwg/config.json"))),
		api.WithSubscriptionConfiguration(subscriptionURLs),
		api.WithSelfUpdater(selfUpdater),
	}
	if routeService != nil {
		secureOptions = append(secureOptions, api.WithRouteExplainer(routeService))
	}
	if scenarioService != nil {
		secureOptions = append(secureOptions, api.WithScenarios(scenarioService))
	}
	if recoveryCoordinator != nil {
		secureOptions = append(secureOptions, api.WithRecovery(recoveryCoordinator))
	}
	secureHandler := api.NewSecure(controller.handler, deviceStore, detector, secureOptions...)
	secureListener, err := net.Listen("tcp4", cfg.SecureListenAddress)
	if err != nil {
		return err
	}
	tlsListener := tls.NewListener(secureListener, &tls.Config{
		Certificates: []tls.Certificate{certificate},
		MinVersion:   tls.VersionTLS12,
	})
	err = runHTTPServers(ctx, []*http.Server{hardenedServer(secureHandler)}, []net.Listener{tlsListener})
	shutdownContext, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if shutdownErr := controller.handler.Shutdown(shutdownContext); err == nil {
		err = shutdownErr
	}
	return err
}

func buildControllerMode(
	ctx context.Context,
	cfg config.Config,
	version string,
	store *state.Store,
	system xray.System,
	allowDomainRecovery, xkeenAvailable bool,
	subscriptionURLs transaction.SubscriptionURLProvider,
) (controllerRuntime, error) {
	if !xkeenAvailable {
		return controllerRuntime{handler: api.NewCore(version, nil, store)}, nil
	}
	client := &http.Client{Timeout: 30 * time.Second}
	fetcher := &subscription.Fetcher{Client: client}
	engine := transaction.NewWithSubscriptionURLProvider(cfg, fetcher, subscription.Parse, store, system, time.Now, subscriptionURLs)
	bootstrap := BootstrapDomainPolicy
	if allowDomainRecovery {
		bootstrap = BootstrapDomainPolicyForRecovery
	}
	domainService, err := bootstrap(ctx, cfg, domainRuntime{System: system, assetDir: cfg.AssetDir}, fileExists, os.Remove)
	if err != nil {
		return controllerRuntime{}, err
	}
	handler := api.NewCore(version, engine, store,
		api.WithExclusions(exclusions.New(cfg.ExcludePath, system)),
		api.WithDomainPolicy(domainService),
	)
	return controllerRuntime{handler: handler, engine: engine, domains: domainService}, nil
}

func subscriptionConfigurationPath(cfg config.Config) string {
	return filepath.Join(filepath.Dir(cfg.SubscriptionCache), "subscription-source.json")
}

func capabilityAvailable(document capability.Document, id string) bool {
	for _, item := range document.Capabilities {
		if item.ID == id {
			return item.Available
		}
	}
	return false
}

func hardenedServer(handler http.Handler) *http.Server {
	return &http.Server{
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		// A reviewed WireGuard mutation can require several bounded ndmq calls,
		// verification, save, and (on failure) rollback. Keep the HTTP budget
		// above that bounded transaction without weakening request timeouts.
		WriteTimeout:   45 * time.Second,
		IdleTimeout:    60 * time.Second,
		MaxHeaderBytes: 8 << 10,
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
