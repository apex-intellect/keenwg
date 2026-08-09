package main

import (
	"bytes"
	"context"
	"errors"
	"io/fs"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"github.com/goldb/keenwg/xkeen-control/internal/config"
	"github.com/goldb/keenwg/xkeen-control/internal/domainpolicy"
	"github.com/goldb/keenwg/xkeen-control/internal/model"
	"github.com/goldb/keenwg/xkeen-control/internal/state"
)

func TestRecoverRunsBeforeListening(t *testing.T) {
	events := []string{}
	listener := &fakeListener{}
	got, err := recoverThenListen(context.Background(), "10.8.0.1:18778", func(context.Context) error {
		events = append(events, "recover")
		return nil
	}, func(network, address string) (net.Listener, error) {
		events = append(events, "listen")
		if network != "tcp4" || address != "10.8.0.1:18778" {
			t.Fatalf("network=%q address=%q", network, address)
		}
		return listener, nil
	})
	if err != nil || got != listener || !reflect.DeepEqual(events, []string{"recover", "listen"}) {
		t.Fatalf("listener=%v events=%v err=%v", got, events, err)
	}
}

func TestCheckDoesNotRestartOrWrite(t *testing.T) {
	deps := newCommandDeps(t)
	checked := []string{}
	err := checkRuntime(deps.cfg, deps.system, func(path string, executable bool) error {
		checked = append(checked, path)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if deps.system.restartCount != 0 || deps.system.writeCount != 0 || deps.system.validateCount != 1 {
		t.Fatalf("system=%+v", deps.system)
	}
	if len(checked) != 2 || checked[0] != deps.cfg.XrayBinary || checked[1] != deps.cfg.InitScript {
		t.Fatalf("checked=%v", checked)
	}
}

func TestBootstrapActiveIsReadOnlyAndRefusesOverwrite(t *testing.T) {
	deps := newCommandDeps(t)
	if err := bootstrapActive(context.Background(), deps.cfg, deps.store, deps.system, func(context.Context) (string, error) {
		return "Нидерланды 1", nil
	}, func() time.Time { return time.Unix(100, 0) }); err != nil {
		t.Fatal(err)
	}
	controller, err := deps.store.LoadControllerState()
	if err != nil || controller.Active == nil || controller.Active.DisplayName != "Нидерланды 1" || controller.Active.ResolvedIP != "203.0.113.10" || controller.StateVersion != 1 {
		t.Fatalf("controller=%+v err=%v", controller, err)
	}
	if deps.system.restartCount != 0 || deps.system.writeCount != 0 || deps.system.resolveCount != 0 {
		t.Fatalf("bootstrap mutated router: %+v", deps.system)
	}
	if err := bootstrapActive(context.Background(), deps.cfg, deps.store, deps.system, func(context.Context) (string, error) { return "other", nil }, time.Now); !errors.Is(err, state.ErrAlreadyBootstrapped) {
		t.Fatalf("second bootstrap err=%v", err)
	}
}

func TestSelfTestRollbackRestoresBytesWithoutRestart(t *testing.T) {
	deps := newCommandDeps(t)
	saved, err := deps.store.SaveSubscription([]model.Node{commandNode()}, time.Unix(90, 0))
	if err != nil {
		t.Fatal(err)
	}
	public := model.SanitizeNode(saved.Nodes[0], true)
	active := &model.ActiveNode{PublicNode: public, ResolvedIP: "203.0.113.10", ConfirmedAt: 90}
	if err := deps.store.SaveControllerState(model.ControllerState{StateVersion: 7, Active: active}); err != nil {
		t.Fatal(err)
	}
	originalOutbounds := append([]byte(nil), deps.system.outbounds...)
	originalExcludes := append([]byte(nil), deps.system.excludes...)
	err = selfTestRollback(context.Background(), deps.cfg, deps.store, deps.system, func() bool { return true }, func() time.Time { return time.Unix(100, 0) }, bytes.NewReader(bytes.Repeat([]byte{0x11}, 16)))
	if err != nil {
		t.Fatal(err)
	}
	if deps.system.restartCount != 0 || string(deps.system.outbounds) != string(originalOutbounds) || string(deps.system.excludes) != string(originalExcludes) {
		t.Fatalf("system=%+v", deps.system)
	}
	controller, err := deps.store.LoadControllerState()
	if err != nil || len(controller.Operations) != 1 || controller.Operations[0].Result != model.ResultFailedRolledBack || controller.Active.ID != active.ID {
		t.Fatalf("controller=%+v err=%v", controller, err)
	}
}

func TestSelfTestRollbackRestoresFirstFileWhenSecondWriteFails(t *testing.T) {
	deps := newCommandDeps(t)
	saved, err := deps.store.SaveSubscription([]model.Node{commandNode()}, time.Unix(90, 0))
	if err != nil {
		t.Fatal(err)
	}
	active := &model.ActiveNode{PublicNode: model.SanitizeNode(saved.Nodes[0], true), ResolvedIP: "203.0.113.10", ConfirmedAt: 90}
	if err := deps.store.SaveControllerState(model.ControllerState{StateVersion: 7, Active: active}); err != nil {
		t.Fatal(err)
	}
	originalOutbounds := append([]byte(nil), deps.system.outbounds...)
	originalExcludes := append([]byte(nil), deps.system.excludes...)
	deps.system.failWriteAt = 2
	if err := selfTestRollback(context.Background(), deps.cfg, deps.store, deps.system, func() bool { return true }, func() time.Time { return time.Unix(100, 0) }, bytes.NewReader(bytes.Repeat([]byte{0x22}, 16))); err != nil {
		t.Fatal(err)
	}
	if deps.system.restartCount != 0 || !bytes.Equal(deps.system.outbounds, originalOutbounds) || !bytes.Equal(deps.system.excludes, originalExcludes) {
		t.Fatalf("system=%+v", deps.system)
	}
	controller, err := deps.store.LoadControllerState()
	if err != nil || len(controller.Operations) != 1 || controller.Operations[0].Result != model.ResultFailedRolledBack {
		t.Fatalf("controller=%+v err=%v", controller, err)
	}
}

func TestBootstrapDomainPolicyMigratesLegacyRouting(t *testing.T) {
	routing, err := os.ReadFile(filepath.Join("..", "..", "internal", "domainpolicy", "testdata", "05_routing.json"))
	if err != nil {
		t.Fatal(err)
	}
	system := &domainBootstrapSystem{files: map[string][]byte{"routing": routing}, geosite: true}
	cfg := config.Config{DomainPolicyPath: "policy", DomainPolicyBackup: "policy.bak", RoutingPath: "routing"}
	service, err := bootstrapDomainPolicy(context.Background(), cfg, system, func(string) (bool, error) { return false, nil }, func(string) error { system.removed = true; delete(system.files, "policy"); return nil })
	if err != nil {
		t.Fatal(err)
	}
	status, err := service.Status(context.Background())
	if err != nil || len(status.Rules) == 0 || system.validations != 1 || system.restarts != 1 {
		t.Fatalf("status=%+v err=%v system=%+v", status, err, system)
	}
	if bytes.Contains(system.files["routing"], []byte(")info$")) || bytes.Contains(system.files["routing"], []byte(")tv$")) || !bytes.Contains(system.files["routing"], []byte("category-gov-ru")) {
		t.Fatalf("routing=%s", system.files["routing"])
	}
}

func TestBootstrapDomainPolicyFailureRestoresRoutingAndRemovesNewPolicy(t *testing.T) {
	routing, err := os.ReadFile(filepath.Join("..", "..", "internal", "domainpolicy", "testdata", "05_routing.json"))
	if err != nil {
		t.Fatal(err)
	}
	system := &domainBootstrapSystem{files: map[string][]byte{"routing": append([]byte(nil), routing...)}, geosite: true, validateErr: errors.New("invalid")}
	cfg := config.Config{DomainPolicyPath: "policy", DomainPolicyBackup: "policy.bak", RoutingPath: "routing"}
	_, err = bootstrapDomainPolicy(context.Background(), cfg, system, func(string) (bool, error) { return false, nil }, func(string) error { system.removed = true; delete(system.files, "policy"); return nil })
	if err == nil || !system.removed || !bytes.Equal(system.files["routing"], routing) {
		t.Fatalf("err=%v system=%+v", err, system)
	}
}

type commandDeps struct {
	cfg    config.Config
	store  *state.Store
	system *commandSystem
}

type domainBootstrapSystem struct {
	files       map[string][]byte
	geosite     bool
	validateErr error
	validations int
	restarts    int
	removed     bool
}

func (s *domainBootstrapSystem) ReadFile(path string) ([]byte, error) {
	body, ok := s.files[path]
	if !ok {
		return nil, fs.ErrNotExist
	}
	return append([]byte(nil), body...), nil
}
func (s *domainBootstrapSystem) WriteAtomic(path string, body []byte, _ fs.FileMode) error {
	s.files[path] = append([]byte(nil), body...)
	return nil
}
func (s *domainBootstrapSystem) Validate(context.Context) error {
	s.validations++
	return s.validateErr
}
func (s *domainBootstrapSystem) Restart(context.Context) error { s.restarts++; return nil }
func (s *domainBootstrapSystem) CheckGeoSite(context.Context, string) error {
	if !s.geosite {
		return domainpolicy.ErrInvalidPolicy
	}
	return nil
}

func newCommandDeps(t *testing.T) commandDeps {
	t.Helper()
	dir := t.TempDir()
	cfg := config.Config{
		SubscriptionURL: "https://vpn.example.test/sub/private", SubscriptionCache: filepath.Join(dir, "subscription.json"), StatePath: filepath.Join(dir, "state.json"), BackupDir: filepath.Join(dir, "backups"),
		OutboundsPath: filepath.Join(dir, "04_outbounds.json"), ExcludePath: filepath.Join(dir, "ip_exclude.lst"), InitScript: "/opt/etc/init.d/S05xkeen", XrayBinary: "/opt/sbin/xray", AssetDir: "/opt/etc/xray/dat", MaxSubscriptionSize: 262144, MaxNodes: 128,
	}
	store := state.New(state.Paths{Subscription: cfg.SubscriptionCache, State: cfg.StatePath, BackupDir: cfg.BackupDir}, bytes.NewReader(bytes.Repeat([]byte{0xab}, 128)))
	system := &commandSystem{
		outboundsPath: cfg.OutboundsPath, excludesPath: cfg.ExcludePath,
		outbounds: []byte(commandOutbounds),
		excludes:  []byte("# keep\n# BEGIN KEENWG XKeen ENDPOINT\n203.0.113.10/32\n# END KEENWG XKeen ENDPOINT\n"),
	}
	return commandDeps{cfg: cfg, store: store, system: system}
}

const commandOutbounds = `{"outbounds":[{"tag":"vless-reality","protocol":"vless","settings":{"vnext":[{"address":"203.0.113.10","port":443,"users":[{"id":"aaaaaaaa-aaaa-2aaa-eaaa-aaaaaaaaaaaa","encryption":"none","flow":"xtls-rprx-vision"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"publicKey":"SYNTHETIC_KEY","fingerprint":"firefox","serverName":"intel.example.test","shortId":"0123456789abcdef","spiderX":"/"}}},{"protocol":"freedom","tag":"direct"}]}`

func commandNode() model.Node {
	return model.Node{DisplayName: "Нидерланды 1", CanonicalURI: "vless://private-synthetic", Host: "nl1.example.test", Port: 443, UUID: "aaaaaaaa-aaaa-2aaa-eaaa-aaaaaaaaaaaa", PublicKey: "SYNTHETIC_KEY", ShortID: "0123456789abcdef", SNI: "intel.example.test", SpiderX: "/", Fingerprint: "firefox", Transport: "tcp", Security: "reality", Flow: "xtls-rprx-vision"}
}

type commandSystem struct {
	outboundsPath string
	excludesPath  string
	outbounds     []byte
	excludes      []byte
	writeCount    int
	failWriteAt   int
	restartCount  int
	validateCount int
	resolveCount  int
}

func (s *commandSystem) ResolveIPv4(context.Context, string) ([]netip.Addr, error) {
	s.resolveCount++
	return []netip.Addr{netip.MustParseAddr("203.0.113.10")}, nil
}

func (s *commandSystem) ReadFile(path string) ([]byte, error) {
	if path == s.outboundsPath {
		return append([]byte(nil), s.outbounds...), nil
	}
	if path == s.excludesPath {
		return append([]byte(nil), s.excludes...), nil
	}
	return nil, errors.New("unexpected path")
}

func (s *commandSystem) WriteAtomic(path string, body []byte, _ fs.FileMode) error {
	s.writeCount++
	if s.failWriteAt == s.writeCount {
		return errors.New("injected write failure")
	}
	if path == s.outboundsPath {
		s.outbounds = append([]byte(nil), body...)
		return nil
	}
	if path == s.excludesPath {
		s.excludes = append([]byte(nil), body...)
		return nil
	}
	return errors.New("unexpected path")
}

func (s *commandSystem) Validate(context.Context) error {
	s.validateCount++
	return nil
}

func (s *commandSystem) Restart(context.Context) error {
	s.restartCount++
	return nil
}

func (s *commandSystem) Verify(context.Context, netip.Addr) error { return nil }

type fakeListener struct{}

func (*fakeListener) Accept() (net.Conn, error) { return nil, errors.New("closed") }
func (*fakeListener) Close() error              { return nil }
func (*fakeListener) Addr() net.Addr            { return fakeAddr("test") }

type fakeAddr string

func (a fakeAddr) Network() string { return string(a) }
func (a fakeAddr) String() string  { return string(a) }
