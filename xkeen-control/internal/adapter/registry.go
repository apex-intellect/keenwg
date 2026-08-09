package adapter

import (
	"context"
	"regexp"
	"sort"
	"sync"

	"github.com/goldb/keenwg/xkeen-control/internal/catalog"
)

var adapterIDPattern = regexp.MustCompile(`^[a-z][a-z0-9_-]{0,63}$`)

type Registry struct {
	mu        sync.RWMutex
	adapters  []Adapter
	byID      map[string]Adapter
	nodeOwner map[string]string
	versions  map[string]uint64
}

func NewRegistry(adapters ...Adapter) (*Registry, error) {
	if len(adapters) > MaxAdapters {
		return nil, ErrProjectionLimit
	}
	result := &Registry{
		adapters: append([]Adapter(nil), adapters...), byID: make(map[string]Adapter, len(adapters)),
		nodeOwner: make(map[string]string), versions: make(map[string]uint64),
	}
	for _, item := range result.adapters {
		if item == nil || !adapterIDPattern.MatchString(item.ID()) {
			return nil, ErrInvalidAdapter
		}
		if _, exists := result.byID[item.ID()]; exists {
			return nil, ErrDuplicateAdapter
		}
		result.byID[item.ID()] = item
	}
	sort.Slice(result.adapters, func(i, j int) bool { return result.adapters[i].ID() < result.adapters[j].ID() })
	return result, nil
}

func (r *Registry) AdapterIDs() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	result := make([]string, len(r.adapters))
	for index, item := range r.adapters {
		result[index] = item.ID()
	}
	return result
}

func (r *Registry) Snapshot(ctx context.Context) (RegistrySnapshot, error) {
	if err := ctx.Err(); err != nil {
		return RegistrySnapshot{}, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	result := RegistrySnapshot{
		Adapters:   []AdapterState{},
		Projection: Projection{AdapterID: "registry", Sources: []catalog.Source{}, Nodes: []catalog.Node{}},
	}
	nodeOwner := make(map[string]string)
	versions := make(map[string]uint64)
	sourceIDs := make(map[string]struct{})
	for _, item := range r.adapters {
		if err := ctx.Err(); err != nil {
			return RegistrySnapshot{}, err
		}
		discovery := item.Discover(ctx)
		state := AdapterState{ID: item.ID(), Discovery: discovery}
		if !discovery.Available {
			result.Adapters = append(result.Adapters, state)
			continue
		}
		projection, err := item.Snapshot(ctx)
		if err != nil {
			state.Discovery = Discovery{Available: false, Writable: false, Reason: "adapter_snapshot_failed"}
			result.Adapters = append(result.Adapters, state)
			continue
		}
		if err := validateProjection(item.ID(), projection); err != nil {
			return RegistrySnapshot{}, err
		}
		if len(result.Projection.Sources)+len(projection.Sources) > catalog.MaxSources ||
			len(result.Projection.Nodes)+len(projection.Nodes) > MaxProjectionNodes {
			return RegistrySnapshot{}, ErrProjectionLimit
		}
		for _, source := range projection.Sources {
			if _, exists := sourceIDs[source.ID]; exists {
				return RegistrySnapshot{}, ErrDuplicateSource
			}
			sourceIDs[source.ID] = struct{}{}
		}
		for _, node := range projection.Nodes {
			if _, exists := nodeOwner[node.ID]; exists {
				return RegistrySnapshot{}, ErrDuplicateNode
			}
			nodeOwner[node.ID] = item.ID()
		}
		state.StateVersion = projection.StateVersion
		versions[item.ID()] = projection.StateVersion
		result.Adapters = append(result.Adapters, state)
		result.Projection.Sources = append(result.Projection.Sources, projection.Sources...)
		result.Projection.Nodes = append(result.Projection.Nodes, projection.Nodes...)
	}
	r.nodeOwner = nodeOwner
	r.versions = versions
	return result, nil
}

func (r *Registry) Test(ctx context.Context, nodeID string) TestResult {
	adapter, _, err := r.adapterForNode(nodeID)
	if err != nil {
		return TestResult{NodeID: nodeID, ErrorCode: "node_not_found"}
	}
	return adapter.Test(ctx, nodeID)
}

func (r *Registry) SnapshotAdapter(ctx context.Context, adapterID string) (Projection, error) {
	r.mu.RLock()
	item := r.byID[adapterID]
	r.mu.RUnlock()
	if item == nil {
		return Projection{}, ErrUnavailable
	}
	discovery := item.Discover(ctx)
	if !discovery.Available {
		return Projection{}, ErrUnavailable
	}
	projection, err := item.Snapshot(ctx)
	if err != nil {
		return Projection{}, err
	}
	if err := validateProjection(adapterID, projection); err != nil {
		return Projection{}, err
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	nextOwners := make(map[string]string, len(r.nodeOwner)+len(projection.Nodes))
	for nodeID, owner := range r.nodeOwner {
		if owner == adapterID {
			continue
		}
		nextOwners[nodeID] = owner
	}
	for _, node := range projection.Nodes {
		if owner := nextOwners[node.ID]; owner != "" && owner != adapterID {
			return Projection{}, ErrDuplicateNode
		}
		nextOwners[node.ID] = adapterID
	}
	r.nodeOwner = nextOwners
	r.versions[adapterID] = projection.StateVersion
	return projection, nil
}

func (r *Registry) RefreshSource(ctx context.Context, adapterID, sourceID string) (OperationResult, Projection, error) {
	r.mu.RLock()
	item := r.byID[adapterID]
	r.mu.RUnlock()
	if item == nil {
		return OperationResult{Result: ResultRejected, ErrorCode: "adapter_unavailable"}, Projection{}, ErrUnavailable
	}
	result := OperationResult{Result: ResultCommitted}
	if refresher, ok := item.(SourceRefresher); ok {
		result = refresher.Refresh(ctx, sourceID)
		if result.Result != ResultCommitted {
			return result, Projection{}, nil
		}
	}
	projection, err := r.SnapshotAdapter(ctx, adapterID)
	return result, projection, err
}

func (r *Registry) PlanActivation(ctx context.Context, nodeID string, reviewed uint64) (ActivationPlan, error) {
	adapter, version, err := r.adapterForNode(nodeID)
	if err != nil {
		return ActivationPlan{}, err
	}
	if reviewed != version {
		return ActivationPlan{}, ErrStaleState
	}
	return adapter.PlanActivation(ctx, nodeID, reviewed)
}

func (r *Registry) Activate(ctx context.Context, plan ActivationPlan) OperationResult {
	r.mu.RLock()
	item := r.byID[plan.AdapterID]
	r.mu.RUnlock()
	if item == nil {
		return OperationResult{Result: ResultRejected, ErrorCode: "adapter_unavailable"}
	}
	return item.Activate(ctx, plan)
}

func (r *Registry) adapterForNode(nodeID string) (Adapter, uint64, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	owner := r.nodeOwner[nodeID]
	if owner == "" {
		return nil, 0, ErrNodeNotFound
	}
	item := r.byID[owner]
	if item == nil {
		return nil, 0, ErrUnavailable
	}
	return item, r.versions[owner], nil
}

func validateProjection(adapterID string, projection Projection) error {
	if len(projection.Sources) > catalog.MaxSources || len(projection.Nodes) > MaxProjectionNodes {
		return ErrProjectionLimit
	}
	if projection.AdapterID != adapterID || projection.StateVersion == 0 {
		return ErrProjection
	}
	sources := make(map[string]struct{}, len(projection.Sources))
	for _, source := range projection.Sources {
		if source.ID == "" || source.AdapterID != adapterID || source.AdapterStateVersion != projection.StateVersion {
			return ErrProjection
		}
		if _, exists := sources[source.ID]; exists {
			return ErrDuplicateSource
		}
		sources[source.ID] = struct{}{}
	}
	nodes := make(map[string]struct{}, len(projection.Nodes))
	for _, node := range projection.Nodes {
		if node.ID == "" {
			return ErrProjection
		}
		if _, exists := sources[node.SourceID]; !exists {
			return ErrProjection
		}
		if _, exists := nodes[node.ID]; exists {
			return ErrDuplicateNode
		}
		nodes[node.ID] = struct{}{}
	}
	return nil
}
