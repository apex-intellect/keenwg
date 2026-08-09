package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/goldb/keenwg/xkeen-control/internal/app"
	"github.com/goldb/keenwg/xkeen-control/internal/config"
	"github.com/goldb/keenwg/xkeen-control/internal/domainpolicy"
	"github.com/goldb/keenwg/xkeen-control/internal/model"
	"github.com/goldb/keenwg/xkeen-control/internal/state"
	"github.com/goldb/keenwg/xkeen-control/internal/xray"
)

var (
	version = "dev"
	commit  = "unknown"
)

const (
	defaultConfigPath = "/opt/etc/keenwg/xkeen-control.json"
	legacyCountryPath = "/opt/sbin/xkeen-country"
	servicePIDPath    = "/opt/var/run/keenwg-xkeen-control.pid"
)

func main() {
	if err := command(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "keenwg-xkeen-control:", publicError(err))
		os.Exit(1)
	}
}

func command(arguments []string) error {
	flags := flag.NewFlagSet("keenwg-xkeen-control", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	configPath := flags.String("config", defaultConfigPath, "controller config")
	check := flags.Bool("check", false, "validate configuration")
	status := flags.Bool("status", false, "print sanitized status")
	bootstrap := flags.Bool("bootstrap-active", false, "capture the current active node")
	rollbackTest := flags.Bool("self-test-rollback", false, "test pre-restart rollback")
	showVersion := flags.Bool("version", false, "print version")
	if err := flags.Parse(arguments); err != nil || flags.NArg() != 0 {
		return errors.New("invalid arguments")
	}
	selected := 0
	for _, enabled := range []bool{*check, *status, *bootstrap, *rollbackTest, *showVersion} {
		if enabled {
			selected++
		}
	}
	if selected > 1 {
		return errors.New("conflicting modes")
	}
	if *showVersion {
		fmt.Printf("keenwg-xkeen-control %s (%s)\n", version, commit)
		return nil
	}
	cfg, err := loadConfig(*configPath)
	if err != nil {
		return err
	}
	store := state.New(state.Paths{Subscription: cfg.SubscriptionCache, State: cfg.StatePath, BackupDir: cfg.BackupDir}, rand.Reader)
	system := xray.NewSystem(cfg)
	if *check {
		if err := checkPrivateFile(*configPath, true); err != nil {
			return err
		}
		if err := checkPrivateFile(cfg.SubscriptionCache, false); err != nil {
			return err
		}
		if err := checkPrivateFile(cfg.StatePath, false); err != nil {
			return err
		}
		return checkRuntime(cfg, system, checkRequiredPath)
	}
	if *status {
		return printStatus(store, os.Stdout)
	}
	ctx := context.Background()
	if *bootstrap {
		return bootstrapActive(ctx, cfg, store, system, legacyCountryLabel, time.Now)
	}
	if *rollbackTest {
		return selfTestRollback(ctx, cfg, store, system, controllerStopped, time.Now, rand.Reader)
	}
	return serve(cfg, store, system)
}

func loadConfig(path string) (config.Config, error) {
	file, err := os.Open(path)
	if err != nil {
		return config.Config{}, errors.New("config unavailable")
	}
	defer file.Close()
	cfg, err := config.Decode(io.LimitReader(file, 64<<10))
	if err != nil {
		return config.Config{}, err
	}
	return cfg, nil
}

func serve(cfg config.Config, store *state.Store, system xray.System) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	return app.RunLegacy(ctx, cfg, version, store, system)
}

func bootstrapDomainPolicy(
	ctx context.Context,
	cfg config.Config,
	runtime domainpolicy.RuntimeSystem,
	exists func(string) (bool, error),
	remove func(string) error,
) (*domainpolicy.Service, error) {
	return app.BootstrapDomainPolicy(ctx, cfg, runtime, exists, remove)
}

func recoverThenListen(ctx context.Context, address string, recover func(context.Context) error, listen func(string, string) (net.Listener, error)) (net.Listener, error) {
	if err := recover(ctx); err != nil {
		return nil, err
	}
	return listen("tcp4", address)
}

func checkRuntime(cfg config.Config, system xray.System, checkPath func(string, bool) error) error {
	if err := checkPath(cfg.XrayBinary, true); err != nil {
		return err
	}
	if err := checkPath(cfg.InitScript, true); err != nil {
		return err
	}
	outbounds, err := system.ReadFile(cfg.OutboundsPath)
	if err != nil {
		return err
	}
	active, err := xray.ParseActiveOutbound(outbounds, "", 0)
	if err != nil {
		return err
	}
	excludes, err := system.ReadFile(cfg.ExcludePath)
	if err != nil {
		return err
	}
	excludedIP, err := xray.ManagedExcludeIP(excludes)
	if err != nil || excludedIP.String() != active.ResolvedIP {
		return errors.New("active endpoint exclusion mismatch")
	}
	return system.Validate(context.Background())
}

func checkRequiredPath(path string, executable bool) error {
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() {
		return errors.New("required path unavailable")
	}
	if executable && info.Mode().Perm()&0o111 == 0 {
		return errors.New("required path is not executable")
	}
	return nil
}

func checkPrivateFile(path string, required bool) error {
	info, err := os.Stat(path)
	if errors.Is(err, os.ErrNotExist) && !required {
		return nil
	}
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm() != 0o600 {
		return errors.New("private file permissions invalid")
	}
	return nil
}

func bootstrapActive(ctx context.Context, cfg config.Config, store *state.Store, system xray.System, label func(context.Context) (string, error), clock func() time.Time) error {
	controller, err := store.LoadControllerState()
	if err != nil {
		return err
	}
	if controller.Active != nil {
		return state.ErrAlreadyBootstrapped
	}
	displayName, err := label(ctx)
	if err != nil {
		return err
	}
	outbounds, err := system.ReadFile(cfg.OutboundsPath)
	if err != nil {
		return err
	}
	active, err := xray.ParseActiveOutbound(outbounds, displayName, clock().Unix())
	if err != nil {
		return err
	}
	controller.Active = active
	if controller.StateVersion == 0 {
		controller.StateVersion = 1
	}
	return store.SaveControllerState(controller)
}

func legacyCountryLabel(ctx context.Context) (string, error) {
	commandContext, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	command := exec.CommandContext(commandContext, legacyCountryPath, "status")
	command.Env = []string{"PATH=/opt/bin:/opt/sbin:/sbin:/bin:/usr/sbin:/usr/bin"}
	output, err := command.Output()
	if err != nil || len(output) > 16<<10 {
		return "", errors.New("legacy status unavailable")
	}
	for _, line := range strings.Split(string(output), "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "Страна:") {
			value := strings.TrimSpace(strings.TrimPrefix(trimmed, "Страна:"))
			if value != "" && len(value) <= 128 {
				return value, nil
			}
		}
	}
	return "Текущий узел", nil
}

func selfTestRollback(ctx context.Context, cfg config.Config, store *state.Store, system xray.System, stopped func() bool, clock func() time.Time, random io.Reader) error {
	if !stopped() {
		return errors.New("controller service must be stopped")
	}
	controller, err := store.LoadControllerState()
	if err != nil || controller.Active == nil || controller.Active.ID == "" {
		return errors.New("confirmed subscription node required")
	}
	subscriptionState, err := store.LoadSubscription()
	if err != nil {
		return err
	}
	var node *model.Node
	for i := range subscriptionState.Nodes {
		if subscriptionState.Nodes[i].ID == controller.Active.ID {
			node = &subscriptionState.Nodes[i]
			break
		}
	}
	if node == nil {
		return errors.New("active node missing from subscription")
	}
	activeIP, err := netip.ParseAddr(controller.Active.ResolvedIP)
	if err != nil {
		return errors.New("invalid active endpoint")
	}
	originalOutbounds, err := system.ReadFile(cfg.OutboundsPath)
	if err != nil {
		return err
	}
	originalExcludes, err := system.ReadFile(cfg.ExcludePath)
	if err != nil {
		return err
	}
	candidateOutbounds, err := xray.RenderOutbounds(originalOutbounds, *node, activeIP)
	if err != nil {
		return err
	}
	candidateExcludes, err := xray.ReplaceManagedExcludeBlock(originalExcludes, activeIP)
	if err != nil {
		return err
	}
	key, err := randomOperationKey(random)
	if err != nil {
		return err
	}
	operation := model.Operation{IdempotencyKey: key, Kind: "self_test_rollback", State: model.OperationQueued, StartedAt: clock().Unix()}
	snapshot := &model.TransactionSnapshot{OperationKey: key, Kind: operation.Kind, Phase: "queued"}
	if err := store.BeginOperation(operation, snapshot); err != nil {
		return err
	}
	snapshot = &model.TransactionSnapshot{
		OperationKey: key, Kind: operation.Kind, Phase: "writing",
		OriginalOutbounds: append([]byte(nil), originalOutbounds...), OriginalExcludes: append([]byte(nil), originalExcludes...),
		OriginalActive: cloneActive(controller.Active), OriginalStateVersion: controller.StateVersion,
		OriginalIP: activeIP.String(), CandidateIP: activeIP.String(),
	}
	operation.State = model.OperationRunning
	if err := store.UpdateOperation(operation, snapshot); err != nil {
		return err
	}
	if err := system.WriteAtomic(cfg.OutboundsPath, candidateOutbounds, 0o600); err != nil {
		return finishSelfTest(store, operation, clock, model.ResultFailedNoChange, "write_failed")
	}
	if err := system.WriteAtomic(cfg.ExcludePath, candidateExcludes, 0o600); err != nil {
		if restoreAndConfirm(ctx, cfg, system, activeIP, originalOutbounds, originalExcludes) {
			return finishSelfTest(store, operation, clock, model.ResultFailedRolledBack, "write_failed")
		}
		_ = finishSelfTest(store, operation, clock, model.ResultUncertain, "rollback_failed")
		return errors.New("rollback could not be confirmed")
	}
	if !restoreAndConfirm(ctx, cfg, system, activeIP, originalOutbounds, originalExcludes) {
		if finishErr := finishSelfTest(store, operation, clock, model.ResultUncertain, "rollback_failed"); finishErr != nil {
			return finishErr
		}
		return errors.New("rollback could not be confirmed")
	}
	return finishSelfTest(store, operation, clock, model.ResultFailedRolledBack, "self_test_injected_failure")
}

func restoreAndConfirm(ctx context.Context, cfg config.Config, system xray.System, activeIP netip.Addr, originalOutbounds, originalExcludes []byte) bool {
	rollbackOK := true
	if system.WriteAtomic(cfg.OutboundsPath, originalOutbounds, 0o600) != nil {
		rollbackOK = false
	}
	if system.WriteAtomic(cfg.ExcludePath, originalExcludes, 0o600) != nil {
		rollbackOK = false
	}
	if system.Validate(ctx) != nil || system.Verify(ctx, activeIP) != nil {
		rollbackOK = false
	}
	restoredOutbounds, outboundsErr := system.ReadFile(cfg.OutboundsPath)
	restoredExcludes, excludesErr := system.ReadFile(cfg.ExcludePath)
	if outboundsErr != nil || excludesErr != nil || !bytes.Equal(restoredOutbounds, originalOutbounds) || !bytes.Equal(restoredExcludes, originalExcludes) {
		rollbackOK = false
	}
	return rollbackOK
}

func finishSelfTest(store *state.Store, operation model.Operation, clock func() time.Time, result model.OperationResult, code string) error {
	finished := clock().Unix()
	operation.State = model.OperationTerminal
	operation.Result = result
	operation.ErrorCode = code
	operation.FinishedAt = &finished
	return store.UpdateOperation(operation, nil)
}

func randomOperationKey(reader io.Reader) (string, error) {
	data := make([]byte, 16)
	if _, err := io.ReadFull(reader, data); err != nil {
		return "", err
	}
	encoded := hex.EncodeToString(data)
	return encoded[:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" + encoded[16:20] + "-" + encoded[20:], nil
}

func controllerStopped() bool {
	_, err := os.Stat(servicePIDPath)
	return errors.Is(err, os.ErrNotExist)
}

func printStatus(store *state.Store, output io.Writer) error {
	controller, err := store.LoadControllerState()
	if err != nil {
		return err
	}
	name, ip, fingerprint := "—", "—", "—"
	if controller.Active != nil {
		name, ip, fingerprint = controller.Active.DisplayName, controller.Active.ResolvedIP, controller.Active.Fingerprint
	}
	latest := "—"
	for i := len(controller.Operations) - 1; i >= 0; i-- {
		if controller.Operations[i].State == model.OperationTerminal {
			latest = string(controller.Operations[i].Result)
			break
		}
	}
	_, err = fmt.Fprintf(output, "Страна: %s\nIP: %s\nFingerprint: %s\nState version: %d\nПоследняя операция: %s\n", name, ip, fingerprint, controller.StateVersion, latest)
	return err
}

func cloneActive(active *model.ActiveNode) *model.ActiveNode {
	if active == nil {
		return nil
	}
	cloned := *active
	cloned.Warnings = append([]string(nil), active.Warnings...)
	return &cloned
}

func publicError(err error) string {
	switch {
	case errors.Is(err, config.ErrInvalidConfig):
		return "invalid configuration"
	case errors.Is(err, state.ErrAlreadyBootstrapped):
		return "active state already initialized"
	default:
		return "operation failed"
	}
}
