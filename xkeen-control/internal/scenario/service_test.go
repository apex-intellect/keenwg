package scenario

import (
	"context"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/coordinator"
)

func TestReviewIsPlanOnlyAndApplyRequiresExactReviewedPlan(t *testing.T) {
	preset := Preset{ID: "media-direct", Label: "Media", Conditions: Conditions{DeviceIDs: []string{"tv"}, Domains: []string{"video.example"}}, Outcome: Outcome{Mode: "direct"}}
	state := &fakeScenarioState{snapshot: RuntimeState{StateVersion: 20, Modules: Modules{Devices: true, Domains: true}, ModuleVersions: map[string]uint64{"devices": 4, "routes": 8}}}
	executor := &fakeScenarioExecutor{result: coordinator.Result{Status: coordinator.StatusCommitted}}
	service, err := NewService([]Preset{preset}, state, executor)
	if err != nil {
		t.Fatal(err)
	}
	review, err := service.Review(context.Background(), "media-direct", 20)
	if err != nil {
		t.Fatal(err)
	}
	if executor.calls != 0 || len(review.Plan.Steps) != 2 || review.PlanID == "" {
		t.Fatalf("review=%+v calls=%d", review, executor.calls)
	}
	result := service.Apply(context.Background(), ApplyRequest{PresetID: "media-direct", ReviewedStateVersion: 20, ReviewedPlanID: review.PlanID, IdempotencyKey: "scenario-apply-0001"})
	if result.Status != coordinator.StatusCommitted || executor.calls != 1 {
		t.Fatalf("result=%+v calls=%d", result, executor.calls)
	}
	if len(executor.plan.Steps) != 2 || executor.plan.Steps[0].Module != "devices" || executor.plan.Steps[0].ReviewedVersion != 4 || executor.plan.Steps[1].Module != "routes" || executor.plan.Steps[1].ReviewedVersion != 8 {
		t.Fatalf("execution=%+v", executor.plan)
	}
}

func TestApplyRejectsStaleOrTamperedReviewWithoutExecution(t *testing.T) {
	preset := Preset{ID: "work", Label: "Work", Conditions: Conditions{Domains: []string{"work.example"}}, Outcome: Outcome{Mode: "group", GroupID: "main"}}
	state := &fakeScenarioState{snapshot: RuntimeState{StateVersion: 5, Modules: Modules{Domains: true}, ModuleVersions: map[string]uint64{"routes": 2}}}
	executor := &fakeScenarioExecutor{}
	service, _ := NewService([]Preset{preset}, state, executor)
	if result := service.Apply(context.Background(), ApplyRequest{PresetID: "work", ReviewedStateVersion: 4, ReviewedPlanID: "00000000000000000000000000000000", IdempotencyKey: "scenario-apply-0002"}); result.Status != coordinator.StatusRejected || result.ErrorCode != "stale_state" {
		t.Fatalf("stale=%+v", result)
	}
	_, err := service.Review(context.Background(), "work", 5)
	if err != nil {
		t.Fatal(err)
	}
	if result := service.Apply(context.Background(), ApplyRequest{PresetID: "work", ReviewedStateVersion: 5, ReviewedPlanID: "00000000000000000000000000000000", IdempotencyKey: "scenario-apply-0003"}); result.Status != coordinator.StatusRejected || result.ErrorCode != "plan_changed" {
		t.Fatalf("tampered=%+v", result)
	}
	if executor.calls != 0 {
		t.Fatalf("calls=%d", executor.calls)
	}
}

func TestCatalogListsPresetsAndCurrentOptionalModulesWithoutApplying(t *testing.T) {
	state := &fakeScenarioState{snapshot: RuntimeState{StateVersion: 12, Modules: Modules{Domains: true, IP: true}, ModuleVersions: map[string]uint64{"routes": 12}}}
	executor := &fakeScenarioExecutor{}
	service, err := NewService(DefaultPresets(), state, executor)
	if err != nil {
		t.Fatal(err)
	}
	catalog, err := service.Catalog(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if catalog.SchemaVersion != 1 || catalog.StateVersion != 12 || len(catalog.Presets) < 3 || !catalog.Modules.Domains || !catalog.Modules.IP || executor.calls != 0 {
		t.Fatalf("catalog=%+v calls=%d", catalog, executor.calls)
	}
	catalog.Presets[0].Label = "changed"
	again, err := service.Catalog(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if again.Presets[0].Label == "changed" {
		t.Fatal("catalog leaked mutable preset storage")
	}
}

type fakeScenarioState struct {
	snapshot RuntimeState
	err      error
}

func (f *fakeScenarioState) Current(context.Context) (RuntimeState, error) { return f.snapshot, f.err }

type fakeScenarioExecutor struct {
	calls  int
	plan   coordinator.Plan
	result coordinator.Result
}

func (f *fakeScenarioExecutor) Execute(_ context.Context, plan coordinator.Plan) coordinator.Result {
	f.calls++
	f.plan = plan
	return f.result
}
