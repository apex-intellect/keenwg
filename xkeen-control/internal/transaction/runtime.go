package transaction

import (
	"context"
	"sync"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/xray"
)

// AcquireExclusive shares the node-selection/refresh guard with runtime and
// whole domain-policy transactions. It does not mutate persisted selections.
func (e *Engine) AcquireExclusive() (func(), error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.activeKey != "" || e.externalMutationBusy() {
		return nil, ErrBusy
	}
	e.activeKey = "runtime-or-domain-mutation"
	var once sync.Once
	return func() { once.Do(func() { e.clearActive("runtime-or-domain-mutation") }) }, nil
}

// SetExternalBusyProbe is wired before serving requests. Unknown pending-update
// state fails closed so a detached self-update cannot race router mutations.
func (e *Engine) SetExternalBusyProbe(probe func() (bool, error)) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.externalBusy = probe
}

func (e *Engine) externalMutationBusy() bool {
	if e.externalBusy == nil {
		return false
	}
	busy, err := e.externalBusy()
	return busy || err != nil
}

func (e *Engine) RuntimeStatus(ctx context.Context) (bool, error) {
	system, ok := e.system.(xray.RuntimeSystem)
	if !ok {
		return false, xray.ErrRuntimeUnsupported
	}
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return system.RuntimeStatus(ctx)
}

func (e *Engine) SetRuntime(ctx context.Context, enabled bool) (bool, error) {
	system, ok := e.system.(xray.RuntimeSystem)
	if !ok {
		return false, xray.ErrRuntimeUnsupported
	}
	release, err := e.AcquireExclusive()
	if err != nil {
		return false, err
	}
	defer release()
	ctx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	return system.SetRuntime(ctx, enabled)
}
