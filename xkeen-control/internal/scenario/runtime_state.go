package scenario

import (
	"context"
	"errors"
)

var ErrRuntimeStateUnavailable = errors.New("scenario_runtime_state_unavailable")

type RouteStateProvider struct{ manager DomainReplaceManager }

func NewRouteStateProvider(manager DomainReplaceManager) *RouteStateProvider {
	return &RouteStateProvider{manager: manager}
}
func (p *RouteStateProvider) Current(ctx context.Context) (RuntimeState, error) {
	if p == nil || p.manager == nil {
		return RuntimeState{}, ErrRuntimeStateUnavailable
	}
	status, err := p.manager.Status(ctx)
	if err != nil || status.SchemaVersion != 1 || status.StateVersion == 0 || len(status.Warnings) > 0 {
		return RuntimeState{}, ErrRuntimeStateUnavailable
	}
	return RuntimeState{StateVersion: status.StateVersion, Modules: Modules{Domains: true, IP: true}, ModuleVersions: map[string]uint64{"routes": status.StateVersion}}, nil
}
