package transaction

import (
	"context"
	"errors"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/subscription"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/xray"
)

type runtimeEngineSystem struct {
	*fakeSystem
	entered chan struct{}
	proceed chan struct{}
	err     error
}

func (s *runtimeEngineSystem) RuntimeStatus(context.Context) (bool, error) { return true, s.err }
func (s *runtimeEngineSystem) SetRuntime(ctx context.Context, enabled bool) (bool, error) {
	if _, ok := ctx.Deadline(); !ok {
		return false, errors.New("missing deadline")
	}
	if s.entered != nil {
		close(s.entered)
		<-s.proceed
	}
	return enabled, s.err
}

func TestRuntimeSerializesBothDirectionsWithSelectionsAndRefresh(t *testing.T) {
	d := newEngineDeps(t)
	system := &runtimeEngineSystem{fakeSystem: d.system, entered: make(chan struct{}), proceed: make(chan struct{})}
	e := New(d.cfg, d.fetcher, subscription.Parse, d.store, system, d.clock)
	done := make(chan error, 1)
	go func() { _, err := e.SetRuntime(context.Background(), false); done <- err }()
	<-system.entered
	if _, _, err := e.PrepareRefresh("11111111-1111-4111-8111-111111111111", d.stateVersion); !errors.Is(err, ErrBusy) {
		t.Fatalf("refresh err=%v", err)
	}
	node := testNode()
	node.ID = "test-node"
	if _, _, err := e.PrepareSelectNode("22222222-2222-4222-8222-222222222222", node, d.stateVersion); !errors.Is(err, ErrBusy) {
		t.Fatalf("select err=%v", err)
	}
	if _, err := e.SetRuntime(context.Background(), true); !errors.Is(err, ErrBusy) {
		t.Fatalf("second runtime err=%v", err)
	}
	close(system.proceed)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	_, job, err := e.PrepareRefresh("33333333-3333-4333-8333-333333333333", d.stateVersion)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := e.SetRuntime(context.Background(), true); !errors.Is(err, ErrBusy) {
		t.Fatalf("runtime during refresh err=%v", err)
	}
	job(context.Background())
}

func TestRuntimeReleasesGuardOnFailureAndPendingUpdateFailsClosed(t *testing.T) {
	d := newEngineDeps(t)
	system := &runtimeEngineSystem{fakeSystem: d.system, err: xray.ErrRuntimeStop}
	e := New(d.cfg, d.fetcher, subscription.Parse, d.store, system, d.clock)
	if _, err := e.SetRuntime(context.Background(), false); !errors.Is(err, xray.ErrRuntimeStop) {
		t.Fatal(err)
	}
	release, err := e.AcquireExclusive()
	if err != nil {
		t.Fatal(err)
	}
	release()
	release()
	for _, probe := range []func() (bool, error){func() (bool, error) { return true, nil }, func() (bool, error) { return false, errors.New("read error") }} {
		e.SetExternalBusyProbe(probe)
		if _, err := e.AcquireExclusive(); !errors.Is(err, ErrBusy) {
			t.Fatalf("pending update err=%v", err)
		}
		if _, _, err := e.PrepareRefresh("11111111-1111-4111-8111-111111111111", d.stateVersion); !errors.Is(err, ErrBusy) {
			t.Fatalf("refresh pending err=%v", err)
		}
	}
}

func TestRuntimeIsUnavailableForLegacySystemWithoutRuntimeCapability(t *testing.T) {
	d := newEngineDeps(t)
	e := New(d.cfg, d.fetcher, subscription.Parse, d.store, d.system, d.clock)
	if _, err := e.SetRuntime(context.Background(), false); !errors.Is(err, xray.ErrRuntimeUnsupported) {
		t.Fatal(err)
	}
	if _, err := e.RuntimeStatus(context.Background()); !errors.Is(err, xray.ErrRuntimeUnsupported) {
		t.Fatal(err)
	}
}
