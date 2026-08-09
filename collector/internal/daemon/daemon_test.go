package daemon

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/goldb/keenwg/collector/internal/history"
	"github.com/goldb/keenwg/collector/internal/model"
	collectorsource "github.com/goldb/keenwg/collector/internal/source"
)

type fakeClock struct {
	mu    sync.Mutex
	now   time.Time
	waits []fakeWait
}
type fakeWait struct {
	d  time.Duration
	ch chan time.Time
}

func newFakeClock(now time.Time) *fakeClock { return &fakeClock{now: now} }
func (f *fakeClock) Now() time.Time         { f.mu.Lock(); defer f.mu.Unlock(); return f.now }
func (f *fakeClock) After(d time.Duration) <-chan time.Time {
	f.mu.Lock()
	defer f.mu.Unlock()
	ch := make(chan time.Time, 1)
	f.waits = append(f.waits, fakeWait{d, ch})
	return ch
}
func (f *fakeClock) Advance(d time.Duration) {
	f.mu.Lock()
	f.now = f.now.Add(d)
	if len(f.waits) == 0 {
		f.mu.Unlock()
		return
	}
	w := f.waits[0]
	f.waits = f.waits[1:]
	now := f.now
	f.mu.Unlock()
	w.ch <- now
}
func (f *fakeClock) LastWait() (time.Duration, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.waits) == 0 {
		return 0, false
	}
	return f.waits[len(f.waits)-1].d, true
}

type sourceResult struct {
	peers []model.RuntimePeer
	err   error
}
type fakeSource struct {
	mu      sync.Mutex
	results []sourceResult
	calls   int
}

func (f *fakeSource) Run(context.Context, string) ([]model.RuntimePeer, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	if len(f.results) == 0 {
		return nil, errors.New("no result")
	}
	r := f.results[0]
	if len(f.results) > 1 {
		f.results = f.results[1:]
	}
	return r.peers, r.err
}
func (f *fakeSource) Calls() int { f.mu.Lock(); defer f.mu.Unlock(); return f.calls }

type fakeStore struct {
	mu                                         sync.Mutex
	appended, appendAttempts, maintainAttempts int
	appendErrors, maintainErrors               []error
	samples                                    []history.ReducedSample
	flushErr, closeErr                         error
	closeBlock                                 <-chan struct{}
	rejectExpiredContexts                      bool
	storageStatus                              history.StorageStatus
	degradeAfterAppend                         bool
	rejectWhenDegraded                         bool
	recoverStorageOnMaintainAt                 int
	lifecycle                                  *lifecycleLog
	flushed, closed                            bool
}

func (f *fakeStore) Append(ctx context.Context, rows []history.ReducedSample) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.recordLocked("append")
	f.appendAttempts++
	if f.rejectExpiredContexts {
		select {
		case <-ctx.Done():
			return fmt.Errorf("append received expired context: %w", ctx.Err())
		default:
		}
	}
	if f.rejectWhenDegraded && f.storageStatus == history.StorageDegraded {
		return history.ErrStorageDegraded
	}
	if len(f.appendErrors) > 0 {
		err := f.appendErrors[0]
		f.appendErrors = f.appendErrors[1:]
		if err != nil {
			return err
		}
	}
	f.appended += len(rows)
	f.samples = append(f.samples, rows...)
	if f.degradeAfterAppend {
		f.storageStatus = history.StorageDegraded
	}
	return nil
}
func (f *fakeStore) Maintain(context.Context, time.Time) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.maintainAttempts++
	if len(f.maintainErrors) > 0 {
		err := f.maintainErrors[0]
		f.maintainErrors = f.maintainErrors[1:]
		return err
	}
	if f.recoverStorageOnMaintainAt > 0 && f.maintainAttempts >= f.recoverStorageOnMaintainAt {
		f.storageStatus = history.StorageOK
		f.degradeAfterAppend = false
	}
	return nil
}
func (f *fakeStore) Flush(ctx context.Context) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.recordLocked("flush")
	f.flushed = true
	if f.rejectExpiredContexts {
		select {
		case <-ctx.Done():
			return fmt.Errorf("flush received expired context: %w", ctx.Err())
		default:
		}
	}
	return f.flushErr
}
func (f *fakeStore) Close() error {
	f.mu.Lock()
	f.recordLocked("close")
	f.closed = true
	block := f.closeBlock
	err := f.closeErr
	f.mu.Unlock()
	if block != nil {
		<-block
	}
	return err
}
func (f *fakeStore) Appended() int       { f.mu.Lock(); defer f.mu.Unlock(); return f.appended }
func (f *fakeStore) AppendAttempts() int { f.mu.Lock(); defer f.mu.Unlock(); return f.appendAttempts }
func (f *fakeStore) MaintainAttempts() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.maintainAttempts
}
func (f *fakeStore) Samples() []history.ReducedSample {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]history.ReducedSample(nil), f.samples...)
}
func (f *fakeStore) StorageState() history.StorageStatus {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.storageStatus == history.StorageDegraded {
		return history.StorageDegraded
	}
	return history.StorageOK
}
func (f *fakeStore) recordLocked(event string) {
	if f.lifecycle != nil {
		f.lifecycle.add(event)
	}
}

type lifecycleLog struct {
	mu     sync.Mutex
	events []string
}

func (l *lifecycleLog) add(event string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.events = append(l.events, event)
}

func (l *lifecycleLog) Events() []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return append([]string(nil), l.events...)
}

type fakeListener struct {
	mu                     sync.Mutex
	shutdown               bool
	forceClosed            bool
	done                   chan struct{}
	shutdownErr            error
	forceCloseErr          error
	shutdownBlock          <-chan struct{}
	forceCloseBlock        <-chan struct{}
	lifecycle              *lifecycleLog
	waitForContextDeadline bool
}

func newFakeListener() *fakeListener { return &fakeListener{done: make(chan struct{})} }
func (f *fakeListener) Serve() error { <-f.done; return nil }
func (f *fakeListener) Shutdown(ctx context.Context) error {
	f.mu.Lock()
	if f.lifecycle != nil {
		f.lifecycle.add("listener_shutdown")
	}
	if !f.shutdown {
		f.shutdown = true
		close(f.done)
	}
	waitForContextDeadline := f.waitForContextDeadline
	shutdownErr := f.shutdownErr
	shutdownBlock := f.shutdownBlock
	f.mu.Unlock()
	if waitForContextDeadline {
		<-ctx.Done()
		if shutdownErr == nil {
			shutdownErr = ctx.Err()
		}
	}
	if shutdownBlock != nil {
		<-shutdownBlock
	}
	return shutdownErr
}
func (f *fakeListener) ShutdownCalled() bool { f.mu.Lock(); defer f.mu.Unlock(); return f.shutdown }
func (f *fakeListener) Close() error {
	f.mu.Lock()
	if f.lifecycle != nil {
		f.lifecycle.add("listener_close")
	}
	if !f.shutdown {
		f.shutdown = true
		close(f.done)
	}
	f.forceClosed = true
	block := f.forceCloseBlock
	err := f.forceCloseErr
	f.mu.Unlock()
	if block != nil {
		<-block
	}
	return err
}
func (f *fakeListener) CloseCalled() bool { f.mu.Lock(); defer f.mu.Unlock(); return f.forceClosed }

func onePeer() []model.RuntimePeer {
	return []model.RuntimePeer{{PeerID: strings.Repeat("a", 64), InterfaceID: "Wireguard0", Online: true, RouterRXBytes: 1, RouterTXBytes: 2}}
}

func manyPeers(count int) []model.RuntimePeer {
	peers := make([]model.RuntimePeer, count)
	for i := range peers {
		peers[i] = model.RuntimePeer{
			PeerID:        fmt.Sprintf("%064x", i+1),
			InterfaceID:   "Wireguard0",
			Online:        true,
			RouterRXBytes: uint64(i+1) * 100,
			RouterTXBytes: uint64(i+1) * 200,
		}
	}
	return peers
}

func eventually(t *testing.T, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("condition not met")
}

func runForTest(t *testing.T, clock *fakeClock, source *fakeSource, store *fakeStore, state *State, maxRows int) (context.CancelFunc, <-chan error, *fakeListener) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	listener := newFakeListener()
	go func() {
		done <- Run(ctx, Config{InterfaceID: "Wireguard0", Clock: clock, Jitter: func() time.Duration { return 0 }, State: state, MaxBufferedRows: maxRows}, source, store, listener)
	}()
	return cancel, done, listener
}

func TestRunPollsImmediatelyThenOnTicker(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	source := &fakeSource{results: []sourceResult{{peers: onePeer()}, {peers: onePeer()}}}
	store := &fakeStore{}
	cancel, done, _ := runForTest(t, clock, source, store, NewState("test"), 1)
	eventually(t, func() bool { return source.Calls() == 1 })
	eventually(t, func() bool { d, ok := clock.LastWait(); return ok && d == 60*time.Second })
	clock.Advance(60 * time.Second)
	eventually(t, func() bool { return source.Calls() == 2 })
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestSourceErrorKeepsSnapshotStaleAndWritesNoOfflineRow(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	source := &fakeSource{results: []sourceResult{{peers: onePeer()}, {err: errors.New("ndmq down")}}}
	store := &fakeStore{}
	state := NewState("test")
	cancel, done, _ := runForTest(t, clock, source, store, state, 1)
	eventually(t, func() bool { return store.Appended() == 1 })
	before := store.Appended()
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	clock.Advance(60 * time.Second)
	eventually(t, func() bool { return state.Health().Stale })
	if store.Appended() != before {
		t.Fatalf("appended = %d after source error, want %d", store.Appended(), before)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestCancellationFlushesAndStopsHTTP(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	store := &fakeStore{}
	source := &fakeSource{results: []sourceResult{{peers: onePeer()}}}
	cancel, done, listener := runForTest(t, clock, source, store, NewState("test"), 500)
	eventually(t, func() bool { return source.Calls() == 1 })
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if store.Appended() != 1 || !store.flushed || !store.closed || !listener.ShutdownCalled() {
		t.Fatalf("shutdown state appended=%d store=%+v listener=%+v", store.Appended(), store, listener)
	}
}

func TestFailureBackoffCapsAndResetsAfterSuccess(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	results := []sourceResult{{err: errors.New("1")}, {err: errors.New("2")}, {err: errors.New("3")}, {err: errors.New("4")}, {err: errors.New("5")}, {peers: onePeer()}, {peers: onePeer()}}
	source := &fakeSource{results: results}
	cancel, done, _ := runForTest(t, clock, source, &fakeStore{}, NewState("test"), 1)
	for i, want := range []time.Duration{60, 120, 240, 300, 300, 60} {
		eventually(t, func() bool { got, ok := clock.LastWait(); return ok && got == want*time.Second })
		clock.Advance(want * time.Second)
		eventually(t, func() bool { return source.Calls() >= i+2 })
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestLoadConfigRejectsUnknownWildcardShortTokenAndRelativeDB(t *testing.T) {
	token := base64.StdEncoding.EncodeToString(make([]byte, 32))
	valid := `{"interface_id":"Wireguard0","listen_address":"10.8.0.1:18777","token":"` + token + `","database_path":"/opt/var/lib/keenwg/history.db"}`
	if _, err := DecodeConfig(strings.NewReader(valid)); err != nil {
		t.Fatal(err)
	}
	for name, document := range map[string]string{
		"unknown":            strings.TrimSuffix(valid, "}") + `,"extra":true}`,
		"wildcard":           strings.Replace(valid, "10.8.0.1:18777", "0.0.0.0:18777", 1),
		"short token":        strings.Replace(valid, token, base64.StdEncoding.EncodeToString(make([]byte, 31)), 1),
		"relative db":        strings.Replace(valid, "/opt/var/lib/keenwg/history.db", "history.db", 1),
		"broadcast":          strings.Replace(valid, "10.8.0.1:18777", "255.255.255.255:18777", 1),
		"IPv4-mapped IPv6":   strings.Replace(valid, "10.8.0.1:18777", "[::ffff:10.8.0.1]:18777", 1),
		"overflow retention": strings.TrimSuffix(valid, "}") + `,"hourly_retention_days":200000}`,
		"oversize database":  strings.TrimSuffix(valid, "}") + `,"max_database_bytes":1073741825}`,
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := DecodeConfig(strings.NewReader(document)); err == nil {
				t.Fatal("DecodeConfig succeeded")
			}
		})
	}
	maximumDatabase := strings.TrimSuffix(valid, "}") + `,"max_database_bytes":1073741824}`
	if _, err := DecodeConfig(strings.NewReader(maximumDatabase)); err != nil {
		t.Fatalf("maximum supported database cap rejected: %v", err)
	}
}

func TestTransientAppendFailureRetainsBoundedRowsForRetry(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	source := &fakeSource{results: []sourceResult{{peers: onePeer()}, {peers: onePeer()}, {peers: onePeer()}}}
	store := &fakeStore{appendErrors: []error{errors.New("sqlite busy"), nil, nil}}
	cancel, done, _ := runForTest(t, clock, source, store, NewState("test"), 1)
	eventually(t, func() bool { return store.AppendAttempts() >= 1 })
	if store.Appended() != 0 {
		t.Fatalf("appended after failure = %d", store.Appended())
	}
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	clock.Advance(time.Minute)
	eventually(t, func() bool { return store.Appended() == 2 })
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestTransientMaintenanceFailureRetriesSameDay(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	source := &fakeSource{results: []sourceResult{{peers: onePeer()}, {peers: onePeer()}}}
	store := &fakeStore{maintainErrors: []error{errors.New("busy"), nil}}
	cancel, done, _ := runForTest(t, clock, source, store, NewState("test"), 1)
	eventually(t, func() bool { return store.MaintainAttempts() == 1 })
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	clock.Advance(time.Minute)
	eventually(t, func() bool { return store.MaintainAttempts() >= 2 })
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestPollPersistsEveryAcceptedPeerAcrossBufferChunks(t *testing.T) {
	for _, count := range []int{501, 1024} {
		t.Run(fmt.Sprintf("%d peers", count), func(t *testing.T) {
			clock := newFakeClock(time.Unix(1_800_000_000, 0))
			source := &fakeSource{results: []sourceResult{{peers: manyPeers(count)}}}
			store := &fakeStore{}
			cancel, done, _ := runForTest(t, clock, source, store, NewState("test"), 500)
			eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
			cancel()
			if err := <-done; err != nil {
				t.Fatal(err)
			}
			if got := store.Appended(); got != count {
				t.Fatalf("persisted rows = %d, want %d", got, count)
			}
		})
	}
}

func TestFlushFailureStopsPollBeforeAdvancingUnbufferedPeer(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	first := manyPeers(501)
	last := first[500]
	last.RouterRXBytes += 50
	last.RouterTXBytes += 75
	source := &fakeSource{results: []sourceResult{{peers: first}, {peers: []model.RuntimePeer{last}}}}
	store := &fakeStore{appendErrors: []error{errors.New("sqlite busy")}}
	cancel, done, _ := runForTest(t, clock, source, store, NewState("test"), 500)
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	clock.Advance(time.Minute)
	eventually(t, func() bool { return source.Calls() == 2 })
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	for _, sample := range store.Samples() {
		if sample.PeerID != last.PeerID {
			continue
		}
		if sample.ClientUploadBytes != 0 || sample.ClientDownloadBytes != 0 {
			t.Fatalf("unbuffered peer inherited reducer state: upload=%d download=%d", sample.ClientUploadBytes, sample.ClientDownloadBytes)
		}
		return
	}
	t.Fatalf("peer %s was not persisted after retry", last.PeerID)
}

func TestStoragePauseKeepsAlreadyBufferedRowsForRecovery(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	source := &fakeSource{results: []sourceResult{{peers: onePeer()}}}
	store := &fakeStore{appendErrors: []error{history.ErrStorageDegraded, nil}}
	cancel, done, _ := runForTest(t, clock, source, store, NewState("test"), 1)
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if got := store.Appended(); got != 1 {
		t.Fatalf("persisted rows after storage recovery = %d, want 1", got)
	}
}

func TestSourceFailureAfterSuccessPublishesStaleUnavailableState(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	source := &fakeSource{results: []sourceResult{{peers: onePeer()}, {err: errors.New("ndmq unavailable")}}}
	state := NewState("test")
	cancel, done, _ := runForTest(t, clock, source, &fakeStore{}, state, 500)
	eventually(t, func() bool { return state.Health().Status == "ok" })
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	clock.Advance(time.Minute)
	eventually(t, func() bool { return source.Calls() == 2 && state.Health().Stale })
	health := state.Health()
	if health.Status != "source_unavailable" || health.Storage != "ok" {
		t.Fatalf("health after source failure = %+v", health)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestUnsupportedSourceSchemaPublishesExactStatus(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	source := &fakeSource{results: []sourceResult{{err: fmt.Errorf("parse source: %w", collectorsource.ErrUnsupportedSchema)}}}
	state := NewState("test")
	cancel, done, _ := runForTest(t, clock, source, &fakeStore{}, state, 500)
	eventually(t, func() bool { return source.Calls() == 1 })
	eventually(t, func() bool { return state.Health().Stale })
	if got := state.Health().Status; got != "source_schema_unsupported" {
		t.Fatalf("health status = %q, want source_schema_unsupported", got)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestMaintenanceFailureSurvivesSuccessfulAppendUntilMaintenanceRecovers(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	source := &fakeSource{results: []sourceResult{{peers: onePeer()}, {peers: onePeer()}}}
	store := &fakeStore{maintainErrors: []error{errors.New("maintenance busy")}}
	state := NewState("test")
	cancel, done, _ := runForTest(t, clock, source, store, state, 1)
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	if health := state.Health(); health.Status != "degraded" || health.Storage != "degraded" {
		t.Fatalf("health after maintenance failure and append success = %+v", health)
	}
	clock.Advance(time.Minute)
	eventually(t, func() bool { return store.MaintainAttempts() == 2 })
	eventually(t, func() bool { return state.Health().Status == "ok" })
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestDatabaseCapDetectedAfterAppendRemainsDegraded(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	store := &fakeStore{degradeAfterAppend: true}
	state := NewState("test")
	cancel, done, _ := runForTest(t, clock, &fakeSource{results: []sourceResult{{peers: onePeer()}}}, store, state, 1)
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	if health := state.Health(); health.Status != "degraded" || health.Storage != "degraded" {
		t.Fatalf("health after database crossed cap = %+v", health)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestMaintenanceCanRecoverDatabaseCapAfterChunkFlushPausesPoll(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	store := &fakeStore{degradeAfterAppend: true, rejectWhenDegraded: true, recoverStorageOnMaintainAt: 2}
	source := &fakeSource{results: []sourceResult{{peers: onePeer()}, {peers: manyPeers(2)}, {peers: onePeer()}}}
	state := NewState("test")
	cancel, done, _ := runForTest(t, clock, source, store, state, 1)
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	if state.Health().Storage != "degraded" {
		t.Fatalf("storage after cap crossing = %q", state.Health().Storage)
	}
	clock.Advance(24 * time.Hour)
	eventually(t, func() bool { return source.Calls() == 2 })
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	if got := store.MaintainAttempts(); got != 2 {
		t.Fatalf("maintenance attempts after paused poll = %d, want 2", got)
	}
	if health := state.Health(); health.Status != "ok" || health.Storage != "ok" {
		t.Fatalf("health after cap recovery = %+v", health)
	}
	clock.Advance(time.Minute)
	eventually(t, func() bool { return source.Calls() == 3 })
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if got := store.Appended(); got < 2 {
		t.Fatalf("persisted rows after cap recovery = %d, want at least 2", got)
	}
}

func TestCancellationReturnsFinalPendingAppendFailure(t *testing.T) {
	appendErr := errors.New("final append failed")
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	store := &fakeStore{appendErrors: []error{appendErr}}
	cancel, done, _ := runForTest(t, clock, &fakeSource{results: []sourceResult{{peers: onePeer()}}}, store, NewState("test"), 500)
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	cancel()
	if err := <-done; !errors.Is(err, appendErr) {
		t.Fatalf("Run error = %v, want final append error", err)
	}
}

func TestShutdownDrainsHTTPBeforeFlushingAndClosingStorage(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	lifecycle := &lifecycleLog{}
	store := &fakeStore{lifecycle: lifecycle}
	cancel, done, listener := runForTest(t, clock, &fakeSource{results: []sourceResult{{peers: onePeer()}}}, store, NewState("test"), 500)
	listener.lifecycle = lifecycle
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	want := []string{"listener_shutdown", "append", "flush", "close"}
	if got := lifecycle.Events(); !slicesEqual(got, want) {
		t.Fatalf("shutdown order = %v, want %v", got, want)
	}
}

func TestShutdownJoinsLifecycleErrorsAndAttemptsEveryCleanup(t *testing.T) {
	listenerErr := errors.New("listener drain failed")
	listenerCloseErr := errors.New("listener force close failed")
	appendErr := errors.New("append failed")
	flushErr := errors.New("flush failed")
	closeErr := errors.New("close failed")
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	store := &fakeStore{appendErrors: []error{appendErr}, flushErr: flushErr, closeErr: closeErr}
	cancel, done, listener := runForTest(t, clock, &fakeSource{results: []sourceResult{{peers: onePeer()}}}, store, NewState("test"), 500)
	listener.shutdownErr = listenerErr
	listener.forceCloseErr = listenerCloseErr
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	cancel()
	err := <-done
	for name, want := range map[string]error{
		"listener":       listenerErr,
		"listener close": listenerCloseErr,
		"append":         appendErr,
		"flush":          flushErr,
		"close":          closeErr,
	} {
		if !errors.Is(err, want) {
			t.Errorf("Run error = %v, want joined %s error %v", err, name, want)
		}
	}
	if !listener.ShutdownCalled() || !listener.CloseCalled() || !store.flushed || !store.closed || store.AppendAttempts() != 1 {
		t.Fatalf("cleanup incomplete: listener shutdown=%v close=%v append=%d flushed=%v closed=%v", listener.ShutdownCalled(), listener.CloseCalled(), store.AppendAttempts(), store.flushed, store.closed)
	}
}

func TestFailedGracefulShutdownForceClosesListenerBeforePersistence(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	lifecycle := &lifecycleLog{}
	store := &fakeStore{lifecycle: lifecycle}
	forceCloseRelease := make(chan struct{})
	var releaseOnce sync.Once
	releaseForceClose := func() { releaseOnce.Do(func() { close(forceCloseRelease) }) }
	defer releaseForceClose()
	cancel, done, listener := runForTest(t, clock, &fakeSource{results: []sourceResult{{peers: onePeer()}}}, store, NewState("test"), 500)
	listener.lifecycle = lifecycle
	listener.shutdownErr = errors.New("active handler did not drain")
	listener.forceCloseBlock = forceCloseRelease
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	cancel()
	eventually(t, listener.CloseCalled)
	if store.AppendAttempts() != 0 || store.flushed || store.closed {
		releaseForceClose()
		<-done
		t.Fatalf("storage touched while forced listener close was waiting: append=%d flush=%v close=%v", store.AppendAttempts(), store.flushed, store.closed)
	}
	releaseForceClose()
	if err := <-done; err == nil {
		t.Fatal("Run returned no graceful shutdown error")
	}
	want := []string{"listener_shutdown", "listener_close", "append", "flush", "close"}
	if got := lifecycle.Events(); !slicesEqual(got, want) {
		t.Fatalf("forced shutdown order = %v, want %v", got, want)
	}
}

func TestSlowListenerShutdownCannotConsumePersistenceBudget(t *testing.T) {
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	store := &fakeStore{rejectExpiredContexts: true}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	listener := newFakeListener()
	listener.waitForContextDeadline = true
	go func() {
		done <- Run(ctx, Config{
			InterfaceID:     "Wireguard0",
			Clock:           clock,
			Jitter:          func() time.Duration { return 0 },
			State:           NewState("test"),
			MaxBufferedRows: 500,
			ShutdownTimeout: 25 * time.Millisecond,
		}, &fakeSource{results: []sourceResult{{peers: onePeer()}}}, store, listener)
	}()
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	cancel()
	err := <-done
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("Run error = %v, want graceful shutdown deadline", err)
	}
	if got := store.Appended(); got != 1 {
		t.Fatalf("persisted rows after slow listener shutdown = %d, want 1", got)
	}
	if !store.flushed || !store.closed {
		t.Fatalf("persistence cleanup incomplete after slow listener shutdown: flushed=%v closed=%v", store.flushed, store.closed)
	}
	if !listener.CloseCalled() {
		t.Fatal("slow graceful shutdown did not force-close listener")
	}
}

func TestShutdownTotalDeadlineBoundsBlockedListenerAndProtectsStorage(t *testing.T) {
	shutdownTimeout := 200 * time.Millisecond
	shutdownRelease := make(chan struct{})
	forceCloseRelease := make(chan struct{})
	var releaseOnce sync.Once
	releaseListener := func() {
		releaseOnce.Do(func() {
			close(shutdownRelease)
			close(forceCloseRelease)
		})
	}
	defer releaseListener()
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	store := &fakeStore{}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	listener := newFakeListener()
	listener.shutdownBlock = shutdownRelease
	listener.forceCloseBlock = forceCloseRelease
	go func() {
		done <- Run(ctx, Config{
			InterfaceID:     "Wireguard0",
			Clock:           clock,
			Jitter:          func() time.Duration { return 0 },
			State:           NewState("test"),
			MaxBufferedRows: 500,
			ShutdownTimeout: shutdownTimeout,
		}, &fakeSource{results: []sourceResult{{peers: onePeer()}}}, store, listener)
	}()
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	started := time.Now()
	cancel()
	select {
	case err := <-done:
		releaseListener()
		if !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("Run error = %v, want total shutdown deadline", err)
		}
		if elapsed := time.Since(started); elapsed > shutdownTimeout+100*time.Millisecond {
			t.Fatalf("Run returned after %v, want one bounded shutdown window", elapsed)
		}
	case <-time.After(shutdownTimeout + 150*time.Millisecond):
		releaseListener()
		<-done
		t.Fatal("Run exceeded the single total shutdown deadline")
	}
	if !listener.CloseCalled() {
		t.Fatal("blocked graceful shutdown did not attempt force-close")
	}
	if store.AppendAttempts() != 0 || store.flushed || store.closed {
		t.Fatalf("storage touched before listener quiesced: append=%d flush=%v close=%v", store.AppendAttempts(), store.flushed, store.closed)
	}
}

func TestShutdownReturnsDeadlineErrorWhenStorageCloseBlocks(t *testing.T) {
	closeRelease := make(chan struct{})
	defer close(closeRelease)
	clock := newFakeClock(time.Unix(1_800_000_000, 0))
	store := &fakeStore{closeBlock: closeRelease}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	listener := newFakeListener()
	go func() {
		done <- Run(ctx, Config{
			InterfaceID:     "Wireguard0",
			Clock:           clock,
			Jitter:          func() time.Duration { return 0 },
			State:           NewState("test"),
			MaxBufferedRows: 500,
			ShutdownTimeout: 25 * time.Millisecond,
		}, &fakeSource{results: []sourceResult{{peers: onePeer()}}}, store, listener)
	}()
	eventually(t, func() bool { _, ok := clock.LastWait(); return ok })
	started := time.Now()
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("Run error = %v, want shutdown deadline", err)
		}
		if elapsed := time.Since(started); elapsed > 500*time.Millisecond {
			t.Fatalf("Run returned after %v, want bounded shutdown", elapsed)
		}
	case <-time.After(time.Second):
		t.Fatal("Run remained blocked in Store.Close")
	}
}

func slicesEqual(got, want []string) bool {
	if len(got) != len(want) {
		return false
	}
	for i := range got {
		if got[i] != want[i] {
			return false
		}
	}
	return true
}
