package scenario

import (
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"testing"
)

func TestCompileExpandsVisibleOptionalStepsDeterministically(t *testing.T) {
	preset := Preset{ID: "media-direct", Label: "Media direct", Conditions: Conditions{
		DeviceIDs: []string{"tv"}, Services: []string{"streaming"}, Domains: []string{"video.example"}, CIDRs: []string{"192.0.2.0/24"},
	}, Outcome: Outcome{Mode: "direct"}}
	plan, err := Compile(preset, 12, 12, Modules{Devices: true, Services: false, Domains: true, IP: true})
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.Steps) != 3 || plan.Steps[0].Module != "devices" || plan.Steps[1].Module != "domains" || plan.Steps[2].Module != "ip" {
		t.Fatalf("steps=%+v", plan.Steps)
	}
	if len(plan.Skipped) != 1 || plan.Skipped[0] != "services" {
		t.Fatalf("skipped=%v", plan.Skipped)
	}
	if plan.StateVersion != 12 || plan.Outcome.Mode != "direct" {
		t.Fatalf("plan=%+v", plan)
	}
}

func TestCompileRejectsStaleAndInvalidGroup(t *testing.T) {
	preset := Preset{ID: "work", Label: "Work", Conditions: Conditions{Domains: []string{"work.example"}}, Outcome: Outcome{Mode: "group", GroupID: "main"}}
	if _, err := Compile(preset, 4, 3, Modules{Domains: true}); !errors.Is(err, ErrStaleState) {
		t.Fatalf("stale=%v", err)
	}
	preset.Outcome.GroupID = "bad group"
	if _, err := Compile(preset, 4, 4, Modules{Domains: true}); !errors.Is(err, ErrInvalidPreset) {
		t.Fatalf("invalid=%v", err)
	}
}

func TestSignedPresetBundleDetectsAnyReviewedDiff(t *testing.T) {
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	bundle := Bundle{SchemaVersion: 1, Revision: 7, Presets: []Preset{{ID: "safe", Label: "Safe", Conditions: Conditions{Domains: []string{"example.com"}}, Outcome: Outcome{Mode: "direct"}}}}
	bundle.Signature, err = SignBundle(private, bundle)
	if err != nil {
		t.Fatal(err)
	}
	if err := VerifyBundle(public, bundle); err != nil {
		t.Fatal(err)
	}
	bundle.Presets[0].Outcome.Mode = "group"
	bundle.Presets[0].Outcome.GroupID = "main"
	if err := VerifyBundle(public, bundle); !errors.Is(err, ErrInvalidSignature) {
		t.Fatalf("tamper=%v", err)
	}
}

func TestCompileExpandsSuffixAndGeoSiteAsVisibleDomainSteps(t *testing.T) {
	preset := Preset{ID: "russia-direct", Label: "Russia direct", Conditions: Conditions{Suffixes: []string{"ru", "xn--p1ai"}, GeoSites: []string{"category-gov-ru"}}, Outcome: Outcome{Mode: "direct"}}
	plan, err := Compile(preset, 9, 9, Modules{Domains: true})
	if err != nil {
		t.Fatal(err)
	}
	want := []struct{ kind, value string }{{"suffix", "ru"}, {"suffix", "xn--p1ai"}, {"geosite", "category-gov-ru"}}
	if len(plan.Steps) != len(want) {
		t.Fatalf("steps=%+v", plan.Steps)
	}
	for index, expected := range want {
		if plan.Steps[index].Module != "domains" || plan.Steps[index].MatchKind != expected.kind || plan.Steps[index].Value != expected.value {
			t.Fatalf("step[%d]=%+v", index, plan.Steps[index])
		}
	}
}
