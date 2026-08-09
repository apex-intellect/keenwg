package scenario

import "testing"

func TestDefaultPresetsAreUniqueCompilableAndUsefulWithoutOptionalEngines(t *testing.T) {
	presets := DefaultPresets()
	if len(presets) < 3 {
		t.Fatalf("presets=%+v", presets)
	}
	seen := map[string]struct{}{}
	for _, preset := range presets {
		if _, ok := seen[preset.ID]; ok {
			t.Fatalf("duplicate %s", preset.ID)
		}
		seen[preset.ID] = struct{}{}
		plan, err := Compile(preset, 1, 1, Modules{Domains: true, IP: true})
		if err != nil {
			t.Fatalf("%s: %v", preset.ID, err)
		}
		if len(plan.Steps) == 0 {
			t.Fatalf("%s has no supported steps", preset.ID)
		}
	}
	for _, required := range []string{"russia-direct", "okko-direct", "emias-direct"} {
		if _, ok := seen[required]; !ok {
			t.Fatalf("missing %s", required)
		}
	}
}
