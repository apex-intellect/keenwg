package domainpolicy

import "testing"

func FuzzCanonicalizeRuleNeverPanics(f *testing.F) {
	f.Add("domain", "example.org", "direct", "Example")
	f.Add("suffix", "ru", "direct", "RU zone")
	f.Add("cidr", "10.0.0.0/8", "vpn", "Private")
	f.Fuzz(func(t *testing.T, kind, value, effect, label string) {
		if len(kind)+len(value)+len(effect)+len(label) > 1<<20 {
			t.Skip()
		}
		canonical, err := CanonicalizeRule(Rule{Kind: kind, Value: value, Effect: effect, Label: label})
		if err == nil {
			if canonical.ID == "" || canonical.Value == "" {
				t.Fatal("accepted rule is incomplete")
			}
			if _, secondErr := CanonicalizeRule(canonical); secondErr != nil {
				t.Fatalf("canonical rule is not idempotent: %v", secondErr)
			}
		}
	})
}
