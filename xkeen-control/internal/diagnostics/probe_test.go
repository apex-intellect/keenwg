package diagnostics

import (
	"context"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/goldb/keenwg/xkeen-control/internal/model"
)

type transientResolver struct{ calls atomic.Int32 }

func (r *transientResolver) LookupHost(_ context.Context, _ string) ([]string, error) {
	if r.calls.Add(1) == 1 {
		return nil, &net.DNSError{Err: "temporary resolver failure", IsTemporary: true}
	}
	return []string{"192.0.2.10"}, nil
}

type deadlineResolver struct{ calls atomic.Int32 }

func (r *deadlineResolver) LookupHost(ctx context.Context, _ string) ([]string, error) {
	if r.calls.Add(1) == 1 {
		<-ctx.Done()
		return nil, &net.DNSError{Err: "resolver timeout", IsTimeout: true, IsTemporary: true}
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return []string{"192.0.2.11"}, nil
}

type fakeResolver struct {
	ips map[string][]string
	err map[string]error
}

func (f fakeResolver) LookupHost(_ context.Context, host string) ([]string, error) {
	return f.ips[host], f.err[host]
}

type fakeConnector struct {
	mu        sync.Mutex
	err       map[string]error
	active    int
	maxActive int
}

func (f *fakeConnector) Connect(_ context.Context, address string) error {
	f.mu.Lock()
	f.active++
	if f.active > f.maxActive {
		f.maxActive = f.active
	}
	f.mu.Unlock()
	time.Sleep(2 * time.Millisecond)
	f.mu.Lock()
	f.active--
	f.mu.Unlock()
	return f.err[address]
}

func TestCheckPreservesOrderAndSanitizesFailures(t *testing.T) {
	connector := &fakeConnector{err: map[string]error{"192.0.2.2:443": context.DeadlineExceeded}}
	service := New(fakeResolver{
		ips: map[string][]string{"nl.example": {"192.0.2.1"}, "de.example": {"192.0.2.2"}},
		err: map[string]error{"bad.example": errors.New("resolver leaked secret")},
	}, connector, time.Second, 2)
	nodes := []model.Node{
		{ID: "nl", Host: "nl.example", Port: 443},
		{ID: "bad", Host: "bad.example", Port: 443},
		{ID: "de", Host: "de.example", Port: 443},
	}
	report := service.Check(context.Background(), nodes)
	if report.SchemaVersion != 1 || len(report.Results) != 3 {
		t.Fatalf("unexpected report: %#v", report)
	}
	if report.Results[0].NodeID != "nl" || report.Results[0].Status != StatusReachable {
		t.Fatalf("first result: %#v", report.Results[0])
	}
	if report.Results[1].NodeID != "bad" || report.Results[1].Status != StatusDNSError {
		t.Fatalf("dns result: %#v", report.Results[1])
	}
	if report.Results[2].NodeID != "de" || report.Results[2].Status != StatusTimeout {
		t.Fatalf("timeout result: %#v", report.Results[2])
	}
	if report.Results[1].ResolvedIP != "" {
		t.Fatalf("resolver detail leaked: %#v", report.Results[1])
	}
}

func TestCheckBoundsConcurrency(t *testing.T) {
	connector := &fakeConnector{err: map[string]error{}}
	resolver := fakeResolver{ips: map[string][]string{}, err: map[string]error{}}
	nodes := make([]model.Node, 12)
	for i := range nodes {
		nodes[i] = model.Node{ID: string(rune('a' + i)), Host: string(rune('a'+i)) + ".example", Port: 443}
		resolver.ips[nodes[i].Host] = []string{"192.0.2.1"}
	}
	service := New(resolver, connector, time.Second, 4)
	service.Check(context.Background(), nodes)
	if connector.maxActive > 4 {
		t.Fatalf("max concurrency = %d", connector.maxActive)
	}
}

func TestCheckRetriesTemporaryDNSError(t *testing.T) {
	resolver := &transientResolver{}
	service := New(resolver, &fakeConnector{err: map[string]error{}}, time.Second, 1)

	report := service.Check(context.Background(), []model.Node{{ID: "de", Host: "de.example", Port: 443}})

	if got := report.Results[0]; got.Status != StatusReachable || got.ResolvedIP != "192.0.2.10" {
		t.Fatalf("transient DNS result: %#v", got)
	}
	if calls := resolver.calls.Load(); calls != 2 {
		t.Fatalf("resolver calls = %d, want 2", calls)
	}
}

func TestCheckRetriesDNSTimeoutWithFreshDeadline(t *testing.T) {
	resolver := &deadlineResolver{}
	service := New(resolver, &fakeConnector{err: map[string]error{}}, 5*time.Millisecond, 1)

	report := service.Check(context.Background(), []model.Node{{ID: "nl", Host: "nl.example", Port: 443}})

	if got := report.Results[0]; got.Status != StatusReachable || got.ResolvedIP != "192.0.2.11" {
		t.Fatalf("DNS timeout retry result: %#v", got)
	}
	if calls := resolver.calls.Load(); calls != 2 {
		t.Fatalf("resolver calls = %d, want 2", calls)
	}
}
