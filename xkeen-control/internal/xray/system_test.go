package xray

import (
	"context"
	"errors"
	"net/netip"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/goldb/keenwg/xkeen-control/internal/config"
)

func TestResolveIPv4RejectsUnsafeRangesAndPreservesPublicOrder(t *testing.T) {
	resolver := fakeResolver{addresses: map[string][]netip.Addr{
		"node.example.test": {
			netip.MustParseAddr("127.0.0.1"),
			netip.MustParseAddr("203.0.113.9"),
			netip.MustParseAddr("10.0.0.1"),
			netip.MustParseAddr("203.0.113.10"),
			netip.MustParseAddr("203.0.113.9"),
		},
	}}
	s := newSystem(testConfig(t), &recordingRunner{}, resolver)
	got, err := s.ResolveIPv4(context.Background(), "node.example.test")
	if err != nil {
		t.Fatal(err)
	}
	want := []netip.Addr{netip.MustParseAddr("203.0.113.9"), netip.MustParseAddr("203.0.113.10")}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got=%v want=%v", got, want)
	}
}

func TestResolveIPv4AllowsPrivateOnlyWhenExplicitlyConfigured(t *testing.T) {
	resolver := fakeResolver{addresses: map[string][]netip.Addr{"node.example.test": {netip.MustParseAddr("10.0.0.5"), netip.MustParseAddr("100.64.0.9")}}}
	cfg := testConfig(t)
	if _, err := newSystem(cfg, &recordingRunner{}, resolver).ResolveIPv4(context.Background(), "node.example.test"); !errors.Is(err, ErrResolve) {
		t.Fatalf("private accepted: %v", err)
	}
	cfg.AllowPrivateServers = true
	got, err := newSystem(cfg, &recordingRunner{}, resolver).ResolveIPv4(context.Background(), "node.example.test")
	if err != nil || len(got) != 2 {
		t.Fatalf("got=%v err=%v", got, err)
	}
}

func TestValidateUsesFixedBinaryAssetAndConfdir(t *testing.T) {
	runner := &recordingRunner{}
	s := newSystem(testConfig(t), runner, fakeResolver{})
	if err := s.Validate(context.Background()); err != nil {
		t.Fatal(err)
	}
	want := commandCall{
		name: "/opt/sbin/xray",
		args: []string{"run", "-test", "-confdir", "/opt/etc/xray/configs"},
		env:  map[string]string{"XRAY_LOCATION_ASSET": "/opt/etc/xray/dat", "XRAY_LOCATION_CONFDIR": "/opt/etc/xray/configs"},
	}
	if len(runner.calls) != 1 || !reflect.DeepEqual(runner.calls[0], want) {
		t.Fatalf("calls=%+v", runner.calls)
	}
}

func TestRestartAndVerifyUseXKeen2NativeExclusionCommands(t *testing.T) {
	runner := &recordingRunner{}
	s := newSystem(testConfig(t), runner, fakeResolver{})
	if err := s.Restart(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := s.Verify(context.Background(), netip.MustParseAddr("203.0.113.44")); err != nil {
		t.Fatal(err)
	}
	want := []commandCall{
		{name: "/opt/etc/init.d/S05xkeen", args: []string{"restart"}},
		{name: "/opt/etc/init.d/S05xkeen", args: []string{"status"}},
		{name: "/opt/bin/pidof", args: []string{"xray"}},
		{name: "/opt/sbin/ipset", args: []string{"test", "user_exclude", "203.0.113.44"}},
		{name: "/opt/sbin/iptables", args: []string{"-t", "nat", "-C", "xkeen", "-m", "set", "--match-set", "user_exclude", "dst", "-m", "comment", "--comment", "xkeen_rule", "-j", "RETURN"}},
		{name: "/opt/sbin/iptables", args: []string{"-t", "mangle", "-C", "xkeen", "-m", "set", "--match-set", "user_exclude", "dst", "-m", "comment", "--comment", "xkeen_rule", "-j", "RETURN"}},
	}
	if !reflect.DeepEqual(runner.calls, want) {
		t.Fatalf("calls=%+v", runner.calls)
	}
}

func TestSystemMapsSecretBearingCommandFailuresToSentinelErrors(t *testing.T) {
	runner := &recordingRunner{failAt: "/opt/sbin/xray", failure: errors.New("private UUID 11111111-1111-4111-8111-111111111111")}
	err := newSystem(testConfig(t), runner, fakeResolver{}).Validate(context.Background())
	if !errors.Is(err, ErrValidate) || strings.Contains(err.Error(), "11111111") {
		t.Fatalf("err=%v", err)
	}
}

func TestWriteAtomicAcceptsOnlyConfiguredFiles(t *testing.T) {
	cfg := testConfig(t)
	dir := t.TempDir()
	cfg.OutboundsPath = filepath.Join(dir, "04_outbounds.json")
	cfg.ExcludePath = filepath.Join(dir, "ip_exclude.lst")
	cfg.DomainPolicyPath = filepath.Join(dir, "domain-policy.json")
	cfg.DomainPolicyBackup = filepath.Join(dir, "domain-policy.json.bak")
	cfg.RoutingPath = filepath.Join(dir, "05_routing.json")
	s := newSystem(cfg, &recordingRunner{}, fakeResolver{})
	if err := s.WriteAtomic(cfg.OutboundsPath, []byte("new"), 0o600); err != nil {
		t.Fatal(err)
	}
	body, err := os.ReadFile(cfg.OutboundsPath)
	if err != nil || string(body) != "new" {
		t.Fatalf("body=%q err=%v", body, err)
	}
	if err := s.WriteAtomic(filepath.Join(dir, "other"), []byte("unsafe"), 0o600); !errors.Is(err, ErrWrite) {
		t.Fatalf("unsafe path err=%v", err)
	}
	for _, path := range []string{cfg.DomainPolicyPath, cfg.DomainPolicyBackup, cfg.RoutingPath} {
		if err := s.WriteAtomic(path, []byte("managed"), 0o600); err != nil {
			t.Fatalf("path=%s err=%v", path, err)
		}
	}
}

func TestGeoSiteAvailableReadsOnlyAllowlistedAsset(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "geosite_v2fly.dat"), []byte("binary CATEGORY-GOV-RU payload"), 0o600); err != nil {
		t.Fatal(err)
	}
	if !GeoSiteAvailable(dir, "category-gov-ru") {
		t.Fatal("available category not found")
	}
	if GeoSiteAvailable(dir, "private") {
		t.Fatal("unapproved category accepted")
	}
	if GeoSiteAvailable(filepath.Join(dir, "missing"), "category-gov-ru") {
		t.Fatal("missing asset accepted")
	}
}

func TestExecRunnerDoesNotWaitForDetachedDescendantHoldingOutput(t *testing.T) {
	switch os.Getenv("KEENWG_EXEC_RUNNER_HELPER") {
	case "parent":
		child := exec.Command(os.Args[0], "-test.run=^TestExecRunnerDoesNotWaitForDetachedDescendantHoldingOutput$")
		child.Env = []string{"KEENWG_EXEC_RUNNER_HELPER=child"}
		child.Stdout = os.Stdout
		child.Stderr = os.Stderr
		if err := child.Start(); err != nil {
			t.Fatal(err)
		}
		return
	case "child":
		time.Sleep(3 * time.Second)
		return
	}

	started := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if _, err := (execRunner{}).Run(ctx, os.Args[0], []string{"-test.run=^TestExecRunnerDoesNotWaitForDetachedDescendantHoldingOutput$"}, map[string]string{"KEENWG_EXEC_RUNNER_HELPER": "parent"}); err != nil {
		t.Fatal(err)
	}
	if elapsed := time.Since(started); elapsed > 2*time.Second {
		t.Fatalf("runner waited %v for detached descendant holding stdout", elapsed)
	}
}

type fakeResolver struct {
	addresses map[string][]netip.Addr
	err       error
}

func (f fakeResolver) LookupNetIP(context.Context, string, string) ([]netip.Addr, error) {
	if f.err != nil {
		return nil, f.err
	}
	return append([]netip.Addr(nil), f.addresses["node.example.test"]...), nil
}

type commandCall struct {
	name string
	args []string
	env  map[string]string
}

type recordingRunner struct {
	calls   []commandCall
	failAt  string
	failure error
}

func (r *recordingRunner) Run(_ context.Context, name string, args []string, env map[string]string) ([]byte, error) {
	r.calls = append(r.calls, commandCall{name: name, args: append([]string(nil), args...), env: cloneStringMap(env)})
	if name == r.failAt {
		return []byte("private output"), r.failure
	}
	return []byte("ok"), nil
}

func cloneStringMap(input map[string]string) map[string]string {
	if input == nil {
		return nil
	}
	result := make(map[string]string, len(input))
	for key, value := range input {
		result[key] = value
	}
	return result
}

func testConfig(t *testing.T) config.Config {
	t.Helper()
	return config.Config{
		OutboundsPath: "/opt/etc/xray/configs/04_outbounds.json",
		ExcludePath:   "/opt/etc/xkeen/ip_exclude.lst",
		InitScript:    "/opt/etc/init.d/S05xkeen",
		XrayBinary:    "/opt/sbin/xray",
		AssetDir:      "/opt/etc/xray/dat",
	}
}
