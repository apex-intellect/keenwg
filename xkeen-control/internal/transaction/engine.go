package transaction

import (
	"context"
	"errors"
	"net/netip"
	"regexp"
	"sync"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/config"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/model"
	statepkg "github.com/apex-intellect/keenwg/xkeen-control/internal/state"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/subscription"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/xray"
)

var (
	ErrInvalidOperationKey = errors.New("invalid_operation_key")
	ErrStaleState          = errors.New("stale_state")
	ErrBusy                = errors.New("busy")
	ErrNodeNotFound        = errors.New("node_not_found")
	ErrOperationStorage    = errors.New("operation_storage_failed")
)

var operationKeyPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

type Fetcher interface {
	Fetch(context.Context, string, int64) ([]byte, error)
}

type Parser func([]byte, int) (subscription.Result, error)

type Store interface {
	SaveSubscription([]model.Node, time.Time) (model.SubscriptionState, error)
	LoadSubscription() (model.SubscriptionState, error)
	SaveControllerState(model.ControllerState) error
	LoadControllerState() (model.ControllerState, error)
	BeginOperation(model.Operation, *model.TransactionSnapshot) error
	UpdateOperation(model.Operation, *model.TransactionSnapshot) error
	FindOperation(string) (model.Operation, bool, error)
}

type Job func(context.Context)

type Engine struct {
	mu        sync.Mutex
	activeKey string
	cfg       config.Config
	fetcher   Fetcher
	parser    Parser
	store     Store
	system    xray.System
	clock     func() time.Time
}

func New(cfg config.Config, fetcher Fetcher, parser Parser, store Store, system xray.System, clock func() time.Time) *Engine {
	if clock == nil {
		clock = time.Now
	}
	return &Engine{cfg: cfg, fetcher: fetcher, parser: parser, store: store, system: system, clock: clock}
}

func (e *Engine) PrepareRefresh(key string, expectedVersion uint64) (model.Operation, Job, error) {
	return e.prepare(key, "refresh", "", nil, expectedVersion)
}

func (e *Engine) PrepareSelect(key, nodeID string, expectedVersion uint64) (model.Operation, Job, error) {
	return e.prepare(key, "select", nodeID, nil, expectedVersion)
}

func (e *Engine) PrepareSelectNode(key string, node model.Node, expectedVersion uint64) (model.Operation, Job, error) {
	if node.ID == "" || node.CanonicalURI == "" || node.Host == "" || node.Port < 1 || node.Port > 65535 {
		return model.Operation{}, nil, ErrNodeNotFound
	}
	selected := node
	return e.prepare(key, "select", "", &selected, expectedVersion)
}

func (e *Engine) prepare(key, kind, nodeID string, supplied *model.Node, expectedVersion uint64) (model.Operation, Job, error) {
	if !operationKeyPattern.MatchString(key) {
		return model.Operation{}, nil, ErrInvalidOperationKey
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	existing, found, err := e.store.FindOperation(key)
	if err != nil {
		return model.Operation{}, nil, ErrOperationStorage
	}
	if found {
		return existing, nil, nil
	}
	if e.activeKey != "" {
		return model.Operation{}, nil, ErrBusy
	}
	controller, err := e.store.LoadControllerState()
	if err != nil {
		return model.Operation{}, nil, ErrOperationStorage
	}
	if controller.StateVersion != expectedVersion {
		return model.Operation{}, nil, ErrStaleState
	}
	var selected model.Node
	if kind == "select" {
		if supplied != nil {
			selected = *supplied
		} else {
			subscriptionState, err := e.store.LoadSubscription()
			if err != nil {
				return model.Operation{}, nil, ErrOperationStorage
			}
			var nodeFound bool
			for _, node := range subscriptionState.Nodes {
				if node.ID == nodeID {
					selected = node
					nodeFound = true
					break
				}
			}
			if !nodeFound {
				return model.Operation{}, nil, ErrNodeNotFound
			}
		}
	}
	operation := model.Operation{
		IdempotencyKey: key,
		Kind:           kind,
		State:          model.OperationQueued,
		StartedAt:      e.clock().Unix(),
	}
	snapshot := &model.TransactionSnapshot{OperationKey: key, Kind: kind, Phase: "queued"}
	if err := e.store.BeginOperation(operation, snapshot); err != nil {
		return model.Operation{}, nil, mapStorePreparationError(err)
	}
	e.activeKey = key
	job := func(ctx context.Context) {
		defer e.clearActive(key)
		if kind == "refresh" {
			e.runRefresh(ctx, operation, expectedVersion)
			return
		}
		e.runSelect(ctx, operation, selected, expectedVersion)
	}
	return operation, job, nil
}

func (e *Engine) runRefresh(ctx context.Context, operation model.Operation, expectedVersion uint64) {
	snapshot := &model.TransactionSnapshot{OperationKey: operation.IdempotencyKey, Kind: operation.Kind, Phase: "fetch"}
	operation.State = model.OperationRunning
	if err := e.store.UpdateOperation(operation, snapshot); err != nil {
		return
	}
	payload, err := e.fetcher.Fetch(ctx, e.cfg.SubscriptionURL, e.cfg.MaxSubscriptionSize)
	if err != nil {
		e.finish(operation, model.ResultFailedNoChange, "subscription_download_failed")
		return
	}
	parsed, err := e.parser(payload, e.cfg.MaxNodes)
	if err != nil {
		e.finish(operation, model.ResultFailedNoChange, "invalid_subscription")
		return
	}
	saved, err := e.store.SaveSubscription(parsed.Nodes, e.clock())
	if err != nil {
		e.finish(operation, model.ResultFailedNoChange, "storage_error")
		return
	}
	controller, err := e.store.LoadControllerState()
	if err != nil || controller.StateVersion != expectedVersion {
		e.finish(operation, model.ResultFailedNoChange, "storage_error")
		return
	}
	controller.StateVersion++
	if controller.Active != nil {
		controller.Active.MissingFromSubscription = !containsNodeID(saved.Nodes, controller.Active.ID)
	}
	if err := e.store.SaveControllerState(controller); err != nil {
		e.finish(operation, model.ResultFailedNoChange, "storage_error")
		return
	}
	e.finish(operation, model.ResultSuccess, "")
}

func (e *Engine) runSelect(ctx context.Context, operation model.Operation, node model.Node, expectedVersion uint64) {
	addresses, err := e.system.ResolveIPv4(ctx, node.Host)
	if err != nil || len(addresses) == 0 {
		e.finish(operation, model.ResultFailedNoChange, "resolve_failed")
		return
	}
	selectedIP := addresses[0]
	originalOutbounds, err := e.system.ReadFile(e.cfg.OutboundsPath)
	if err != nil {
		e.finish(operation, model.ResultFailedNoChange, "read_failed")
		return
	}
	originalExcludes, err := e.system.ReadFile(e.cfg.ExcludePath)
	if err != nil {
		e.finish(operation, model.ResultFailedNoChange, "read_failed")
		return
	}
	nextOutbounds, err := xray.RenderOutbounds(originalOutbounds, node, selectedIP)
	if err != nil {
		e.finish(operation, model.ResultFailedNoChange, "render_failed")
		return
	}
	nextExcludes, err := xray.ReplaceManagedExcludeBlock(originalExcludes, selectedIP)
	if err != nil {
		e.finish(operation, model.ResultFailedNoChange, "render_failed")
		return
	}
	controller, err := e.store.LoadControllerState()
	if err != nil || controller.StateVersion != expectedVersion || controller.Active == nil {
		e.finish(operation, model.ResultFailedNoChange, "stale_state")
		return
	}
	snapshot := &model.TransactionSnapshot{
		OperationKey:         operation.IdempotencyKey,
		Kind:                 operation.Kind,
		Phase:                "writing",
		OriginalOutbounds:    append([]byte(nil), originalOutbounds...),
		OriginalExcludes:     append([]byte(nil), originalExcludes...),
		OriginalActive:       cloneActive(controller.Active),
		OriginalStateVersion: controller.StateVersion,
		OriginalIP:           controller.Active.ResolvedIP,
		CandidateIP:          selectedIP.String(),
	}
	operation.State = model.OperationRunning
	if err := e.store.UpdateOperation(operation, snapshot); err != nil {
		return
	}
	if err := e.system.WriteAtomic(e.cfg.OutboundsPath, nextOutbounds, 0o600); err != nil {
		e.finish(operation, model.ResultFailedNoChange, "write_failed")
		return
	}
	if err := e.system.WriteAtomic(e.cfg.ExcludePath, nextExcludes, 0o600); err != nil {
		e.rollback(ctx, operation, snapshot, "write_failed")
		return
	}
	if err := e.system.Validate(ctx); err != nil {
		e.rollback(ctx, operation, snapshot, "xray_validation_failed")
		return
	}
	if err := e.system.Restart(ctx); err != nil {
		e.rollback(ctx, operation, snapshot, "xkeen_restart_failed")
		return
	}
	if err := e.system.Verify(ctx, selectedIP); err != nil {
		e.rollback(ctx, operation, snapshot, "xkeen_verification_failed")
		return
	}
	controller, err = e.store.LoadControllerState()
	if err != nil || controller.StateVersion != expectedVersion {
		e.finish(operation, model.ResultUncertain, "storage_error")
		return
	}
	active := model.SanitizeNode(node, true)
	controller.Active = &model.ActiveNode{
		PublicNode:              active,
		ResolvedIP:              selectedIP.String(),
		ConfirmedAt:             e.clock().Unix(),
		MissingFromSubscription: false,
	}
	controller.StateVersion++
	if err := e.store.SaveControllerState(controller); err != nil {
		e.rollback(ctx, operation, snapshot, "storage_error")
		return
	}
	e.finish(operation, model.ResultSuccess, "")
}

func (e *Engine) Recover(ctx context.Context) error {
	controller, err := e.store.LoadControllerState()
	if err != nil {
		return ErrOperationStorage
	}
	if controller.InProgress == nil {
		return nil
	}
	snapshot := controller.InProgress
	operation, found, err := e.store.FindOperation(snapshot.OperationKey)
	if err != nil || !found || operation.State == model.OperationTerminal {
		return ErrOperationStorage
	}
	if snapshot.Kind == "refresh" || snapshot.Phase == "queued" {
		return e.finish(operation, model.ResultFailedNoChange, "recovered_no_change")
	}
	if snapshot.Kind != "select" || len(snapshot.OriginalOutbounds) == 0 || len(snapshot.OriginalExcludes) == 0 || snapshot.OriginalActive == nil {
		if finishErr := e.finish(operation, model.ResultUncertain, "recovery_snapshot_invalid"); finishErr != nil {
			return finishErr
		}
		return ErrOperationStorage
	}
	if e.rollback(ctx, operation, snapshot, "recovered_after_restart") != model.ResultFailedRolledBack {
		return ErrOperationStorage
	}
	return nil
}

func (e *Engine) rollback(ctx context.Context, operation model.Operation, snapshot *model.TransactionSnapshot, errorCode string) model.OperationResult {
	rollbackFailed := false
	if snapshot == nil || len(snapshot.OriginalOutbounds) == 0 || len(snapshot.OriginalExcludes) == 0 || snapshot.OriginalActive == nil {
		rollbackFailed = true
	} else {
		if err := e.system.WriteAtomic(e.cfg.OutboundsPath, snapshot.OriginalOutbounds, 0o600); err != nil {
			rollbackFailed = true
		}
		if err := e.system.WriteAtomic(e.cfg.ExcludePath, snapshot.OriginalExcludes, 0o600); err != nil {
			rollbackFailed = true
		}
		if err := e.system.Validate(ctx); err != nil {
			rollbackFailed = true
		}
		if err := e.system.Restart(ctx); err != nil {
			rollbackFailed = true
		}
		oldIP, err := netip.ParseAddr(snapshot.OriginalIP)
		if err != nil || e.system.Verify(ctx, oldIP) != nil {
			rollbackFailed = true
		}
		if !rollbackFailed {
			controller, err := e.store.LoadControllerState()
			if err != nil {
				rollbackFailed = true
			} else {
				controller.Active = cloneActive(snapshot.OriginalActive)
				if controller.StateVersion > snapshot.OriginalStateVersion {
					controller.StateVersion++
				} else {
					controller.StateVersion = snapshot.OriginalStateVersion
				}
				if err := e.store.SaveControllerState(controller); err != nil {
					rollbackFailed = true
				}
			}
		}
	}
	if rollbackFailed {
		_ = e.finish(operation, model.ResultUncertain, "rollback_failed")
		return model.ResultUncertain
	}
	_ = e.finish(operation, model.ResultFailedRolledBack, errorCode)
	return model.ResultFailedRolledBack
}

func (e *Engine) finish(operation model.Operation, result model.OperationResult, errorCode string) error {
	finished := e.clock().Unix()
	operation.State = model.OperationTerminal
	operation.Result = result
	operation.ErrorCode = errorCode
	operation.FinishedAt = &finished
	if err := e.store.UpdateOperation(operation, nil); err != nil {
		return ErrOperationStorage
	}
	return nil
}

func (e *Engine) clearActive(key string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.activeKey == key {
		e.activeKey = ""
	}
}

func containsNodeID(nodes []model.Node, id string) bool {
	for _, node := range nodes {
		if node.ID == id {
			return true
		}
	}
	return false
}

func cloneActive(active *model.ActiveNode) *model.ActiveNode {
	if active == nil {
		return nil
	}
	cloned := *active
	cloned.Warnings = append([]string(nil), active.Warnings...)
	return &cloned
}

func mapStorePreparationError(err error) error {
	switch {
	case errors.Is(err, statepkg.ErrBusy):
		return ErrBusy
	default:
		return ErrOperationStorage
	}
}
