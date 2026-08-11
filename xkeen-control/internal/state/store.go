package state

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/model"
)

var (
	ErrStorage             = errors.New("storage_error")
	ErrInvalidState        = errors.New("invalid_state")
	ErrStaleState          = errors.New("stale_state")
	ErrOperationExists     = errors.New("operation_exists")
	ErrBusy                = errors.New("operation_busy")
	ErrAlreadyBootstrapped = errors.New("already_bootstrapped")
)

var (
	nodeIDPattern    = regexp.MustCompile(`^[0-9a-f]{32}$`)
	operationPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
)

type Paths struct {
	Subscription string
	State        string
	BackupDir    string
}

type Store struct {
	mu     sync.Mutex
	paths  Paths
	random io.Reader
	rename func(string, string) error
}

func New(paths Paths, random io.Reader) *Store {
	if random == nil {
		random = rand.Reader
	}
	return &Store{paths: paths, random: random, rename: os.Rename}
}

func (s *Store) SaveSubscription(nodes []model.Node, refreshedAt time.Time) (model.SubscriptionState, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	current, err := s.loadSubscriptionUnlocked()
	if err != nil {
		return model.SubscriptionState{}, err
	}
	ids := make(map[string]string, len(current.Nodes))
	for _, node := range current.Nodes {
		ids[node.CanonicalURI] = node.ID
	}
	nextNodes := make([]model.Node, len(nodes))
	seenCanonical := make(map[string]struct{}, len(nodes))
	seenIDs := make(map[string]struct{}, len(nodes))
	for i, node := range nodes {
		if node.CanonicalURI == "" {
			return model.SubscriptionState{}, ErrInvalidState
		}
		if _, exists := seenCanonical[node.CanonicalURI]; exists {
			return model.SubscriptionState{}, ErrInvalidState
		}
		seenCanonical[node.CanonicalURI] = struct{}{}
		if id := ids[node.CanonicalURI]; id != "" {
			node.ID = id
		} else {
			id, err := randomHex(s.random, 16)
			if err != nil {
				return model.SubscriptionState{}, err
			}
			node.ID = id
		}
		if _, exists := seenIDs[node.ID]; exists {
			return model.SubscriptionState{}, ErrInvalidState
		}
		seenIDs[node.ID] = struct{}{}
		nextNodes[i] = cloneNode(node)
	}
	next := model.SubscriptionState{RefreshedAt: refreshedAt.Unix(), Nodes: nextNodes}
	if err := validateSubscription(next); err != nil {
		return model.SubscriptionState{}, err
	}
	if err := s.writeJSONAtomic(s.paths.Subscription, next, 0o600); err != nil {
		return model.SubscriptionState{}, err
	}
	return cloneSubscription(next), nil
}

func (s *Store) LoadSubscription() (model.SubscriptionState, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	state, err := s.loadSubscriptionUnlocked()
	return cloneSubscription(state), err
}

func (s *Store) loadSubscriptionUnlocked() (model.SubscriptionState, error) {
	var state model.SubscriptionState
	found, err := readJSONStrict(s.paths.Subscription, &state)
	if err != nil {
		return model.SubscriptionState{}, err
	}
	if !found {
		return model.SubscriptionState{Nodes: []model.Node{}}, nil
	}
	if err := validateSubscription(state); err != nil {
		return model.SubscriptionState{}, err
	}
	return state, nil
}

func (s *Store) SaveControllerState(next model.ControllerState) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	current, err := s.loadControllerStateUnlocked()
	if err != nil {
		return err
	}
	if next.StateVersion < current.StateVersion {
		return ErrStaleState
	}
	if err := validateControllerState(next); err != nil {
		return err
	}
	return s.writeJSONAtomic(s.paths.State, normalizeControllerState(next), 0o600)
}

func (s *Store) LoadControllerState() (model.ControllerState, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.loadControllerStateUnlocked()
}

func (s *Store) loadControllerStateUnlocked() (model.ControllerState, error) {
	var state model.ControllerState
	found, err := readJSONStrict(s.paths.State, &state)
	if err != nil {
		return model.ControllerState{}, err
	}
	if !found {
		return model.ControllerState{Operations: []model.Operation{}}, nil
	}
	if err := validateControllerState(state); err != nil {
		return model.ControllerState{}, err
	}
	return normalizeControllerState(state), nil
}

func (s *Store) BeginOperation(operation model.Operation, snapshot *model.TransactionSnapshot) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	state, err := s.loadControllerStateUnlocked()
	if err != nil {
		return err
	}
	for _, existing := range state.Operations {
		if existing.IdempotencyKey == operation.IdempotencyKey {
			return ErrOperationExists
		}
	}
	if state.InProgress != nil {
		return ErrBusy
	}
	state.Operations = trimTerminalOperations(state.Operations, 99)
	state.Operations = append(state.Operations, operation)
	state.InProgress = cloneSnapshot(snapshot)
	if err := validateControllerState(state); err != nil {
		return err
	}
	return s.writeJSONAtomic(s.paths.State, normalizeControllerState(state), 0o600)
}

func (s *Store) UpdateOperation(operation model.Operation, snapshot *model.TransactionSnapshot) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	state, err := s.loadControllerStateUnlocked()
	if err != nil {
		return err
	}
	index := -1
	for i := range state.Operations {
		if state.Operations[i].IdempotencyKey == operation.IdempotencyKey {
			index = i
			break
		}
	}
	if index < 0 {
		return ErrInvalidState
	}
	state.Operations[index] = operation
	if operation.State == model.OperationTerminal {
		if state.InProgress != nil && state.InProgress.OperationKey == operation.IdempotencyKey {
			state.InProgress = nil
		}
		state.Operations = trimTerminalOperations(state.Operations, 100)
	} else {
		state.InProgress = cloneSnapshot(snapshot)
	}
	if err := validateControllerState(state); err != nil {
		return err
	}
	return s.writeJSONAtomic(s.paths.State, normalizeControllerState(state), 0o600)
}

func (s *Store) FindOperation(key string) (model.Operation, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	state, err := s.loadControllerStateUnlocked()
	if err != nil {
		return model.Operation{}, false, err
	}
	for i := len(state.Operations) - 1; i >= 0; i-- {
		if state.Operations[i].IdempotencyKey == key {
			return state.Operations[i], true, nil
		}
	}
	return model.Operation{}, false, nil
}

func (s *Store) writeJSONAtomic(target string, value any, mode os.FileMode) error {
	if target == "" {
		return ErrStorage
	}
	dir := filepath.Dir(target)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return storageError(err)
	}
	file, err := os.CreateTemp(dir, "."+filepath.Base(target)+".tmp-*")
	if err != nil {
		return storageError(err)
	}
	tempName := file.Name()
	closed := false
	defer func() {
		if !closed {
			_ = file.Close()
		}
		_ = os.Remove(tempName)
	}()
	if err := file.Chmod(mode); err != nil {
		return storageError(err)
	}
	encoder := json.NewEncoder(file)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(value); err != nil {
		return storageError(err)
	}
	if err := file.Sync(); err != nil {
		return storageError(err)
	}
	if err := file.Close(); err != nil {
		return storageError(err)
	}
	closed = true
	if err := s.rename(tempName, target); err != nil {
		return storageError(err)
	}
	if err := os.Chmod(target, mode); err != nil {
		return storageError(err)
	}
	if directory, err := os.Open(dir); err == nil {
		_ = directory.Sync()
		_ = directory.Close()
	}
	return nil
}

func readJSONStrict(path string, destination any) (bool, error) {
	file, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, storageError(err)
	}
	defer file.Close()
	decoder := json.NewDecoder(io.LimitReader(file, 8<<20))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		return false, ErrInvalidState
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return false, ErrInvalidState
	}
	return true, nil
}

func validateSubscription(state model.SubscriptionState) error {
	if state.RefreshedAt < 0 {
		return ErrInvalidState
	}
	seenIDs := make(map[string]struct{}, len(state.Nodes))
	seenCanonical := make(map[string]struct{}, len(state.Nodes))
	for _, node := range state.Nodes {
		if !nodeIDPattern.MatchString(node.ID) || node.CanonicalURI == "" {
			return ErrInvalidState
		}
		if _, exists := seenIDs[node.ID]; exists {
			return ErrInvalidState
		}
		if _, exists := seenCanonical[node.CanonicalURI]; exists {
			return ErrInvalidState
		}
		seenIDs[node.ID] = struct{}{}
		seenCanonical[node.CanonicalURI] = struct{}{}
	}
	return nil
}

func validateControllerState(state model.ControllerState) error {
	if len(state.Operations) > 100 {
		return ErrInvalidState
	}
	seen := make(map[string]struct{}, len(state.Operations))
	nonTerminal := ""
	for _, operation := range state.Operations {
		if !operationPattern.MatchString(operation.IdempotencyKey) {
			return ErrInvalidState
		}
		if _, exists := seen[operation.IdempotencyKey]; exists {
			return ErrInvalidState
		}
		seen[operation.IdempotencyKey] = struct{}{}
		switch operation.State {
		case model.OperationQueued, model.OperationRunning:
			if nonTerminal != "" || operation.FinishedAt != nil || operation.Result != "" {
				return ErrInvalidState
			}
			nonTerminal = operation.IdempotencyKey
		case model.OperationTerminal:
			if operation.FinishedAt == nil || !validResult(operation.Result) {
				return ErrInvalidState
			}
		default:
			return ErrInvalidState
		}
	}
	if nonTerminal == "" {
		if state.InProgress != nil {
			return ErrInvalidState
		}
		return nil
	}
	if state.InProgress == nil || state.InProgress.OperationKey != nonTerminal || state.InProgress.Kind == "" || state.InProgress.Phase == "" {
		return ErrInvalidState
	}
	return nil
}

func validResult(result model.OperationResult) bool {
	switch result {
	case model.ResultSuccess, model.ResultFailedRolledBack, model.ResultFailedNoChange, model.ResultUncertain:
		return true
	default:
		return false
	}
}

func randomHex(reader io.Reader, bytesCount int) (string, error) {
	buffer := make([]byte, bytesCount)
	if _, err := io.ReadFull(reader, buffer); err != nil {
		return "", storageError(err)
	}
	return hex.EncodeToString(buffer), nil
}

func storageError(error) error {
	return fmt.Errorf("%w", ErrStorage)
}

func cloneNode(node model.Node) model.Node {
	node.Warnings = append([]string(nil), node.Warnings...)
	return node
}

func cloneSubscription(state model.SubscriptionState) model.SubscriptionState {
	cloned := model.SubscriptionState{RefreshedAt: state.RefreshedAt, Nodes: make([]model.Node, len(state.Nodes))}
	for i, node := range state.Nodes {
		cloned.Nodes[i] = cloneNode(node)
	}
	return cloned
}

func cloneSnapshot(snapshot *model.TransactionSnapshot) *model.TransactionSnapshot {
	if snapshot == nil {
		return nil
	}
	cloned := *snapshot
	cloned.OriginalOutbounds = append([]byte(nil), snapshot.OriginalOutbounds...)
	cloned.OriginalExcludes = append([]byte(nil), snapshot.OriginalExcludes...)
	if snapshot.OriginalActive != nil {
		active := *snapshot.OriginalActive
		active.Warnings = append([]string(nil), snapshot.OriginalActive.Warnings...)
		cloned.OriginalActive = &active
	}
	return &cloned
}

func normalizeControllerState(state model.ControllerState) model.ControllerState {
	if state.Operations == nil {
		state.Operations = []model.Operation{}
	}
	state.InProgress = cloneSnapshot(state.InProgress)
	return state
}

func trimTerminalOperations(operations []model.Operation, limit int) []model.Operation {
	if len(operations) <= limit {
		return operations
	}
	remove := len(operations) - limit
	trimmed := make([]model.Operation, 0, limit)
	for _, operation := range operations {
		if remove > 0 && operation.State == model.OperationTerminal {
			remove--
			continue
		}
		trimmed = append(trimmed, operation)
	}
	return trimmed
}
