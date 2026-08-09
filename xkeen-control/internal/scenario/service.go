package scenario

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"sort"

	"github.com/goldb/keenwg/xkeen-control/internal/coordinator"
)

var ErrPresetNotFound = errors.New("scenario_preset_not_found")

type RuntimeState struct {
	StateVersion   uint64            `json:"state_version"`
	Modules        Modules           `json:"modules"`
	ModuleVersions map[string]uint64 `json:"module_versions"`
}

type StateProvider interface {
	Current(context.Context) (RuntimeState, error)
}
type Executor interface {
	Execute(context.Context, coordinator.Plan) coordinator.Result
}

type Review struct {
	SchemaVersion int    `json:"schema_version"`
	PlanID        string `json:"plan_id"`
	Plan          Plan   `json:"plan"`
}

type Catalog struct {
	SchemaVersion int      `json:"schema_version"`
	StateVersion  uint64   `json:"state_version"`
	Modules       Modules  `json:"modules"`
	Presets       []Preset `json:"presets"`
}

type ApplyRequest struct {
	PresetID             string `json:"preset_id"`
	ReviewedStateVersion uint64 `json:"reviewed_state_version"`
	ReviewedPlanID       string `json:"reviewed_plan_id"`
	IdempotencyKey       string `json:"idempotency_key"`
}

type Service struct {
	presets  map[string]Preset
	state    StateProvider
	executor Executor
}

func NewService(presets []Preset, state StateProvider, executor Executor) (*Service, error) {
	if len(presets) == 0 || state == nil || executor == nil {
		return nil, ErrInvalidPreset
	}
	service := &Service{presets: make(map[string]Preset, len(presets)), state: state, executor: executor}
	for _, input := range presets {
		preset, err := canonicalPreset(input)
		if err != nil {
			return nil, err
		}
		if _, ok := service.presets[preset.ID]; ok {
			return nil, ErrInvalidPreset
		}
		service.presets[preset.ID] = preset
	}
	return service, nil
}

func (s *Service) Catalog(ctx context.Context) (Catalog, error) {
	state, err := s.state.Current(ctx)
	if err != nil {
		return Catalog{}, err
	}
	ids := make([]string, 0, len(s.presets))
	for id := range s.presets {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	presets := make([]Preset, 0, len(ids))
	for _, id := range ids {
		presets = append(presets, clonePreset(s.presets[id]))
	}
	return Catalog{SchemaVersion: 1, StateVersion: state.StateVersion, Modules: state.Modules, Presets: presets}, nil
}

func clonePreset(input Preset) Preset {
	input.Conditions.DeviceIDs = append([]string(nil), input.Conditions.DeviceIDs...)
	input.Conditions.Services = append([]string(nil), input.Conditions.Services...)
	input.Conditions.Domains = append([]string(nil), input.Conditions.Domains...)
	input.Conditions.Suffixes = append([]string(nil), input.Conditions.Suffixes...)
	input.Conditions.GeoSites = append([]string(nil), input.Conditions.GeoSites...)
	input.Conditions.CIDRs = append([]string(nil), input.Conditions.CIDRs...)
	return input
}

func (s *Service) Review(ctx context.Context, presetID string, reviewedVersion uint64) (Review, error) {
	preset, ok := s.presets[presetID]
	if !ok {
		return Review{}, ErrPresetNotFound
	}
	state, err := s.state.Current(ctx)
	if err != nil {
		return Review{}, err
	}
	plan, err := Compile(preset, state.StateVersion, reviewedVersion, state.Modules)
	if err != nil {
		return Review{}, err
	}
	if err := validateRuntimePlan(plan, state); err != nil {
		return Review{}, err
	}
	body, err := json.Marshal(plan)
	if err != nil {
		return Review{}, ErrInvalidPreset
	}
	hash := sha256.Sum256(body)
	return Review{SchemaVersion: 1, PlanID: hex.EncodeToString(hash[:16]), Plan: plan}, nil
}

func (s *Service) Apply(ctx context.Context, request ApplyRequest) coordinator.Result {
	if !identifier.MatchString(request.IdempotencyKey) || len(request.ReviewedPlanID) != 32 {
		return coordinator.Result{Status: coordinator.StatusRejected, ErrorCode: "invalid_request", PlanID: request.IdempotencyKey}
	}
	review, err := s.Review(ctx, request.PresetID, request.ReviewedStateVersion)
	if err != nil {
		code := "scenario_unavailable"
		if errors.Is(err, ErrStaleState) {
			code = "stale_state"
		}
		if errors.Is(err, ErrPresetNotFound) || errors.Is(err, ErrInvalidPreset) {
			code = "invalid_request"
		}
		return coordinator.Result{Status: coordinator.StatusRejected, ErrorCode: code, PlanID: request.IdempotencyKey}
	}
	if review.PlanID != request.ReviewedPlanID {
		return coordinator.Result{Status: coordinator.StatusRejected, ErrorCode: "plan_changed", PlanID: request.IdempotencyKey}
	}
	state, err := s.state.Current(ctx)
	if err != nil {
		return coordinator.Result{Status: coordinator.StatusRejected, ErrorCode: "scenario_unavailable", PlanID: request.IdempotencyKey}
	}
	grouped := map[string][]Step{}
	for _, step := range review.Plan.Steps {
		module := executionModule(step.Module)
		grouped[module] = append(grouped[module], step)
	}
	modules := make([]string, 0, len(grouped))
	for module := range grouped {
		modules = append(modules, module)
	}
	sort.Strings(modules)
	execution := coordinator.Plan{ID: request.IdempotencyKey, Steps: make([]coordinator.Step, 0, len(modules))}
	for _, module := range modules {
		payload, marshalErr := json.Marshal(grouped[module])
		if marshalErr != nil {
			return coordinator.Result{Status: coordinator.StatusRejected, ErrorCode: "invalid_plan", PlanID: request.IdempotencyKey}
		}
		version := state.ModuleVersions[module]
		if version == 0 {
			return coordinator.Result{Status: coordinator.StatusRejected, ErrorCode: "module_unavailable", PlanID: request.IdempotencyKey}
		}
		execution.Steps = append(execution.Steps, coordinator.Step{Module: module, ReviewedVersion: version, Payload: payload})
	}
	return s.executor.Execute(ctx, execution)
}

func validateRuntimePlan(plan Plan, state RuntimeState) error {
	if state.StateVersion == 0 || len(state.ModuleVersions) == 0 {
		return ErrInvalidPreset
	}
	for _, step := range plan.Steps {
		if state.ModuleVersions[executionModule(step.Module)] == 0 {
			return ErrInvalidPreset
		}
	}
	return nil
}

func executionModule(visible string) string {
	if visible == "domains" || visible == "ip" {
		return "routes"
	}
	return visible
}
