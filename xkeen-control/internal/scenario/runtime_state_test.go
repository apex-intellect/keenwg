package scenario

import (
	"context"
	"errors"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/domainpolicy"
)

func TestRouteRuntimeStateExposesOnlyActuallyWritableModules(t *testing.T) {
	provider := NewRouteStateProvider(&fakeDomainReplaceManager{status: domainpolicy.NewStatus(17, nil, nil, nil)})
	state, err := provider.Current(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if state.StateVersion != 17 || !state.Modules.Domains || !state.Modules.IP || state.Modules.Devices || state.Modules.Services || state.ModuleVersions["routes"] != 17 {
		t.Fatalf("state=%+v", state)
	}
}

func TestRouteRuntimeStateRejectsWarningsAndUnavailablePolicy(t *testing.T) {
	for _, manager := range []DomainReplaceManager{&fakeDomainReplaceManager{status: domainpolicy.NewStatus(17, nil, nil, []string{"projection_mismatch"})}, failingDomainManager{}} {
		if _, err := NewRouteStateProvider(manager).Current(context.Background()); !errors.Is(err, ErrRuntimeStateUnavailable) {
			t.Fatalf("err=%v", err)
		}
	}
}

type failingDomainManager struct{}

func (failingDomainManager) Status(context.Context) (domainpolicy.Status, error) {
	return domainpolicy.Status{}, errors.New("offline")
}
func (failingDomainManager) Replace(context.Context, domainpolicy.ReplaceRequest) domainpolicy.Result {
	return domainpolicy.Result{}
}
