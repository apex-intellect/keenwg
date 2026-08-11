package diagnostics

import (
	"context"
	"errors"
	"net"
	"strconv"
	"sync"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/model"
)

const (
	StatusReachable   = "reachable"
	StatusUnreachable = "unreachable"
	StatusTimeout     = "timeout"
	StatusDNSError    = "dns_error"
)

type Resolver interface {
	LookupHost(context.Context, string) ([]string, error)
}

type Connector interface {
	Connect(context.Context, string) error
}

type NodeResult struct {
	NodeID     string `json:"node_id"`
	Host       string `json:"host"`
	Port       int    `json:"port"`
	ResolvedIP string `json:"resolved_ip,omitempty"`
	DNSMS      int64  `json:"dns_ms"`
	ConnectMS  int64  `json:"connect_ms"`
	Status     string `json:"status"`
}

type Report struct {
	SchemaVersion int          `json:"schema_version"`
	CheckedAt     int64        `json:"checked_at"`
	Results       []NodeResult `json:"results"`
}

type Service struct {
	resolver  Resolver
	connector Connector
	timeout   time.Duration
	workers   int
}

func New(resolver Resolver, connector Connector, timeout time.Duration, workers int) *Service {
	if workers < 1 {
		workers = 1
	}
	return &Service{resolver: resolver, connector: connector, timeout: timeout, workers: workers}
}

func NewDefault() *Service {
	return New(net.DefaultResolver, tcpConnector{dialer: &net.Dialer{}}, 3*time.Second, 4)
}

func (s *Service) Check(ctx context.Context, nodes []model.Node) Report {
	results := make([]NodeResult, len(nodes))
	jobs := make(chan int)
	var wg sync.WaitGroup
	count := s.workers
	if count > len(nodes) {
		count = len(nodes)
	}
	for worker := 0; worker < count; worker++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for index := range jobs {
				results[index] = s.checkNode(ctx, nodes[index])
			}
		}()
	}
	for index := range nodes {
		jobs <- index
	}
	close(jobs)
	wg.Wait()
	return Report{SchemaVersion: 1, CheckedAt: time.Now().Unix(), Results: results}
}

func (s *Service) checkNode(parent context.Context, node model.Node) NodeResult {
	result := NodeResult{NodeID: node.ID, Host: node.Host, Port: node.Port}
	started := time.Now()
	ips, err := s.lookupHost(parent, node.Host)
	result.DNSMS = elapsedMS(started)
	if err != nil || len(ips) == 0 {
		result.Status = StatusDNSError
		return result
	}
	result.ResolvedIP = preferredIP(ips)
	ctx, cancel := context.WithTimeout(parent, s.timeout)
	defer cancel()
	started = time.Now()
	err = s.connector.Connect(ctx, net.JoinHostPort(result.ResolvedIP, strconv.Itoa(node.Port)))
	result.ConnectMS = elapsedMS(started)
	switch {
	case err == nil:
		result.Status = StatusReachable
	case errors.Is(err, context.DeadlineExceeded) || errors.Is(ctx.Err(), context.DeadlineExceeded):
		result.Status = StatusTimeout
	default:
		result.Status = StatusUnreachable
	}
	return result
}

func (s *Service) lookupHost(parent context.Context, host string) ([]string, error) {
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		ctx, cancel := context.WithTimeout(parent, s.timeout)
		ips, err := s.resolver.LookupHost(ctx, host)
		timedOut := errors.Is(ctx.Err(), context.DeadlineExceeded)
		cancel()
		if err == nil || parent.Err() != nil || (!timedOut && !isTemporaryDNSError(err)) {
			return ips, err
		}
		lastErr = err
	}
	return nil, lastErr
}

func isTemporaryDNSError(err error) bool {
	if errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var dnsErr *net.DNSError
	return errors.As(err, &dnsErr) && (dnsErr.IsTimeout || dnsErr.IsTemporary)
}

func elapsedMS(start time.Time) int64 {
	value := time.Since(start).Milliseconds()
	if value < 0 {
		return 0
	}
	return value
}

func preferredIP(values []string) string {
	for _, value := range values {
		if parsed := net.ParseIP(value); parsed != nil && parsed.To4() != nil {
			return value
		}
	}
	return values[0]
}

type tcpConnector struct{ dialer *net.Dialer }

func (c tcpConnector) Connect(ctx context.Context, address string) error {
	connection, err := c.dialer.DialContext(ctx, "tcp", address)
	if err != nil {
		return err
	}
	return connection.Close()
}
