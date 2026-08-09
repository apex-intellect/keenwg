package adapter

import (
	"context"
	"errors"
	"time"

	"github.com/goldb/keenwg/xkeen-control/internal/catalog"
)

const (
	MaxAdapters        = 16
	MaxProjectionNodes = catalog.MaxNodes
)

var (
	ErrInvalidAdapter    = errors.New("adapter_invalid")
	ErrDuplicateAdapter  = errors.New("adapter_duplicate")
	ErrDuplicateSource   = errors.New("adapter_duplicate_source")
	ErrDuplicateNode     = errors.New("adapter_duplicate_node")
	ErrProjectionLimit   = errors.New("adapter_projection_limit")
	ErrProjection        = errors.New("adapter_projection_invalid")
	ErrStaleState        = errors.New("adapter_stale_state")
	ErrNodeNotFound      = errors.New("adapter_node_not_found")
	ErrUnavailable       = errors.New("adapter_unavailable")
	ErrUnsupportedSchema = errors.New("adapter_unsupported_schema")
)

const (
	ResultCommitted  = "committed"
	ResultRejected   = "rejected"
	ResultRolledBack = "rolled_back"
	ResultUncertain  = "uncertain"
)

type Discovery struct {
	Available bool   `json:"available"`
	Writable  bool   `json:"writable"`
	Reason    string `json:"reason,omitempty"`
}

type Projection struct {
	AdapterID    string           `json:"adapter_id"`
	StateVersion uint64           `json:"state_version"`
	Sources      []catalog.Source `json:"sources"`
	Nodes        []catalog.Node   `json:"nodes"`
}

type TestResult struct {
	NodeID     string    `json:"node_id"`
	Reachable  bool      `json:"reachable"`
	LatencyMS  int64     `json:"latency_ms,omitempty"`
	ErrorCode  string    `json:"error,omitempty"`
	ObservedAt time.Time `json:"observed_at"`
}

type ActivationPlan struct {
	AdapterID            string `json:"adapter_id"`
	NodeID               string `json:"node_id"`
	ReviewedStateVersion uint64 `json:"reviewed_state_version"`
	PreviousNodeID       string `json:"previous_node_id,omitempty"`
	Opaque               string `json:"-"`
}

type OperationResult struct {
	Result    string `json:"result"`
	ErrorCode string `json:"error,omitempty"`
	NodeID    string `json:"node_id,omitempty"`
}

type Adapter interface {
	ID() string
	Discover(context.Context) Discovery
	Snapshot(context.Context) (Projection, error)
	Test(context.Context, string) TestResult
	PlanActivation(context.Context, string, uint64) (ActivationPlan, error)
	Activate(context.Context, ActivationPlan) OperationResult
}

type SourceRefresher interface {
	Refresh(context.Context, string) OperationResult
}

type AdapterState struct {
	ID           string    `json:"id"`
	Discovery    Discovery `json:"discovery"`
	StateVersion uint64    `json:"state_version,omitempty"`
}

type RegistrySnapshot struct {
	Adapters   []AdapterState `json:"adapters"`
	Projection Projection     `json:"projection"`
}
