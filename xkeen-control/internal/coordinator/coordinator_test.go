package coordinator

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

func TestExecuteStagesAllModulesBeforeApplyingAndCommits(t *testing.T) {
	trace := []string{}
	a := newFakeModule("a", 7, &trace)
	b := newFakeModule("b", 9, &trace)
	store := &memoryRecoveryStore{}
	engine, err := New([]Module{b, a}, store)
	if err != nil {
		t.Fatal(err)
	}
	result := engine.Execute(context.Background(), Plan{ID: "plan-0001", Steps: []Step{
		{Module: "b", ReviewedVersion: 9, Payload: []byte("next-b")},
		{Module: "a", ReviewedVersion: 7, Payload: []byte("next-a")},
	}})
	if result.Status != StatusCommitted {
		t.Fatalf("result=%+v", result)
	}
	want := []string{"a:version", "a:capture", "a:stage", "a:validate", "b:version", "b:capture", "b:stage", "b:validate", "a:apply", "a:verify", "b:apply", "b:verify"}
	if !reflect.DeepEqual(trace, want) {
		t.Fatalf("trace=%v", trace)
	}
	if store.record != nil {
		t.Fatal("recovery record was not deleted")
	}
}

func TestExecuteRejectsStaleBeforeCaptureOrApply(t *testing.T) {
	trace := []string{}
	a := newFakeModule("a", 8, &trace)
	engine, _ := New([]Module{a}, &memoryRecoveryStore{})
	result := engine.Execute(context.Background(), Plan{ID: "plan-0002", Steps: []Step{{Module: "a", ReviewedVersion: 7, Payload: []byte("next")}}})
	if result.Status != StatusRejected || result.ErrorCode != "stale_state" {
		t.Fatalf("result=%+v", result)
	}
	if !reflect.DeepEqual(trace, []string{"a:version"}) {
		t.Fatalf("trace=%v", trace)
	}
}

func TestExecuteRollsBackInReverseWhenApplyFails(t *testing.T) {
	trace := []string{}
	a := newFakeModule("a", 1, &trace)
	b := newFakeModule("b", 1, &trace)
	b.fail["apply"] = errors.New("apply failed")
	store := &memoryRecoveryStore{}
	engine, _ := New([]Module{a, b}, store)
	result := engine.Execute(context.Background(), Plan{ID: "plan-0003", Steps: []Step{
		{Module: "a", ReviewedVersion: 1, Payload: []byte("new-a")},
		{Module: "b", ReviewedVersion: 1, Payload: []byte("new-b")},
	}})
	if result.Status != StatusRolledBack {
		t.Fatalf("result=%+v trace=%v", result, trace)
	}
	wantTail := []string{"b:restore", "b:verify_restore", "a:restore", "a:verify_restore"}
	if !reflect.DeepEqual(trace[len(trace)-4:], wantTail) {
		t.Fatalf("trace=%v", trace)
	}
	if string(a.current) != "old-a" || string(b.current) != "old-b" || store.record != nil {
		t.Fatalf("state a=%s b=%s record=%+v", a.current, b.current, store.record)
	}
}

func TestUncertainBlocksMutationsUntilRestartRecovery(t *testing.T) {
	trace := []string{}
	a := newFakeModule("a", 1, &trace)
	a.fail["verify"] = errors.New("verify failed")
	a.fail["verify_restore"] = errors.New("restore verify failed")
	store := &memoryRecoveryStore{}
	engine, _ := New([]Module{a}, store)
	plan := Plan{ID: "plan-0004", Steps: []Step{{Module: "a", ReviewedVersion: 1, Payload: []byte("new")}}}
	if result := engine.Execute(context.Background(), plan); result.Status != StatusUncertain {
		t.Fatalf("result=%+v", result)
	}
	if result := engine.Execute(context.Background(), plan); result.Status != StatusRejected || result.ErrorCode != "recovery_required" {
		t.Fatalf("blocked=%+v", result)
	}
	delete(a.fail, "verify_restore")
	restarted, _ := New([]Module{a}, store)
	status, err := restarted.RecoveryStatus(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if !status.Pending || status.PlanID != "plan-0004" || !reflect.DeepEqual(status.Modules, []string{"a"}) {
		t.Fatalf("status=%+v", status)
	}
	if result := restarted.RecoverReviewed(context.Background(), "wrong-plan"); result.Status != StatusRejected || result.ErrorCode != "recovery_changed" {
		t.Fatalf("wrong=%+v", result)
	}
	if result := restarted.RecoverReviewed(context.Background(), "plan-0004"); result.Status != StatusRolledBack {
		t.Fatalf("recover=%+v", result)
	}
	if store.record != nil || string(a.current) != "old-a" {
		t.Fatalf("record=%+v state=%s", store.record, a.current)
	}
}

func TestRollbackRestoreFailureLeavesRecoveryPending(t *testing.T) {
	trace := []string{}
	module := newFakeModule("routes", 1, &trace)
	module.fail["apply"] = errors.New("injected apply failure")
	module.fail["restore"] = errors.New("injected restore failure")
	store := &memoryRecoveryStore{}
	engine, _ := New([]Module{module}, store)
	plan := Plan{ID: "plan-restore-0001", Steps: []Step{{Module: "routes", ReviewedVersion: 1, Payload: []byte("new")}}}
	if result := engine.Execute(context.Background(), plan); result.Status != StatusUncertain || result.ErrorCode != "rollback_failed" {
		t.Fatalf("result=%+v trace=%v", result, trace)
	}
	if store.record == nil {
		t.Fatal("recovery record deleted after failed restore")
	}
	if result := engine.Execute(context.Background(), plan); result.Status != StatusRejected || result.ErrorCode != "recovery_required" {
		t.Fatalf("blocked=%+v", result)
	}
}

func TestRecoveryPersistenceFailurePreventsAnyApply(t *testing.T) {
	trace := []string{}
	a := newFakeModule("a", 1, &trace)
	store := &memoryRecoveryStore{saveErr: errors.New("disk full")}
	engine, _ := New([]Module{a}, store)
	result := engine.Execute(context.Background(), Plan{ID: "plan-0005", Steps: []Step{{Module: "a", ReviewedVersion: 1, Payload: []byte("new")}}})
	if result.Status != StatusRejected || result.ErrorCode != "recovery_unavailable" {
		t.Fatalf("result=%+v", result)
	}
	for _, item := range trace {
		if item == "a:apply" {
			t.Fatalf("apply ran: %v", trace)
		}
	}
}

func TestExecutePreflightFaultsRejectBeforeAnyApply(t *testing.T) {
	tests := []struct {
		phase string
		code  string
	}{
		{phase: "version", code: "module_unavailable"},
		{phase: "capture", code: "capture_failed"},
		{phase: "stage", code: "stage_failed"},
		{phase: "validate", code: "validation_failed"},
	}
	for _, test := range tests {
		t.Run(test.phase, func(t *testing.T) {
			trace := []string{}
			module := newFakeModule("routes", 1, &trace)
			module.fail[test.phase] = errors.New("injected " + test.phase + " failure")
			store := &memoryRecoveryStore{}
			engine, err := New([]Module{module}, store)
			if err != nil {
				t.Fatal(err)
			}
			result := engine.Execute(context.Background(), Plan{ID: "plan-preflight-" + test.phase, Steps: []Step{{Module: "routes", ReviewedVersion: 1, Payload: []byte("new")}}})
			if result.Status != StatusRejected || result.ErrorCode != test.code {
				t.Fatalf("result=%+v trace=%v", result, trace)
			}
			for _, item := range trace {
				if item == "routes:apply" {
					t.Fatalf("apply ran after %s failure: %v", test.phase, trace)
				}
			}
			if store.record != nil {
				t.Fatalf("recovery persisted before apply: %+v", store.record)
			}
		})
	}
}

func TestRecoveryCleanupFailureStaysBlockedUntilReviewedRecovery(t *testing.T) {
	trace := []string{}
	module := newFakeModule("routes", 1, &trace)
	store := &memoryRecoveryStore{deleteErr: errors.New("injected cleanup failure")}
	engine, _ := New([]Module{module}, store)
	plan := Plan{ID: "plan-cleanup-0001", Steps: []Step{{Module: "routes", ReviewedVersion: 1, Payload: []byte("new")}}}
	if result := engine.Execute(context.Background(), plan); result.Status != StatusUncertain || result.ErrorCode != "recovery_cleanup_failed" {
		t.Fatalf("result=%+v", result)
	}
	if result := engine.Execute(context.Background(), plan); result.Status != StatusRejected || result.ErrorCode != "recovery_required" {
		t.Fatalf("blocked=%+v", result)
	}
	store.deleteErr = nil
	if result := engine.RecoverReviewed(context.Background(), plan.ID); result.Status != StatusRolledBack {
		t.Fatalf("recover=%+v", result)
	}
}

func TestFileRecoverySurvivesRestartAndRequiresExactReviewedPlan(t *testing.T) {
	trace := []string{}
	module := newFakeModule("routes", 1, &trace)
	module.fail["verify"] = errors.New("injected verify failure")
	module.fail["verify_restore"] = errors.New("injected rollback verification failure")
	path := filepath.Join(t.TempDir(), "recovery.json")
	store := NewFileRecoveryStore(path)
	plan := Plan{ID: "plan-restart-0001", Steps: []Step{{Module: "routes", ReviewedVersion: 1, Payload: []byte("new")}}}
	engine, err := New([]Module{module}, store)
	if err != nil {
		t.Fatal(err)
	}
	if result := engine.Execute(context.Background(), plan); result.Status != StatusUncertain {
		t.Fatalf("result=%+v", result)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("recovery file missing after uncertain result: %v", err)
	}
	delete(module.fail, "verify")
	delete(module.fail, "verify_restore")
	restarted, err := New([]Module{module}, NewFileRecoveryStore(path))
	if err != nil {
		t.Fatal(err)
	}
	if result := restarted.RecoverReviewed(context.Background(), "plan-restart-stale"); result.Status != StatusRejected || result.ErrorCode != "recovery_changed" {
		t.Fatalf("stale review=%+v", result)
	}
	if result := restarted.RecoverReviewed(context.Background(), plan.ID); result.Status != StatusRolledBack {
		t.Fatalf("recover=%+v", result)
	}
	if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("recovery file remains after verified rollback: %v", err)
	}
	if string(module.current) != "old-routes" {
		t.Fatalf("state=%q", module.current)
	}
}

func TestConcurrentExecuteRejectsBusyInsteadOfQueuingStalePlan(t *testing.T) {
	trace := []string{}
	base := newFakeModule("a", 1, &trace)
	module := &blockingApplyModule{fakeModule: base, entered: make(chan struct{}), release: make(chan struct{})}
	engine, _ := New([]Module{module}, &memoryRecoveryStore{})
	plan := Plan{ID: "plan-0006", Steps: []Step{{Module: "a", ReviewedVersion: 1, Payload: []byte("new")}}}
	first := make(chan Result, 1)
	go func() { first <- engine.Execute(context.Background(), plan) }()
	select {
	case <-module.entered:
	case <-time.After(time.Second):
		t.Fatal("first apply did not start")
	}
	second := make(chan Result, 1)
	go func() { second <- engine.Execute(context.Background(), Plan{ID: "plan-0007", Steps: plan.Steps}) }()
	select {
	case result := <-second:
		if result.Status != StatusRejected || result.ErrorCode != "busy" {
			t.Fatalf("second=%+v", result)
		}
	case <-time.After(100 * time.Millisecond):
		t.Fatal("second plan queued behind active mutation")
	}
	close(module.release)
	if result := <-first; result.Status != StatusCommitted {
		t.Fatalf("first=%+v", result)
	}
}

type fakeModule struct {
	id      string
	version uint64
	current []byte
	trace   *[]string
	fail    map[string]error
}

type blockingApplyModule struct {
	*fakeModule
	entered chan struct{}
	release chan struct{}
}

func (m *blockingApplyModule) Apply(ctx context.Context, staged []byte) error {
	close(m.entered)
	select {
	case <-m.release:
	case <-ctx.Done():
		return ctx.Err()
	}
	return m.fakeModule.Apply(ctx, staged)
}

func newFakeModule(id string, version uint64, trace *[]string) *fakeModule {
	return &fakeModule{id: id, version: version, current: []byte("old-" + id), trace: trace, fail: map[string]error{}}
}
func (m *fakeModule) ID() string { return m.id }
func (m *fakeModule) Version(context.Context) (uint64, error) {
	*m.trace = append(*m.trace, m.id+":version")
	return m.version, m.fail["version"]
}
func (m *fakeModule) Capture(context.Context) ([]byte, error) {
	*m.trace = append(*m.trace, m.id+":capture")
	return append([]byte(nil), m.current...), m.fail["capture"]
}
func (m *fakeModule) Stage(_ context.Context, _ []byte, payload []byte) ([]byte, error) {
	*m.trace = append(*m.trace, m.id+":stage")
	return append([]byte(nil), payload...), m.fail["stage"]
}
func (m *fakeModule) Validate(context.Context, []byte) error {
	*m.trace = append(*m.trace, m.id+":validate")
	return m.fail["validate"]
}
func (m *fakeModule) Apply(_ context.Context, staged []byte) error {
	*m.trace = append(*m.trace, m.id+":apply")
	m.current = append([]byte(nil), staged...)
	return m.fail["apply"]
}
func (m *fakeModule) Verify(context.Context, []byte) error {
	*m.trace = append(*m.trace, m.id+":verify")
	return m.fail["verify"]
}
func (m *fakeModule) Restore(_ context.Context, before []byte) error {
	*m.trace = append(*m.trace, m.id+":restore")
	m.current = append([]byte(nil), before...)
	return m.fail["restore"]
}
func (m *fakeModule) VerifyRestore(context.Context, []byte) error {
	*m.trace = append(*m.trace, m.id+":verify_restore")
	return m.fail["verify_restore"]
}

type memoryRecoveryStore struct {
	record             *RecoveryRecord
	saveErr, deleteErr error
}

func (s *memoryRecoveryStore) Save(_ context.Context, r RecoveryRecord) error {
	if s.saveErr != nil {
		return s.saveErr
	}
	copy := r
	s.record = &copy
	return nil
}
func (s *memoryRecoveryStore) Load(context.Context) (*RecoveryRecord, error) {
	if s.record == nil {
		return nil, nil
	}
	copy := *s.record
	return &copy, nil
}
func (s *memoryRecoveryStore) Delete(context.Context) error {
	if s.deleteErr != nil {
		return s.deleteErr
	}
	s.record = nil
	return nil
}
