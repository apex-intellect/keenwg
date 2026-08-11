package app

import (
	"context"
	"io/fs"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/config"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/domainpolicy"
)

func TestBootstrapDomainPolicyAllowsMismatchOnlyForPendingReviewedRecovery(t *testing.T) {
	runtime := &recoveryDomainRuntime{files: map[string][]byte{"routing": []byte("prefix\n      // 1C ecosystem — direct\n      {}\n      // Direct: Russian IP ranges\nsuffix\n")}}
	policy := domainpolicy.Policy{SchemaVersion: 1, Rules: []domainpolicy.Rule{}}
	if err := domainpolicy.NewStore("policy", "backup", runtime).Save(policy, nil); err != nil {
		t.Fatal(err)
	}
	cfg := config.Config{DomainPolicyPath: "policy", DomainPolicyBackup: "backup", RoutingPath: "routing"}
	exists := func(string) (bool, error) { return true, nil }
	if _, err := BootstrapDomainPolicy(context.Background(), cfg, runtime, exists, func(string) error { return nil }); err == nil {
		t.Fatal("normal bootstrap accepted projection mismatch")
	}
	service, err := BootstrapDomainPolicyForRecovery(context.Background(), cfg, runtime, exists, func(string) error { return nil })
	if err != nil || service == nil {
		t.Fatalf("service=%v err=%v", service, err)
	}
}

type recoveryDomainRuntime struct{ files map[string][]byte }

func (r *recoveryDomainRuntime) ReadFile(path string) ([]byte, error) {
	body, ok := r.files[path]
	if !ok {
		return nil, fs.ErrNotExist
	}
	return append([]byte(nil), body...), nil
}
func (r *recoveryDomainRuntime) WriteAtomic(path string, body []byte, _ fs.FileMode) error {
	r.files[path] = append([]byte(nil), body...)
	return nil
}
func (*recoveryDomainRuntime) Validate(context.Context) error             { return nil }
func (*recoveryDomainRuntime) Restart(context.Context) error              { return nil }
func (*recoveryDomainRuntime) CheckGeoSite(context.Context, string) error { return nil }
