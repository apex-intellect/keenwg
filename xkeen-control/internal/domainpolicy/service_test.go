package domainpolicy

import (
	"bytes"
	"context"
	"errors"
	"io/fs"
	"testing"
)

type runtimeFake struct {
	files            map[string][]byte
	writes           int
	validations      int
	restarts         int
	validateFailures int
	restartFailures  int
	writeFailures    int
	writeFailureAt   int
	geositeAvailable bool
}

func (f *runtimeFake) ReadFile(path string) ([]byte, error) {
	body, ok := f.files[path]
	if !ok {
		return nil, fs.ErrNotExist
	}
	return append([]byte(nil), body...), nil
}
func (f *runtimeFake) WriteAtomic(path string, body []byte, _ fs.FileMode) error {
	f.writes++
	if f.writeFailureAt > 0 && f.writes == f.writeFailureAt {
		return errors.New("write")
	}
	if f.writeFailures > 0 {
		f.writeFailures--
		return errors.New("write")
	}
	f.files[path] = append([]byte(nil), body...)
	return nil
}
func (f *runtimeFake) Validate(context.Context) error {
	f.validations++
	if f.validateFailures > 0 {
		f.validateFailures--
		return errors.New("validate")
	}
	return nil
}
func (f *runtimeFake) Restart(context.Context) error {
	f.restarts++
	if f.restartFailures > 0 {
		f.restartFailures--
		return errors.New("restart")
	}
	return nil
}
func (f *runtimeFake) CheckGeoSite(context.Context, string) error {
	if !f.geositeAvailable {
		return errors.New("missing")
	}
	return nil
}

func serviceFixture(t *testing.T) (*Service, *runtimeFake) {
	t.Helper()
	routing := routingFixture(t)
	policy, _, err := ImportLegacy(routing)
	if err != nil {
		t.Fatal(err)
	}
	routing, err = RenderRouting(routing, policy)
	if err != nil {
		t.Fatal(err)
	}
	system := &runtimeFake{files: map[string][]byte{"routing": routing}, geositeAvailable: true}
	store := NewStore("policy", "policy.bak", system)
	if err := store.Save(policy, nil); err != nil {
		t.Fatal(err)
	}
	system.writes = 0
	return NewService("policy", "policy.bak", "routing", system), system
}

func TestServiceCreateCommitsAndIdempotentReplayDoesNotRestart(t *testing.T) {
	service, system := serviceFixture(t)
	status, err := service.Status(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	draft := Rule{Kind: "domain", Value: "example.com", Effect: "vpn", Label: "Example", Enabled: true}
	mutation := Mutation{StateVersion: status.StateVersion, IdempotencyKey: "create-example-01", Action: "create", Rule: &draft}
	result := service.Mutate(context.Background(), mutation)
	if result.Result != "committed" || system.validations != 1 || system.restarts != 1 {
		t.Fatalf("result=%+v validations=%d restarts=%d", result, system.validations, system.restarts)
	}
	if !bytes.Contains(system.files["routing"], []byte("domain:example.com")) {
		t.Fatal("routing not projected")
	}
	replayed := service.Mutate(context.Background(), mutation)
	if replayed.Result != "committed" || system.restarts != 1 {
		t.Fatalf("replayed=%+v restarts=%d", replayed, system.restarts)
	}
}

func TestServiceRejectsStaleProtectedAndUnavailablePreset(t *testing.T) {
	service, system := serviceFixture(t)
	status, _ := service.Status(context.Background())
	domain := Rule{Kind: "domain", Value: "example.com", Effect: "direct", Enabled: true}
	if got := service.Mutate(context.Background(), Mutation{StateVersion: status.StateVersion + 1, IdempotencyKey: "stale-state-01", Action: "create", Rule: &domain}); got.Result != "rejected" {
		t.Fatalf("stale=%+v", got)
	}
	protected := status.Rules[0]
	protected.Protected = true
	protected.Source = "system"
	policy, body, _ := service.store.Load()
	policy.Rules[0] = protected
	if err := service.store.Save(policy, body); err != nil {
		t.Fatal(err)
	}
	status, _ = service.Status(context.Background())
	if got := service.Mutate(context.Background(), Mutation{StateVersion: status.StateVersion, IdempotencyKey: "delete-protected-01", Action: "delete", RuleID: protected.ID}); got.Result != "rejected" {
		t.Fatalf("protected=%+v", got)
	}
	system.geositeAvailable = false
	preset := Rule{Kind: "geosite", Value: "category-gov-ru", Effect: "vpn", Enabled: true}
	if got := service.Mutate(context.Background(), Mutation{StateVersion: status.StateVersion, IdempotencyKey: "preset-missing-01", Action: "create", Rule: &preset}); got.Result != "rejected" {
		t.Fatalf("preset=%+v", got)
	}
}

func TestServiceRestartFailureRestoresPolicyAndRouting(t *testing.T) {
	service, system := serviceFixture(t)
	status, _ := service.Status(context.Background())
	oldPolicy := append([]byte(nil), system.files["policy"]...)
	oldRouting := append([]byte(nil), system.files["routing"]...)
	system.restartFailures = 1
	draft := Rule{Kind: "domain", Value: "example.com", Effect: "direct", Enabled: true}
	result := service.Mutate(context.Background(), Mutation{StateVersion: status.StateVersion, IdempotencyKey: "rollback-example-01", Action: "create", Rule: &draft})
	if result.Result != "rolled_back" || !bytes.Equal(oldPolicy, system.files["policy"]) || !bytes.Equal(oldRouting, system.files["routing"]) || system.restarts != 2 {
		t.Fatalf("result=%+v restarts=%d", result, system.restarts)
	}
}

func TestServiceRollbackFailureReturnsUncertain(t *testing.T) {
	service, system := serviceFixture(t)
	status, _ := service.Status(context.Background())
	system.validateFailures = 1
	system.writeFailureAt = 4
	draft := Rule{Kind: "domain", Value: "example.com", Effect: "direct", Enabled: true}
	result := service.Mutate(context.Background(), Mutation{StateVersion: status.StateVersion, IdempotencyKey: "uncertain-example-01", Action: "create", Rule: &draft})
	if result.Result != "uncertain" {
		t.Fatalf("result=%+v", result)
	}
}

func TestServiceReplaceCommitsWholeReviewedPolicyWithOneRestart(t *testing.T) {
	service, system := serviceFixture(t)
	status, err := service.Status(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	direct, err := CanonicalizeRule(Rule{Kind: "domain", Value: "video.example", Effect: "direct", Label: "Video", Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	vpn, err := CanonicalizeRule(Rule{Kind: "domain", Value: "work.example", Effect: "vpn", Label: "Work", Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	result := service.Replace(context.Background(), ReplaceRequest{StateVersion: status.StateVersion, IdempotencyKey: "replace-policy-0001", Rules: []Rule{status.Rules[0], direct, vpn}})
	if result.Result != "committed" || system.restarts != 1 {
		t.Fatalf("result=%+v restarts=%d", result, system.restarts)
	}
	if len(result.Status.Rules) != 3 || !bytes.Contains(system.files["routing"], []byte("domain:video.example")) || !bytes.Contains(system.files["routing"], []byte("domain:work.example")) {
		t.Fatalf("status=%+v routing=%s", result.Status, string(system.files["routing"]))
	}
	before := append([]byte(nil), system.files["routing"]...)
	stale := service.Replace(context.Background(), ReplaceRequest{StateVersion: status.StateVersion, IdempotencyKey: "replace-policy-0002", Rules: []Rule{direct}})
	if stale.Result != "rejected" || !bytes.Equal(before, system.files["routing"]) {
		t.Fatalf("stale=%+v", stale)
	}
}
