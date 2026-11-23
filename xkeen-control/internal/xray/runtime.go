package xray

import (
	"context"
	"errors"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

var (
	ErrRuntimeUnsupported = errors.New("feature_unavailable")
	ErrRuntimeStatus      = errors.New("runtime_status_failed")
	ErrRuntimeStart       = errors.New("runtime_start_failed")
	ErrRuntimeStop        = errors.New("runtime_stop_failed")
	ErrRuntimeVerify      = errors.New("runtime_verification_failed")
)

// RuntimeSystem changes only the current process state, never boot settings.
type RuntimeSystem interface {
	RuntimeStatus(context.Context) (bool, error)
	SetRuntime(context.Context, bool) (bool, error)
}

func (s *routerSystem) runtimeSupported() bool {
	for _, name := range []string{s.cfg.InitScript, s.cfg.XrayBinary} {
		info, err := os.Stat(name)
		if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0111 == 0 {
			return false
		}
	}
	return true
}

func (s *routerSystem) RuntimeStatus(ctx context.Context) (bool, error) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if ctx.Err() != nil {
		return false, ErrRuntimeStatus
	}
	if !s.runtimeSupported() {
		return false, ErrRuntimeUnsupported
	}
	// XKeen 2.0's status exits zero for both started and stopped. Verify the
	// configured process separately; a failed command is never a false/off state.
	if _, err := s.run(ctx, s.cfg.InitScript, []string{"status"}, nil, 5*time.Second); err != nil {
		return false, ErrRuntimeStatus
	}
	output, err := s.run(ctx, pidofBinary, []string{"xray"}, nil, 5*time.Second)
	if err != nil {
		var exit interface{ ExitCode() int }
		if errors.As(err, &exit) && exit.ExitCode() == 1 && strings.TrimSpace(string(output)) == "" && ctx.Err() == nil {
			return false, s.verifyStopped(ctx)
		}
		return false, ErrRuntimeStatus
	}
	if len(strings.Fields(string(output))) == 0 {
		return false, ErrRuntimeStatus
	}
	for _, pid := range strings.Fields(string(output)) {
		if number, err := strconv.Atoi(pid); err != nil || number < 1 {
			return false, ErrRuntimeStatus
		}
		processDir := filepath.Join(s.procDir, pid)
		executable, err := os.Readlink(filepath.Join(processDir, "exe"))
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return false, ErrRuntimeStatus
		}
		if strings.TrimSuffix(executable, " (deleted)") != s.cfg.XrayBinary {
			continue
		}
		arguments, err := os.ReadFile(filepath.Join(processDir, "cmdline"))
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return false, ErrRuntimeStatus
		}
		args := strings.Split(strings.TrimRight(string(arguments), "\x00"), "\x00")
		// Managed XKeen launches `xray run` with its configured confdir in the
		// environment. Exclude short-lived validation and diagnostic processes.
		if len(args) != 2 || args[1] != "run" {
			continue
		}
		environment, err := os.ReadFile(filepath.Join(processDir, "environ"))
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return false, ErrRuntimeStatus
		}
		for _, value := range strings.Split(string(environment), "\x00") {
			if value == "XRAY_LOCATION_CONFDIR="+filepath.Dir(s.cfg.OutboundsPath) {
				return true, nil
			}
		}
	}
	if ctx.Err() != nil {
		return false, ErrRuntimeStatus
	}
	return false, s.verifyStopped(ctx)
}

// A crashed process can leave active redirects behind. Inspect complete tables:
// XKeen also owns xkeen_out and direct xkeen_rule hooks outside its base chain.
// Any owned token is conservatively considered residual, even if unreferenced.
func (s *routerSystem) verifyStopped(ctx context.Context) error {
	binaries := []string{iptablesBinary}
	if _, err := os.Stat(filepath.Join(s.procDir, "net", "if_inet6")); err == nil {
		binaries = append(binaries, "/opt/sbin/ip6tables")
	} else if !errors.Is(err, os.ErrNotExist) {
		return ErrRuntimeStatus
	}
	for _, binary := range binaries {
		for _, table := range []string{"nat", "mangle"} {
			body, err := s.run(ctx, binary, []string{"-t", table, "-S"}, nil, 5*time.Second)
			// The runner bounds output; a truncated table cannot prove absence.
			if err != nil || ctx.Err() != nil || len(body) >= maxOutputBytes || len(strings.TrimSpace(string(body))) == 0 {
				return ErrRuntimeStatus
			}
			for _, token := range strings.Fields(string(body)) {
				switch strings.Trim(token, "\"'") {
				case "xkeen", "xkeen_out", "xkeen_rule":
					return ErrRuntimeVerify
				}
			}
		}
	}
	return nil
}

func (s *routerSystem) SetRuntime(ctx context.Context, enabled bool) (bool, error) {
	ctx, cancelOperation := context.WithTimeout(ctx, 60*time.Second)
	defer cancelOperation()
	running, err := s.RuntimeStatus(ctx)
	if err != nil || (!enabled && !running) {
		return running, err
	}
	var endpoint netip.Addr
	if enabled {
		body, readErr := s.ReadFile(s.cfg.OutboundsPath)
		active, parseErr := ParseActiveOutbound(body, "", 0)
		if readErr != nil || parseErr != nil || active == nil {
			return running, ErrRuntimeStart
		}
		endpoint, err = netip.ParseAddr(active.ResolvedIP)
		if err != nil || s.Validate(ctx) != nil {
			return running, ErrRuntimeStart
		}
		if running {
			if s.Verify(ctx, endpoint) != nil {
				return running, ErrRuntimeVerify
			}
			return running, nil
		}
	}
	action, actionError := "stop", ErrRuntimeStop
	if enabled {
		action, actionError = "start", ErrRuntimeStart
	}
	if _, err := s.run(ctx, s.cfg.InitScript, []string{action}, nil, 45*time.Second); err != nil {
		return running, actionError
	}
	// start may launch cold_start asynchronously; the Engine's 60-second
	// deadline remains the outer budget for startup and firewall verification.
	verifyContext, cancel := context.WithTimeout(ctx, 55*time.Second)
	defer cancel()
	for {
		observed, statusErr := s.RuntimeStatus(verifyContext)
		if statusErr != nil && !errors.Is(statusErr, ErrRuntimeVerify) {
			return observed, ErrRuntimeVerify
		}
		if statusErr == nil && observed == enabled && (!enabled || s.Verify(verifyContext, endpoint) == nil) {
			return observed, nil
		}
		select {
		case <-verifyContext.Done():
			return observed, ErrRuntimeVerify
		case <-time.After(time.Second):
		}
	}
}
