package coordinator

import (
	"context"
	"errors"
	"regexp"
	"sort"
	"sync"
)

const (
	StatusCommitted  = "committed"
	StatusRejected   = "rejected"
	StatusRolledBack = "rolled_back"
	StatusUncertain  = "uncertain"
	maxRecoveryBytes = 1 << 20
)

var ErrInvalidCoordinator = errors.New("invalid_coordinator")

type Step struct {
	Module          string `json:"module"`
	ReviewedVersion uint64 `json:"reviewed_version"`
	Payload         []byte `json:"-"`
}

type Plan struct {
	ID    string `json:"id"`
	Steps []Step `json:"steps"`
}

type Result struct {
	Status    string `json:"status"`
	ErrorCode string `json:"error,omitempty"`
	PlanID    string `json:"plan_id,omitempty"`
}

type RecoveryEntry struct {
	Module string `json:"module"`
	Before []byte `json:"before"`
	Staged []byte `json:"staged"`
}

type RecoveryRecord struct {
	SchemaVersion int             `json:"schema_version"`
	PlanID        string          `json:"plan_id"`
	Entries       []RecoveryEntry `json:"entries"`
}

type RecoveryState struct {
	SchemaVersion int      `json:"schema_version"`
	Pending       bool     `json:"pending"`
	PlanID        string   `json:"plan_id,omitempty"`
	Modules       []string `json:"modules"`
}

type Module interface {
	ID() string
	Version(context.Context) (uint64, error)
	Capture(context.Context) ([]byte, error)
	Stage(context.Context, []byte, []byte) ([]byte, error)
	Validate(context.Context, []byte) error
	Apply(context.Context, []byte) error
	Verify(context.Context, []byte) error
	Restore(context.Context, []byte) error
	VerifyRestore(context.Context, []byte) error
}

type RecoveryStore interface {
	Save(context.Context, RecoveryRecord) error
	Load(context.Context) (*RecoveryRecord, error)
	Delete(context.Context) error
}

type Coordinator struct {
	mu      sync.Mutex
	modules map[string]Module
	store   RecoveryStore
	blocked bool
}

var safeID = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,127}$`)

func New(modules []Module, store RecoveryStore) (*Coordinator, error) {
	if len(modules) == 0 || len(modules) > 32 || store == nil {
		return nil, ErrInvalidCoordinator
	}
	engine := &Coordinator{modules: make(map[string]Module, len(modules)), store: store}
	for _, module := range modules {
		if module == nil || !safeID.MatchString(module.ID()) {
			return nil, ErrInvalidCoordinator
		}
		if _, exists := engine.modules[module.ID()]; exists {
			return nil, ErrInvalidCoordinator
		}
		engine.modules[module.ID()] = module
	}
	record, err := store.Load(context.Background())
	if err != nil {
		return nil, err
	}
	engine.blocked = record != nil
	return engine, nil
}

func (c *Coordinator) Execute(ctx context.Context, plan Plan) Result {
	if !c.mu.TryLock() {
		return Result{Status: StatusRejected, ErrorCode: "busy", PlanID: plan.ID}
	}
	defer c.mu.Unlock()
	if c.blocked {
		return Result{Status: StatusRejected, ErrorCode: "recovery_required", PlanID: plan.ID}
	}
	steps, ok := c.validSteps(plan)
	if !ok {
		return Result{Status: StatusRejected, ErrorCode: "invalid_plan", PlanID: plan.ID}
	}
	record := RecoveryRecord{SchemaVersion: 1, PlanID: plan.ID, Entries: make([]RecoveryEntry, 0, len(steps))}
	total := 0
	for _, step := range steps {
		if err := ctx.Err(); err != nil {
			return Result{Status: StatusRejected, ErrorCode: "cancelled", PlanID: plan.ID}
		}
		module := c.modules[step.Module]
		version, err := module.Version(ctx)
		if err != nil {
			return Result{Status: StatusRejected, ErrorCode: "module_unavailable", PlanID: plan.ID}
		}
		if version != step.ReviewedVersion {
			return Result{Status: StatusRejected, ErrorCode: "stale_state", PlanID: plan.ID}
		}
		before, err := module.Capture(ctx)
		if err != nil {
			return Result{Status: StatusRejected, ErrorCode: "capture_failed", PlanID: plan.ID}
		}
		staged, err := module.Stage(ctx, append([]byte(nil), before...), append([]byte(nil), step.Payload...))
		if err != nil {
			return Result{Status: StatusRejected, ErrorCode: "stage_failed", PlanID: plan.ID}
		}
		if err := module.Validate(ctx, staged); err != nil {
			return Result{Status: StatusRejected, ErrorCode: "validation_failed", PlanID: plan.ID}
		}
		total += len(before) + len(staged)
		if total > maxRecoveryBytes {
			return Result{Status: StatusRejected, ErrorCode: "recovery_too_large", PlanID: plan.ID}
		}
		record.Entries = append(record.Entries, RecoveryEntry{Module: step.Module, Before: append([]byte(nil), before...), Staged: append([]byte(nil), staged...)})
	}
	if err := c.store.Save(ctx, record); err != nil {
		return Result{Status: StatusRejected, ErrorCode: "recovery_unavailable", PlanID: plan.ID}
	}
	c.blocked = true
	applied := make([]RecoveryEntry, 0, len(record.Entries))
	for _, entry := range record.Entries {
		applied = append(applied, entry)
		module := c.modules[entry.Module]
		if err := module.Apply(ctx, entry.Staged); err != nil {
			return c.rollback(ctx, plan.ID, applied)
		}
		if err := module.Verify(ctx, entry.Staged); err != nil {
			return c.rollback(ctx, plan.ID, applied)
		}
	}
	if err := c.store.Delete(ctx); err != nil {
		return Result{Status: StatusUncertain, ErrorCode: "recovery_cleanup_failed", PlanID: plan.ID}
	}
	c.blocked = false
	return Result{Status: StatusCommitted, PlanID: plan.ID}
}

func (c *Coordinator) RecoverReviewed(ctx context.Context, reviewedPlanID string) Result {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !safeID.MatchString(reviewedPlanID) {
		return Result{Status: StatusRejected, ErrorCode: "invalid_request"}
	}
	return c.recoverLocked(ctx, reviewedPlanID)
}

func (c *Coordinator) RecoveryStatus(ctx context.Context) (RecoveryState, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	record, err := c.store.Load(ctx)
	if err != nil {
		return RecoveryState{}, err
	}
	if record == nil {
		return RecoveryState{SchemaVersion: 1, Pending: false, Modules: []string{}}, nil
	}
	if !validRecord(*record, c.modules) {
		return RecoveryState{}, ErrInvalidRecovery
	}
	modules := make([]string, len(record.Entries))
	for index, entry := range record.Entries {
		modules[index] = entry.Module
	}
	return RecoveryState{SchemaVersion: 1, Pending: true, PlanID: record.PlanID, Modules: modules}, nil
}

func (c *Coordinator) recoverLocked(ctx context.Context, reviewedPlanID string) Result {
	record, err := c.store.Load(ctx)
	if err != nil {
		c.blocked = true
		return Result{Status: StatusUncertain, ErrorCode: "recovery_unavailable"}
	}
	if record == nil {
		c.blocked = false
		return Result{Status: StatusRejected, ErrorCode: "recovery_not_found", PlanID: reviewedPlanID}
	}
	if record.PlanID != reviewedPlanID {
		return Result{Status: StatusRejected, ErrorCode: "recovery_changed", PlanID: reviewedPlanID}
	}
	c.blocked = true
	if !validRecord(*record, c.modules) {
		return Result{Status: StatusUncertain, ErrorCode: "recovery_invalid", PlanID: record.PlanID}
	}
	if !c.restore(ctx, record.Entries) {
		return Result{Status: StatusUncertain, ErrorCode: "rollback_failed", PlanID: record.PlanID}
	}
	if err := c.store.Delete(ctx); err != nil {
		return Result{Status: StatusUncertain, ErrorCode: "recovery_cleanup_failed", PlanID: record.PlanID}
	}
	c.blocked = false
	return Result{Status: StatusRolledBack, PlanID: record.PlanID}
}

func (c *Coordinator) rollback(ctx context.Context, planID string, entries []RecoveryEntry) Result {
	if !c.restore(ctx, entries) {
		return Result{Status: StatusUncertain, ErrorCode: "rollback_failed", PlanID: planID}
	}
	if err := c.store.Delete(ctx); err != nil {
		return Result{Status: StatusUncertain, ErrorCode: "recovery_cleanup_failed", PlanID: planID}
	}
	c.blocked = false
	return Result{Status: StatusRolledBack, PlanID: planID}
}

func (c *Coordinator) restore(ctx context.Context, entries []RecoveryEntry) bool {
	ok := true
	for index := len(entries) - 1; index >= 0; index-- {
		entry := entries[index]
		module := c.modules[entry.Module]
		if module == nil || module.Restore(ctx, entry.Before) != nil {
			ok = false
			continue
		}
		if module.VerifyRestore(ctx, entry.Before) != nil {
			ok = false
		}
	}
	return ok
}

func (c *Coordinator) validSteps(plan Plan) ([]Step, bool) {
	if !safeID.MatchString(plan.ID) || len(plan.Steps) == 0 || len(plan.Steps) > len(c.modules) {
		return nil, false
	}
	steps := append([]Step(nil), plan.Steps...)
	sort.Slice(steps, func(i, j int) bool { return steps[i].Module < steps[j].Module })
	for index, step := range steps {
		if c.modules[step.Module] == nil || step.ReviewedVersion == 0 || len(step.Payload) == 0 || len(step.Payload) > maxRecoveryBytes {
			return nil, false
		}
		if index > 0 && steps[index-1].Module == step.Module {
			return nil, false
		}
	}
	return steps, true
}

func validRecord(record RecoveryRecord, modules map[string]Module) bool {
	if record.SchemaVersion != 1 || !safeID.MatchString(record.PlanID) || len(record.Entries) == 0 || len(record.Entries) > len(modules) {
		return false
	}
	total := 0
	seen := map[string]struct{}{}
	for _, entry := range record.Entries {
		if modules[entry.Module] == nil || len(entry.Before) == 0 || len(entry.Staged) == 0 {
			return false
		}
		if _, ok := seen[entry.Module]; ok {
			return false
		}
		seen[entry.Module] = struct{}{}
		total += len(entry.Before) + len(entry.Staged)
	}
	return total <= maxRecoveryBytes
}
