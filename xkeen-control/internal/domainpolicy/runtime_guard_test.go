package domainpolicy

import (
	"context"
	"errors"
	"testing"
)

func TestRuntimeMutationGuardRejectsBothDomainWritePathsBeforeAnyWrite(t *testing.T) {
	service, system := serviceFixture(t)
	status, err := service.Status(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	service.SetMutationGuard(func() (func(), error) { return nil, errors.New("busy") })
	draft := Rule{Kind: "domain", Value: "example.com", Effect: "vpn", Enabled: true}
	mutation := Mutation{StateVersion: status.StateVersion, IdempotencyKey: "guard-mutation-01", Action: "create", Rule: &draft}
	if got := service.Mutate(context.Background(), mutation); got.Result != "rejected" {
		t.Fatalf("mutation=%+v", got)
	}
	if got := service.Replace(context.Background(), ReplaceRequest{StateVersion: status.StateVersion, IdempotencyKey: "guard-replace-01", Rules: status.Rules}); got.Result != "rejected" {
		t.Fatalf("replace=%+v", got)
	}
	if system.writes != 0 || system.restarts != 0 || system.validations != 0 {
		t.Fatalf("busy guard allowed writes=%d restarts=%d validations=%d", system.writes, system.restarts, system.validations)
	}
	released := 0
	service.SetMutationGuard(func() (func(), error) { return func() { released++ }, nil })
	if got := service.Mutate(context.Background(), mutation); got.Result != "committed" {
		t.Fatalf("unblocked mutation=%+v", got)
	}
	if released != 1 {
		t.Fatalf("guard releases=%d", released)
	}
}
