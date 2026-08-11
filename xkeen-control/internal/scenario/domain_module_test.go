package scenario

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/domainpolicy"
)

func TestDomainModuleStagesAppliesVerifiesAndRestoresExactPolicy(t *testing.T) {
	base, err := domainpolicy.CanonicalizeRule(domainpolicy.Rule{Kind: "domain", Value: "base.example", Effect: "vpn", Label: "Base", Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	manager := &fakeDomainReplaceManager{status: domainpolicy.NewStatus(11, []domainpolicy.Rule{base}, nil, nil)}
	module := NewDomainModule(manager)
	before, err := module.Capture(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	payload, _ := json.Marshal([]Step{
		{Module: "domains", MatchKind: "domain", Value: "video.example", Outcome: Outcome{Mode: "direct"}},
		{Module: "ip", MatchKind: "cidr", Value: "198.51.100.0/24", Outcome: Outcome{Mode: "group", GroupID: "main"}},
	})
	staged, err := module.Stage(context.Background(), before, payload)
	if err != nil {
		t.Fatal(err)
	}
	if err := module.Validate(context.Background(), staged); err != nil {
		t.Fatal(err)
	}
	if err := module.Apply(context.Background(), staged); err != nil {
		t.Fatal(err)
	}
	if err := module.Verify(context.Background(), staged); err != nil {
		t.Fatal(err)
	}
	if len(manager.status.Rules) != 3 || manager.status.Rules[1].Value != "video.example" || manager.status.Rules[1].Effect != "direct" || manager.status.Rules[2].Kind != "cidr" || manager.status.Rules[2].Effect != "vpn" {
		t.Fatalf("status=%+v", manager.status)
	}
	if err := module.Restore(context.Background(), before); err != nil {
		t.Fatal(err)
	}
	if err := module.VerifyRestore(context.Background(), before); err != nil {
		t.Fatal(err)
	}
	if len(manager.status.Rules) != 1 || manager.status.Rules[0] != base {
		t.Fatalf("restored=%+v", manager.status.Rules)
	}
}

func TestDomainModuleRejectsUnsupportedOrSecretBearingPayload(t *testing.T) {
	manager := &fakeDomainReplaceManager{status: domainpolicy.NewStatus(3, nil, nil, nil)}
	module := NewDomainModule(manager)
	before, err := module.Capture(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	for _, raw := range []string{
		`[{"module":"services","match_kind":"service","value":"streaming","outcome":{"mode":"direct"}}]`,
		`[{"module":"domains","match_kind":"domain","value":"bad name","outcome":{"mode":"direct"},"secret":"x"}]`,
	} {
		if _, err := module.Stage(context.Background(), before, []byte(raw)); err == nil {
			t.Fatalf("accepted %s", raw)
		}
	}
}

type fakeDomainReplaceManager struct{ status domainpolicy.Status }

func (f *fakeDomainReplaceManager) Status(context.Context) (domainpolicy.Status, error) {
	return f.status, nil
}
func (f *fakeDomainReplaceManager) Replace(_ context.Context, request domainpolicy.ReplaceRequest) domainpolicy.Result {
	f.status = domainpolicy.NewStatus(f.status.StateVersion+1, append([]domainpolicy.Rule(nil), request.Rules...), nil, nil)
	return domainpolicy.Result{Result: "committed", Status: f.status}
}
