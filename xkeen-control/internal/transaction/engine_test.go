package transaction

import (
	"context"
	"errors"
	"io/fs"
	"net/netip"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/goldb/keenwg/xkeen-control/internal/config"
	"github.com/goldb/keenwg/xkeen-control/internal/model"
	"github.com/goldb/keenwg/xkeen-control/internal/state"
	"github.com/goldb/keenwg/xkeen-control/internal/subscription"
)

func TestRefreshNeverCallsRouterMutation(t *testing.T) {
	deps := newEngineDeps(t)
	engine := New(deps.cfg, deps.fetcher, subscription.Parse, deps.store, deps.system, deps.clock)
	operation, job, err := engine.PrepareRefresh("11111111-1111-4111-8111-111111111111", deps.stateVersion)
	if err != nil || job == nil || operation.State != model.OperationQueued {
		t.Fatalf("operation=%+v job=%v err=%v", operation, job, err)
	}
	job(context.Background())
	op, found, err := deps.store.FindOperation(operation.IdempotencyKey)
	if err != nil || !found || op.Result != model.ResultSuccess || op.State != model.OperationTerminal {
		t.Fatalf("op=%+v found=%v err=%v", op, found, err)
	}
	if len(deps.system.events) != 0 {
		t.Fatalf("router mutated during refresh: %v", deps.system.events)
	}
	sub, err := deps.store.LoadSubscription()
	if err != nil || len(sub.Nodes) != 1 || sub.Nodes[0].DisplayName != "🇳🇱 Нидерланды 1" {
		t.Fatalf("subscription=%+v err=%v", sub, err)
	}
	controller, err := deps.store.LoadControllerState()
	if err != nil || controller.StateVersion != deps.stateVersion+1 || controller.Active == nil || controller.Active.ID != deps.originalActive.ID {
		t.Fatalf("controller=%+v err=%v", controller, err)
	}
}

func TestSelectWritesValidatesRestartsVerifiesThenConfirms(t *testing.T) {
	deps := newEngineDeps(t)
	saved, err := deps.store.SaveSubscription([]model.Node{testNode()}, time.Unix(90, 0))
	if err != nil {
		t.Fatal(err)
	}
	engine := New(deps.cfg, deps.fetcher, subscription.Parse, deps.store, deps.system, deps.clock)
	operation, job, err := engine.PrepareSelect("22222222-2222-4222-8222-222222222222", saved.Nodes[0].ID, deps.stateVersion)
	if err != nil || job == nil {
		t.Fatalf("operation=%+v job=%v err=%v", operation, job, err)
	}
	job(context.Background())
	op, found, err := deps.store.FindOperation(operation.IdempotencyKey)
	if err != nil || !found || op.Result != model.ResultSuccess {
		t.Fatalf("op=%+v found=%v err=%v", op, found, err)
	}
	want := []string{"resolve", "read-outbounds", "read-excludes", "write-outbounds", "write-excludes", "validate", "restart", "verify"}
	if !reflect.DeepEqual(deps.system.events, want) {
		t.Fatalf("events=%v", deps.system.events)
	}
	controller, err := deps.store.LoadControllerState()
	if err != nil || controller.Active == nil || controller.Active.ID != saved.Nodes[0].ID || controller.Active.ResolvedIP != "203.0.113.44" || controller.StateVersion != deps.stateVersion+1 {
		t.Fatalf("controller=%+v err=%v", controller, err)
	}
	if !strings.Contains(string(deps.system.outbounds), `"address": "203.0.113.44"`) || !strings.Contains(string(deps.system.excludes), "203.0.113.44/32") {
		t.Fatalf("outbounds=%s excludes=%s", deps.system.outbounds, deps.system.excludes)
	}
}

func TestSelectNodeActivatesReviewedExternalNodeWithoutChangingSubscription(t *testing.T) {
	deps := newEngineDeps(t)
	engine := New(deps.cfg, deps.fetcher, subscription.Parse, deps.store, deps.system, deps.clock)
	external := testNode()
	external.ID = "external-catalog-node"
	if _, _, err := engine.PrepareSelectNode("2a222222-2222-4222-8222-222222222222", external, deps.stateVersion-1); !errors.Is(err, ErrStaleState) {
		t.Fatalf("stale error=%v", err)
	}
	operation, job, err := engine.PrepareSelectNode("2b222222-2222-4222-8222-222222222222", external, deps.stateVersion)
	if err != nil || job == nil {
		t.Fatalf("operation=%+v job=%v err=%v", operation, job, err)
	}
	job(context.Background())
	controller, err := deps.store.LoadControllerState()
	if err != nil || controller.Active == nil || controller.Active.ID != external.ID {
		t.Fatalf("controller=%+v err=%v", controller, err)
	}
	saved, err := deps.store.LoadSubscription()
	if err != nil || len(saved.Nodes) != 0 {
		t.Fatalf("external activation changed subscription: %+v err=%v", saved, err)
	}
}

func TestPrepareIsVersionedBusyAndIdempotent(t *testing.T) {
	deps := newEngineDeps(t)
	saved, err := deps.store.SaveSubscription([]model.Node{testNode()}, time.Unix(90, 0))
	if err != nil {
		t.Fatal(err)
	}
	engine := New(deps.cfg, deps.fetcher, subscription.Parse, deps.store, deps.system, deps.clock)
	if _, _, err := engine.PrepareSelect("33333333-3333-4333-8333-333333333333", saved.Nodes[0].ID, deps.stateVersion-1); !errors.Is(err, ErrStaleState) {
		t.Fatalf("stale err=%v", err)
	}
	first, job, err := engine.PrepareRefresh("44444444-4444-4444-8444-444444444444", deps.stateVersion)
	if err != nil || job == nil {
		t.Fatalf("first=%+v job=%v err=%v", first, job, err)
	}
	duplicate, duplicateJob, err := engine.PrepareRefresh(first.IdempotencyKey, deps.stateVersion)
	if err != nil || duplicateJob != nil || duplicate.IdempotencyKey != first.IdempotencyKey {
		t.Fatalf("duplicate=%+v job=%v err=%v", duplicate, duplicateJob, err)
	}
	if _, _, err := engine.PrepareRefresh("55555555-5555-4555-8555-555555555555", deps.stateVersion); !errors.Is(err, ErrBusy) {
		t.Fatalf("busy err=%v", err)
	}
	job(context.Background())
}

func TestSelectRollsBackEveryPostMutationFailure(t *testing.T) {
	for _, failAt := range []string{"write-excludes", "validate", "restart", "verify"} {
		t.Run(failAt, func(t *testing.T) {
			deps := newEngineDeps(t)
			saved, err := deps.store.SaveSubscription([]model.Node{testNode()}, time.Unix(90, 0))
			if err != nil {
				t.Fatal(err)
			}
			originalOutbounds := append([]byte(nil), deps.system.outbounds...)
			originalExcludes := append([]byte(nil), deps.system.excludes...)
			deps.system.failAt = failAt
			engine := New(deps.cfg, deps.fetcher, subscription.Parse, deps.store, deps.system, deps.clock)
			operation, job, err := engine.PrepareSelect("66666666-6666-4666-8666-666666666666", saved.Nodes[0].ID, deps.stateVersion)
			if err != nil || job == nil {
				t.Fatalf("operation=%+v job=%v err=%v", operation, job, err)
			}
			job(context.Background())
			op, found, err := deps.store.FindOperation(operation.IdempotencyKey)
			if err != nil || !found || op.Result != model.ResultFailedRolledBack {
				t.Fatalf("op=%+v found=%v err=%v events=%v", op, found, err, deps.system.events)
			}
			if string(deps.system.outbounds) != string(originalOutbounds) || string(deps.system.excludes) != string(originalExcludes) {
				t.Fatalf("old files not restored: outbounds=%s excludes=%s", deps.system.outbounds, deps.system.excludes)
			}
			controller, err := deps.store.LoadControllerState()
			if err != nil || controller.Active == nil || controller.Active.ID != deps.originalActive.ID || controller.StateVersion != deps.stateVersion {
				t.Fatalf("controller=%+v err=%v", controller, err)
			}
		})
	}
}

func TestRollbackVerificationFailureReturnsUncertain(t *testing.T) {
	deps := newEngineDeps(t)
	saved, err := deps.store.SaveSubscription([]model.Node{testNode()}, time.Unix(90, 0))
	if err != nil {
		t.Fatal(err)
	}
	deps.system.failAt = "verify"
	deps.system.failRollbackAt = "verify"
	engine := New(deps.cfg, deps.fetcher, subscription.Parse, deps.store, deps.system, deps.clock)
	operation, job, err := engine.PrepareSelect("77777777-7777-4777-8777-777777777777", saved.Nodes[0].ID, deps.stateVersion)
	if err != nil || job == nil {
		t.Fatalf("operation=%+v job=%v err=%v", operation, job, err)
	}
	job(context.Background())
	op, found, err := deps.store.FindOperation(operation.IdempotencyKey)
	if err != nil || !found || op.Result != model.ResultUncertain {
		t.Fatalf("op=%+v found=%v err=%v events=%v", op, found, err, deps.system.events)
	}
}

func TestFailureBeforeMutationReturnsFailedNoChange(t *testing.T) {
	deps := newEngineDeps(t)
	saved, err := deps.store.SaveSubscription([]model.Node{testNode()}, time.Unix(90, 0))
	if err != nil {
		t.Fatal(err)
	}
	deps.system.failAt = "resolve"
	engine := New(deps.cfg, deps.fetcher, subscription.Parse, deps.store, deps.system, deps.clock)
	operation, job, err := engine.PrepareSelect("88888888-8888-4888-8888-888888888888", saved.Nodes[0].ID, deps.stateVersion)
	if err != nil || job == nil {
		t.Fatalf("operation=%+v job=%v err=%v", operation, job, err)
	}
	job(context.Background())
	op, _, err := deps.store.FindOperation(operation.IdempotencyKey)
	if err != nil || op.Result != model.ResultFailedNoChange {
		t.Fatalf("op=%+v err=%v", op, err)
	}
}

func TestRecoverRollsBackPersistedRunningSelect(t *testing.T) {
	deps := newEngineDeps(t)
	saved, err := deps.store.SaveSubscription([]model.Node{testNode()}, time.Unix(90, 0))
	if err != nil {
		t.Fatal(err)
	}
	engine := New(deps.cfg, deps.fetcher, subscription.Parse, deps.store, deps.system, deps.clock)
	operation, _, err := engine.PrepareSelect("99999999-9999-4999-8999-999999999999", saved.Nodes[0].ID, deps.stateVersion)
	if err != nil {
		t.Fatal(err)
	}
	originalOutbounds := append([]byte(nil), deps.system.outbounds...)
	originalExcludes := append([]byte(nil), deps.system.excludes...)
	operation.State = model.OperationRunning
	snapshot := &model.TransactionSnapshot{
		OperationKey: operation.IdempotencyKey, Kind: "select", Phase: "restart",
		OriginalOutbounds: originalOutbounds, OriginalExcludes: originalExcludes,
		OriginalActive: cloneActive(deps.originalActive), OriginalIP: deps.originalActive.ResolvedIP,
		CandidateIP: "203.0.113.44",
	}
	if err := deps.store.UpdateOperation(operation, snapshot); err != nil {
		t.Fatal(err)
	}
	deps.system.outbounds = []byte("mutated-outbounds")
	deps.system.excludes = []byte("mutated-excludes")
	restarted := New(deps.cfg, deps.fetcher, subscription.Parse, deps.store, deps.system, deps.clock)
	if err := restarted.Recover(context.Background()); err != nil {
		t.Fatal(err)
	}
	op, found, err := deps.store.FindOperation(operation.IdempotencyKey)
	if err != nil || !found || op.Result != model.ResultFailedRolledBack || string(deps.system.outbounds) != string(originalOutbounds) || string(deps.system.excludes) != string(originalExcludes) {
		t.Fatalf("op=%+v found=%v err=%v outbounds=%s excludes=%s", op, found, err, deps.system.outbounds, deps.system.excludes)
	}
}

type engineDeps struct {
	cfg            config.Config
	store          *state.Store
	system         *fakeSystem
	fetcher        *fakeFetcher
	clock          func() time.Time
	stateVersion   uint64
	originalActive *model.ActiveNode
}

func newEngineDeps(t *testing.T) engineDeps {
	t.Helper()
	dir := t.TempDir()
	cfg := config.Config{
		SubscriptionURL:     "https://vpn.example.test/sub/private",
		SubscriptionCache:   filepath.Join(dir, "subscription.json"),
		StatePath:           filepath.Join(dir, "state.json"),
		BackupDir:           filepath.Join(dir, "backups"),
		OutboundsPath:       filepath.Join(dir, "04_outbounds.json"),
		ExcludePath:         filepath.Join(dir, "ip_exclude.lst"),
		InitScript:          "/opt/etc/init.d/S05xkeen",
		XrayBinary:          "/opt/sbin/xray",
		AssetDir:            "/opt/etc/xray/dat",
		MaxSubscriptionSize: 262144,
		MaxNodes:            128,
	}
	store := state.New(state.Paths{Subscription: cfg.SubscriptionCache, State: cfg.StatePath, BackupDir: cfg.BackupDir}, strings.NewReader(strings.Repeat("ab", 512)))
	active := &model.ActiveNode{
		PublicNode: model.PublicNode{ID: "original-node-id", DisplayName: "Нидерланды 1", Host: "old.example.test", Port: 443, Fingerprint: "firefox", Transport: "tcp", Security: "reality", Flow: "xtls-rprx-vision", Active: true, Warnings: []string{}},
		ResolvedIP: "203.0.113.10", ConfirmedAt: 50,
	}
	const version = uint64(7)
	if err := store.SaveControllerState(model.ControllerState{StateVersion: version, Active: active}); err != nil {
		t.Fatal(err)
	}
	system := &fakeSystem{
		outboundsPath: cfg.OutboundsPath,
		excludesPath:  cfg.ExcludePath,
		outbounds:     []byte(testOutbounds),
		excludes:      []byte("# keep\n# BEGIN KEENWG XKeen ENDPOINT\n203.0.113.10/32\n# END KEENWG XKeen ENDPOINT\n"),
		resolved:      []netip.Addr{netip.MustParseAddr("203.0.113.44")},
	}
	return engineDeps{
		cfg: cfg, store: store, system: system,
		fetcher:      &fakeFetcher{payload: []byte(testSubscriptionURI)},
		clock:        func() time.Time { return time.Unix(100, 0) },
		stateVersion: version, originalActive: active,
	}
}

const testSubscriptionURI = "vless://aaaaaaaa-aaaa-2aaa-eaaa-aaaaaaaaaaaa@nl1.example.test:443?type=tcp&encryption=none&security=reality&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&fp=firefox&sni=intel.example.test&sid=0123456789abcdef&spx=%2F&flow=xtls-rprx-vision#%F0%9F%87%B3%F0%9F%87%B1%20%D0%9D%D0%B8%D0%B4%D0%B5%D1%80%D0%BB%D0%B0%D0%BD%D0%B4%D1%8B%201"

const testOutbounds = `{
  "outbounds": [
    {"tag":"vless-reality","protocol":"vless","settings":{"vnext":[{"address":"203.0.113.10","port":443,"users":[{"id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","encryption":"none","flow":"xtls-rprx-vision","level":0}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"publicKey":"OLD_KEY","fingerprint":"firefox","serverName":"old.example.test","shortId":"0011223344556677","spiderX":"/"}}},
    {"protocol":"freedom","tag":"direct"}
  ]
}`

func testNode() model.Node {
	result, err := subscription.Parse([]byte(testSubscriptionURI), 1)
	if err != nil {
		panic(err)
	}
	return result.Nodes[0]
}

type fakeFetcher struct {
	payload []byte
	err     error
	calls   int
}

func (f *fakeFetcher) Fetch(context.Context, string, int64) ([]byte, error) {
	f.calls++
	return append([]byte(nil), f.payload...), f.err
}

type fakeSystem struct {
	outboundsPath  string
	excludesPath   string
	outbounds      []byte
	excludes       []byte
	resolved       []netip.Addr
	events         []string
	failAt         string
	failRollbackAt string
	counts         map[string]int
}

func (f *fakeSystem) ResolveIPv4(context.Context, string) ([]netip.Addr, error) {
	f.events = append(f.events, "resolve")
	if f.shouldFail("resolve") {
		return nil, errors.New("injected")
	}
	return append([]netip.Addr(nil), f.resolved...), nil
}

func (f *fakeSystem) ReadFile(path string) ([]byte, error) {
	switch path {
	case f.outboundsPath:
		f.events = append(f.events, "read-outbounds")
		return append([]byte(nil), f.outbounds...), nil
	case f.excludesPath:
		f.events = append(f.events, "read-excludes")
		return append([]byte(nil), f.excludes...), nil
	default:
		return nil, errors.New("unexpected path")
	}
}

func (f *fakeSystem) WriteAtomic(path string, body []byte, _ fs.FileMode) error {
	switch path {
	case f.outboundsPath:
		f.events = append(f.events, "write-outbounds")
		if f.shouldFail("write-outbounds") {
			return errors.New("injected")
		}
		f.outbounds = append([]byte(nil), body...)
	case f.excludesPath:
		f.events = append(f.events, "write-excludes")
		if f.shouldFail("write-excludes") {
			return errors.New("injected")
		}
		f.excludes = append([]byte(nil), body...)
	default:
		return errors.New("unexpected path")
	}
	return nil
}

func (f *fakeSystem) Validate(context.Context) error {
	f.events = append(f.events, "validate")
	if f.shouldFail("validate") {
		return errors.New("injected")
	}
	return nil
}

func (f *fakeSystem) Restart(context.Context) error {
	f.events = append(f.events, "restart")
	if f.shouldFail("restart") {
		return errors.New("injected")
	}
	return nil
}

func (f *fakeSystem) Verify(context.Context, netip.Addr) error {
	f.events = append(f.events, "verify")
	if f.shouldFail("verify") {
		return errors.New("injected")
	}
	return nil
}

func (f *fakeSystem) shouldFail(event string) bool {
	if f.counts == nil {
		f.counts = make(map[string]int)
	}
	f.counts[event]++
	if f.failAt == event && f.counts[event] == 1 {
		return true
	}
	return f.failRollbackAt == event && f.counts[event] > 1
}
