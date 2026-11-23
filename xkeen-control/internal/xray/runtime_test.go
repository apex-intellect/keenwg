package xray

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type runtimeExit int

func (e runtimeExit) Error() string { return "exit" }
func (e runtimeExit) ExitCode() int { return int(e) }

type runtimeRunner struct {
	running           bool
	failAction        string
	ignoreMutation    bool
	staleChains       bool
	staleIPv6Chains   bool
	missingIPv6Tables bool
	firewallOutput    []byte
	calls             []commandCall
}

func (r *runtimeRunner) Run(ctx context.Context, name string, args []string, env map[string]string) ([]byte, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if _, ok := ctx.Deadline(); !ok {
		return nil, errors.New("unbounded command")
	}
	r.calls = append(r.calls, commandCall{name: name, args: append([]string{}, args...), env: env})
	if len(args) > 0 && args[0] == r.failAction {
		return nil, errors.New("PRIVATE-COMMAND-OUTPUT")
	}
	if name == pidofBinary {
		if r.running {
			return []byte("123\n"), nil
		}
		return nil, runtimeExit(1)
	}
	if (name == iptablesBinary || name == "/opt/sbin/ip6tables") && len(args) == 3 && args[2] == "-S" {
		if name == "/opt/sbin/ip6tables" && r.missingIPv6Tables {
			return []byte("cannot initialize table"), runtimeExit(3)
		}
		if r.staleChains || (name == "/opt/sbin/ip6tables" && r.staleIPv6Chains) {
			return []byte("-N xkeen\n"), nil
		}
		if r.firewallOutput != nil {
			return r.firewallOutput, nil
		}
		return []byte("-P PREROUTING ACCEPT\n-P OUTPUT ACCEPT\n"), nil
	}
	if len(args) == 1 && !r.ignoreMutation {
		if args[0] == "start" {
			r.running = true
		}
		if args[0] == "stop" {
			r.running = false
		}
	}
	return nil, nil
}

func TestRuntimeStoppedRejectsOutputChainAndDirectRedirectHooks(t *testing.T) {
	for _, body := range []string{
		"-P OUTPUT ACCEPT\n-N xkeen_out\n",
		"-P PREROUTING ACCEPT\n-A _NDM_HOTSPOT_DNSREDIR -p udp -m comment --comment \"xkeen_rule\" -j REDIRECT --to-ports 1053\n",
		"-P OUTPUT ACCEPT\n-A OUTPUT -j xkeen_out\n",
	} {
		t.Run(body, func(t *testing.T) {
			s, runner := runtimeSystemFixture(t, false)
			runner.firewallOutput = []byte(body)
			if _, err := s.RuntimeStatus(context.Background()); !errors.Is(err, ErrRuntimeVerify) {
				t.Fatalf("residual hook err=%v", err)
			}
		})
	}
}

func TestRuntimeStoppedRejectsTruncatedOrEmptyFirewallOutput(t *testing.T) {
	for _, body := range [][]byte{[]byte{}, []byte(strings.Repeat(" ", maxOutputBytes))} {
		s, runner := runtimeSystemFixture(t, false)
		runner.firewallOutput = body
		if _, err := s.RuntimeStatus(context.Background()); !errors.Is(err, ErrRuntimeStatus) {
			t.Fatalf("unverifiable table length=%d err=%v", len(body), err)
		}
	}
}

func TestRuntimeStoppedUsesExactOwnedFirewallTokens(t *testing.T) {
	s, runner := runtimeSystemFixture(t, false)
	runner.firewallOutput = []byte("-P OUTPUT ACCEPT\n-N other_xkeen\n-A OUTPUT -m comment --comment xkeen_rules_backup -j ACCEPT\n")
	if running, err := s.RuntimeStatus(context.Background()); err != nil || running {
		t.Fatalf("unrelated firewall running=%v err=%v", running, err)
	}
}

func TestRuntimeStoppedRequiresIPv6FirewallVerificationWhenIPv6IsPresent(t *testing.T) {
	s, runner := runtimeSystemFixture(t, false)
	if err := os.Mkdir(filepath.Join(s.procDir, "net"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(s.procDir, "net", "if_inet6"), nil, 0600); err != nil {
		t.Fatal(err)
	}
	if got, err := s.RuntimeStatus(context.Background()); err != nil || got {
		t.Fatalf("clean dualstack=%v %v", got, err)
	}
	runner.staleIPv6Chains = true
	if _, err := s.RuntimeStatus(context.Background()); !errors.Is(err, ErrRuntimeVerify) {
		t.Fatalf("stale IPv6=%v", err)
	}
	runner.staleIPv6Chains = false
	runner.missingIPv6Tables = true
	if _, err := s.RuntimeStatus(context.Background()); !errors.Is(err, ErrRuntimeStatus) {
		t.Fatalf("unavailable IPv6 tables=%v", err)
	}
}

func TestRuntimeAlreadyEnabledStillVerifiesFirewall(t *testing.T) {
	s, runner := runtimeSystemFixture(t, true)
	runner.failAction = "test"
	if _, err := s.SetRuntime(context.Background(), true); !errors.Is(err, ErrRuntimeVerify) {
		t.Fatalf("existing invalid runtime=%v", err)
	}
}

func runtimeSystemFixture(t *testing.T, running bool) (*routerSystem, *runtimeRunner) {
	t.Helper()
	root := t.TempDir()
	cfg := testConfig(t)
	cfg.InitScript = filepath.Join(root, "S05xkeen")
	cfg.XrayBinary = filepath.Join(root, "xray")
	cfg.OutboundsPath = filepath.Join(root, "configs", "04_outbounds.json")
	for _, name := range []string{cfg.InitScript, cfg.XrayBinary} {
		if err := os.WriteFile(name, []byte("synthetic"), 0700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Mkdir(filepath.Dir(cfg.OutboundsPath), 0700); err != nil {
		t.Fatal(err)
	}
	body, err := os.ReadFile("testdata/outbounds.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(cfg.OutboundsPath, body, 0600); err != nil {
		t.Fatal(err)
	}
	runner := &runtimeRunner{running: running}
	s := newSystem(cfg, runner, fakeResolver{})
	s.procDir = filepath.Join(root, "proc")
	process := filepath.Join(s.procDir, "123")
	if err = os.MkdirAll(process, 0700); err != nil {
		t.Fatal(err)
	}
	if err = os.Symlink(cfg.XrayBinary, filepath.Join(process, "exe")); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(process, "cmdline"), []byte("xray\x00run\x00"), 0600); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(process, "environ"), []byte("XRAY_LOCATION_CONFDIR="+filepath.Dir(cfg.OutboundsPath)+"\x00"), 0600); err != nil {
		t.Fatal(err)
	}
	return s, runner
}

func TestRuntimeDesiredStateVerifiesAndNeverChangesConfiguration(t *testing.T) {
	s, runner := runtimeSystemFixture(t, false)
	before, _ := os.ReadFile(s.cfg.OutboundsPath)
	for _, enabled := range []bool{true, true, false, false} {
		got, err := s.SetRuntime(context.Background(), enabled)
		if err != nil || got != enabled {
			t.Fatalf("enabled=%v got=%v err=%v", enabled, got, err)
		}
	}
	counts := map[string]int{}
	for _, call := range runner.calls {
		if call.name == s.cfg.InitScript && len(call.args) == 1 {
			counts[call.args[0]]++
		}
		if strings.Contains(call.name, "wireguard") || strings.Contains(call.name, "companion") {
			t.Fatalf("unrelated process command: %v", call)
		}
	}
	if counts["start"] != 1 || counts["stop"] != 1 || counts["restart"] != 0 {
		t.Fatalf("calls=%v", counts)
	}
	after, _ := os.ReadFile(s.cfg.OutboundsPath)
	if string(after) != string(before) {
		t.Fatal("runtime mutated selected configuration")
	}
}

func TestRuntimeStatusDistinguishesStoppedErrorsAndDiagnosticProcesses(t *testing.T) {
	s, runner := runtimeSystemFixture(t, false)
	if got, err := s.RuntimeStatus(context.Background()); err != nil || got {
		t.Fatalf("stopped=%v err=%v", got, err)
	}
	runner.failAction = "status"
	if _, err := s.RuntimeStatus(context.Background()); !errors.Is(err, ErrRuntimeStatus) || strings.Contains(err.Error(), "PRIVATE") {
		t.Fatalf("err=%v", err)
	}
	runner.failAction = ""
	runner.running = true
	if got, err := s.RuntimeStatus(context.Background()); err != nil || !got {
		t.Fatalf("running=%v err=%v", got, err)
	}
	if err := os.WriteFile(filepath.Join(s.procDir, "123", "cmdline"), []byte("xray\x00run\x00-config\x00/tmp/private.json\x00"), 0600); err != nil {
		t.Fatal(err)
	}
	if got, err := s.RuntimeStatus(context.Background()); err != nil || got {
		t.Fatalf("diagnostic counted running=%v err=%v", got, err)
	}
	if err := os.Remove(s.cfg.InitScript); err != nil {
		t.Fatal(err)
	}
	if _, err := s.RuntimeStatus(context.Background()); !errors.Is(err, ErrRuntimeUnsupported) {
		t.Fatalf("missing init err=%v", err)
	}
}

func TestRuntimeNeverReportsSuccessfulStopWhenProcessSurvives(t *testing.T) {
	s, runner := runtimeSystemFixture(t, true)
	runner.ignoreMutation = true
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	_, err := s.SetRuntime(ctx, false)
	if !errors.Is(err, ErrRuntimeVerify) {
		t.Fatalf("err=%v", err)
	}
}

func TestRuntimeNeverClaimsStoppedWithResidualFirewall(t *testing.T) {
	s, runner := runtimeSystemFixture(t, false)
	runner.staleChains = true
	if _, err := s.RuntimeStatus(context.Background()); !errors.Is(err, ErrRuntimeVerify) {
		t.Fatalf("residual firewall status=%v", err)
	}
	if _, err := s.SetRuntime(context.Background(), false); !errors.Is(err, ErrRuntimeVerify) {
		t.Fatalf("residual firewall stop=%v", err)
	}
	for _, call := range runner.calls {
		if len(call.args) == 1 && call.args[0] == "stop" {
			t.Fatal("ineffective stop attempted without verified initial state")
		}
	}
}

func TestRuntimeStartFailureIsSanitizedAndDoesNotTouchOtherServices(t *testing.T) {
	s, runner := runtimeSystemFixture(t, false)
	runner.failAction = "start"
	_, err := s.SetRuntime(context.Background(), true)
	if !errors.Is(err, ErrRuntimeStart) || strings.Contains(err.Error(), "PRIVATE") {
		t.Fatalf("err=%v", err)
	}
	for _, call := range runner.calls {
		if len(call.args) == 1 && (call.args[0] == "stop" || call.args[0] == "restart") {
			t.Fatalf("unexpected recovery mutation: %+v", call)
		}
	}
}
