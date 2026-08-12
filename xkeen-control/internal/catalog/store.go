package catalog

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	MaxSourceSecretBytes = 1 << 20
	maxCatalogFileBytes  = 4 << 20
	maxOperations        = 128
)

var (
	ErrStorage           = errors.New("catalog_storage")
	ErrCorrupt           = errors.New("catalog_corrupt")
	ErrStaleState        = errors.New("catalog_stale_state")
	ErrOperationConflict = errors.New("catalog_operation_conflict")
	ErrInvalid           = errors.New("catalog_invalid")
	ErrLimit             = errors.New("catalog_limit")
	ErrNotFound          = errors.New("catalog_not_found")
)

type Paths struct {
	Catalog string
	Secrets string
}

type SourceDraft struct {
	GroupID   string
	Kind      SourceKind
	Label     string
	AdapterID string
	Headers   map[string]string
}

type sourceSecret struct {
	SourceID   string            `json:"source_id"`
	Kind       SourceKind        `json:"kind"`
	Raw        string            `json:"raw"`
	Projection string            `json:"projection,omitempty"`
	Headers    map[string]string `json:"headers"`
}

type secretDocument struct {
	SchemaVersion int            `json:"schema_version"`
	Sources       []sourceSecret `json:"sources"`
}

type operationRecord struct {
	Key          string          `json:"key"`
	RequestHash  string          `json:"request_hash"`
	StateVersion uint64          `json:"state_version"`
	Result       *RecordedResult `json:"result,omitempty"`
}

type RecordedResult struct {
	Kind         string `json:"kind"`
	Result       string `json:"result"`
	ErrorCode    string `json:"error,omitempty"`
	NodeID       string `json:"node_id,omitempty"`
	Reachable    bool   `json:"reachable,omitempty"`
	LatencyMS    int64  `json:"latency_ms,omitempty"`
	ObservedUnix int64  `json:"observed_unix,omitempty"`
}

type catalogDocument struct {
	SchemaVersion int               `json:"schema_version"`
	StateVersion  uint64            `json:"state_version"`
	Groups        []Group           `json:"groups"`
	Sources       []Source          `json:"sources"`
	Nodes         []Node            `json:"nodes"`
	Operations    []operationRecord `json:"operations"`
}

type Store struct {
	mu       sync.Mutex
	paths    Paths
	document catalogDocument
	secrets  secretDocument
	random   io.Reader
	now      func() time.Time
	rename   func(string, string) error
}

var operationKey = regexp.MustCompile(`^[A-Za-z0-9_-]{8,128}$`)

func NewStore(paths Paths, randomSource io.Reader) (*Store, error) {
	if paths.Catalog == "" || paths.Secrets == "" || paths.Catalog == paths.Secrets {
		return nil, ErrStorage
	}
	if randomSource == nil {
		randomSource = rand.Reader
	}
	store := &Store{paths: paths, random: randomSource, now: time.Now, rename: os.Rename}
	publicFound, err := readStrict(paths.Catalog, &store.document)
	if err != nil {
		return nil, err
	}
	privateFound, err := readStrict(paths.Secrets, &store.secrets)
	if err != nil {
		return nil, err
	}
	if publicFound != privateFound {
		return nil, ErrCorrupt
	}
	if !publicFound {
		store.document = catalogDocument{
			SchemaVersion: SchemaVersion,
			StateVersion:  1,
			Groups:        []Group{{ID: "primary", Label: "Primary", Order: 0}},
			Sources:       []Source{},
			Nodes:         []Node{},
			Operations:    []operationRecord{},
		}
		store.secrets = secretDocument{SchemaVersion: SchemaVersion, Sources: []sourceSecret{}}
		if err := store.persist(store.document, store.secrets); err != nil {
			return nil, err
		}
	}
	if err := validateStored(store.document, store.secrets); err != nil {
		return nil, err
	}
	return store, nil
}

func (s *Store) Snapshot(ctx context.Context) (Document, error) {
	if err := ctx.Err(); err != nil {
		return Document{}, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return publicDocument(s.document), nil
}

func (s *Store) CreateGroup(ctx context.Context, reviewed uint64, key, label string) (Document, error) {
	return s.mutate(ctx, reviewed, key, requestDigest("create_group", label), func(next *catalogDocument, _ *secretDocument) error {
		if !validLabel(label) {
			return ErrInvalid
		}
		if len(next.Groups) >= MaxGroups {
			return ErrLimit
		}
		id, err := s.uniqueID(groupIDs(next.Groups))
		if err != nil {
			return err
		}
		next.Groups = append(next.Groups, Group{ID: id, Label: strings.TrimSpace(label), Order: len(next.Groups)})
		return nil
	})
}

func (s *Store) CreateSource(
	ctx context.Context,
	reviewed uint64,
	key string,
	draft SourceDraft,
	secret []byte,
) (Document, error) {
	defer secretZero(secret)
	if len(secret) == 0 || len(secret) > MaxSourceSecretBytes {
		return Document{}, ErrLimit
	}
	if !utf8.Valid(secret) || bytes.IndexByte(secret, 0) >= 0 {
		return Document{}, ErrInvalid
	}
	secretHash := sha256.Sum256(secret)
	digest := requestDigest("create_source", draft.GroupID, string(draft.Kind), draft.Label, draft.AdapterID, hex.EncodeToString(secretHash[:]))
	return s.mutate(ctx, reviewed, key, digest, func(next *catalogDocument, private *secretDocument) error {
		if len(next.Sources) >= MaxSources || !validSourceDraft(draft) {
			return ErrLimit
		}
		if !containsGroup(next.Groups, draft.GroupID) {
			return ErrNotFound
		}
		id, err := s.uniqueID(sourceIDs(next.Sources))
		if err != nil {
			return err
		}
		next.Sources = append(next.Sources, Source{
			ID: id, GroupID: draft.GroupID, Kind: draft.Kind, Label: strings.TrimSpace(draft.Label),
			AdapterID: draft.AdapterID, Status: SourceStale, Warnings: []string{},
		})
		private.Sources = append(private.Sources, sourceSecret{
			SourceID: id, Kind: draft.Kind, Raw: string(secret), Headers: cloneHeaders(draft.Headers),
		})
		return nil
	})
}

func (s *Store) DeleteSource(ctx context.Context, reviewed uint64, key, sourceID string) (Document, error) {
	return s.mutate(ctx, reviewed, key, requestDigest("delete_source", sourceID), func(next *catalogDocument, private *secretDocument) error {
		index := indexSource(next.Sources, sourceID)
		if index < 0 {
			return ErrNotFound
		}
		next.Sources = append(next.Sources[:index], next.Sources[index+1:]...)
		next.Nodes = filterNodes(next.Nodes, sourceID)
		private.Sources = filterSecrets(private.Sources, sourceID)
		return nil
	})
}

func (s *Store) ReplaceProjection(
	ctx context.Context,
	reviewed uint64,
	key, sourceID string,
	nodes []Node,
) (Document, error) {
	digestBody, err := json.Marshal(nodes)
	if err != nil {
		return Document{}, ErrInvalid
	}
	digest := requestDigest("replace_projection", sourceID, string(digestBody))
	return s.mutate(ctx, reviewed, key, digest, func(next *catalogDocument, _ *secretDocument) error {
		index := indexSource(next.Sources, sourceID)
		if index < 0 {
			return ErrNotFound
		}
		if len(next.Nodes)-countNodes(next.Nodes, sourceID)+len(nodes) > MaxNodes {
			return ErrLimit
		}
		existing := make(map[string]string)
		for _, node := range next.Nodes {
			if node.SourceID == sourceID {
				existing[NodeIdentity(node)] = node.ID
			}
		}
		projected := make([]Node, len(nodes))
		used := nodeIDs(filterNodes(next.Nodes, sourceID))
		for i, node := range nodes {
			node.SourceID = sourceID
			node.GroupID = next.Sources[index].GroupID
			identity := NodeIdentity(node)
			if identity == "" {
				return ErrInvalid
			}
			if id := existing[identity]; id != "" {
				node.ID = id
			} else if node.ID == "" {
				id, err := s.uniqueID(used)
				if err != nil {
					return err
				}
				node.ID = id
				used[id] = struct{}{}
			}
			projected[i] = node
		}
		next.Nodes = append(filterNodes(next.Nodes, sourceID), projected...)
		next.Sources[index].NodeCount = len(projected)
		next.Sources[index].Status = SourceReady
		refreshed := s.now().UTC()
		next.Sources[index].LastRefresh = &refreshed
		return nil
	})
}

func (s *Store) SourceSecret(ctx context.Context, sourceID string) ([]byte, map[string]string, error) {
	if err := ctx.Err(); err != nil {
		return nil, nil, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, secret := range s.secrets.Sources {
		if secret.SourceID == sourceID {
			return []byte(secret.Raw), cloneHeaders(secret.Headers), nil
		}
	}
	return nil, nil, ErrNotFound
}

func (s *Store) SourceProjection(ctx context.Context, sourceID string) ([]byte, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, secret := range s.secrets.Sources {
		if secret.SourceID == sourceID {
			if secret.Projection == "" {
				return nil, ErrNotFound
			}
			return []byte(secret.Projection), nil
		}
	}
	return nil, ErrNotFound
}

func (s *Store) ReplaceOwnedProjection(
	ctx context.Context,
	reviewed uint64,
	key, digest, sourceID string,
	nodes []Node,
	payload []byte,
	result RecordedResult,
) (Document, error) {
	defer secretZero(payload)
	if len(payload) == 0 || len(payload) > MaxSourceSecretBytes || !utf8.Valid(payload) || bytes.IndexByte(payload, 0) >= 0 ||
		len(digest) != 64 || !validRecordedResult(result) {
		return Document{}, ErrInvalid
	}
	return s.mutateRecorded(ctx, reviewed, key, digest, &result, func(next *catalogDocument, private *secretDocument) error {
		index := indexSource(next.Sources, sourceID)
		if index < 0 || next.Sources[index].Foreign || next.Sources[index].AdapterID != "catalog" {
			return ErrNotFound
		}
		if len(next.Nodes)-countNodes(next.Nodes, sourceID)+len(nodes) > MaxNodes {
			return ErrLimit
		}
		projected := cloneNodes(nodes)
		used := nodeIDs(filterNodes(next.Nodes, sourceID))
		for nodeIndex := range projected {
			projected[nodeIndex].SourceID = sourceID
			projected[nodeIndex].GroupID = next.Sources[index].GroupID
			if projected[nodeIndex].ID == "" || NodeIdentity(projected[nodeIndex]) == "" {
				return ErrInvalid
			}
			if _, exists := used[projected[nodeIndex].ID]; exists {
				return ErrInvalid
			}
			used[projected[nodeIndex].ID] = struct{}{}
		}
		secretIndex := -1
		for candidate := range private.Sources {
			if private.Sources[candidate].SourceID == sourceID {
				secretIndex = candidate
				break
			}
		}
		if secretIndex < 0 {
			return ErrCorrupt
		}
		private.Sources[secretIndex].Projection = string(payload)
		next.Nodes = append(filterNodes(next.Nodes, sourceID), projected...)
		next.Sources[index].NodeCount = len(projected)
		next.Sources[index].Status = SourceReady
		refreshed := s.now().UTC()
		next.Sources[index].LastRefresh = &refreshed
		return nil
	})
}

func (s *Store) CommitOwnedActivation(
	ctx context.Context,
	reviewed uint64,
	key, digest, nodeID string,
	result RecordedResult,
) (Document, error) {
	if len(digest) != 64 || !validRecordedResult(result) {
		return Document{}, ErrInvalid
	}
	return s.mutateRecorded(ctx, reviewed, key, digest, &result, func(next *catalogDocument, _ *secretDocument) error {
		sourceAdapters := make(map[string]string, len(next.Sources))
		for _, source := range next.Sources {
			sourceAdapters[source.ID] = source.AdapterID
		}
		targetFound := false
		for index := range next.Nodes {
			adapterID := sourceAdapters[next.Nodes[index].SourceID]
			if next.Nodes[index].ID == nodeID {
				if adapterID != "catalog" {
					return ErrInvalid
				}
				targetFound = true
			}
			if adapterID == "catalog" || adapterID == "xkeen" {
				next.Nodes[index].Active = next.Nodes[index].ID == nodeID
			}
		}
		if !targetFound {
			return ErrNotFound
		}
		return nil
	})
}

func (s *Store) ReplaceAdapterProjection(
	ctx context.Context,
	reviewed uint64,
	key, digest, adapterID string,
	sources []Source,
	nodes []Node,
	result RecordedResult,
) (Document, error) {
	if !opaqueID.MatchString(adapterID) || !validRecordedResult(result) || len(digest) != 64 {
		return Document{}, ErrInvalid
	}
	return s.mutateRecorded(ctx, reviewed, key, digest, &result, func(next *catalogDocument, _ *secretDocument) error {
		replacedSources := make(map[string]struct{})
		keptSources := make([]Source, 0, len(next.Sources))
		for _, source := range next.Sources {
			if source.Foreign && source.AdapterID == adapterID {
				replacedSources[source.ID] = struct{}{}
				continue
			}
			keptSources = append(keptSources, source)
		}
		keptNodes := make([]Node, 0, len(next.Nodes))
		for _, node := range next.Nodes {
			if _, replaced := replacedSources[node.SourceID]; !replaced {
				keptNodes = append(keptNodes, node)
			}
		}
		if len(keptSources)+len(sources) > MaxSources || len(keptNodes)+len(nodes) > MaxNodes {
			return ErrLimit
		}
		for _, source := range sources {
			if !source.Foreign || source.AdapterID != adapterID || source.AdapterStateVersion == 0 {
				return ErrInvalid
			}
			for _, existing := range keptSources {
				if existing.ID == source.ID {
					return ErrInvalid
				}
			}
		}
		next.Sources = append(keptSources, cloneSources(sources)...)
		next.Nodes = append(keptNodes, cloneNodes(nodes)...)
		return nil
	})
}

func (s *Store) RecordResult(
	ctx context.Context,
	reviewed uint64,
	key, digest string,
	result RecordedResult,
) (Document, error) {
	if err := ctx.Err(); err != nil {
		return Document{}, err
	}
	if !operationKey.MatchString(key) || len(digest) != 64 || !validRecordedResult(result) {
		return Document{}, ErrInvalid
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if operation, exists := findOperation(s.document.Operations, key); exists {
		if operation.RequestHash != digest {
			return Document{}, ErrOperationConflict
		}
		return publicDocument(s.document), nil
	}
	if reviewed != s.document.StateVersion {
		return Document{}, ErrStaleState
	}
	next := cloneCatalog(s.document)
	copyResult := result
	next.Operations = append(next.Operations, operationRecord{
		Key: key, RequestHash: digest, StateVersion: next.StateVersion, Result: &copyResult,
	})
	trimOperations(&next)
	if err := validateStored(next, s.secrets); err != nil {
		return Document{}, err
	}
	if err := s.persist(next, s.secrets); err != nil {
		return Document{}, err
	}
	s.document = next
	return publicDocument(next), nil
}

func (s *Store) LookupResult(ctx context.Context, key, digest string) (RecordedResult, bool, error) {
	if err := ctx.Err(); err != nil {
		return RecordedResult{}, false, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	operation, exists := findOperation(s.document.Operations, key)
	if !exists {
		return RecordedResult{}, false, nil
	}
	if operation.RequestHash != digest {
		return RecordedResult{}, false, ErrOperationConflict
	}
	if operation.Result == nil {
		return RecordedResult{}, false, nil
	}
	return *operation.Result, true, nil
}

func (s *Store) mutate(
	ctx context.Context,
	reviewed uint64,
	key, digest string,
	change func(*catalogDocument, *secretDocument) error,
) (Document, error) {
	return s.mutateRecorded(ctx, reviewed, key, digest, nil, change)
}

func (s *Store) mutateRecorded(
	ctx context.Context,
	reviewed uint64,
	key, digest string,
	result *RecordedResult,
	change func(*catalogDocument, *secretDocument) error,
) (Document, error) {
	if err := ctx.Err(); err != nil {
		return Document{}, err
	}
	if !operationKey.MatchString(key) {
		return Document{}, ErrInvalid
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if operation, exists := findOperation(s.document.Operations, key); exists {
		if operation.RequestHash != digest {
			return Document{}, ErrOperationConflict
		}
		return publicDocument(s.document), nil
	}
	if reviewed != s.document.StateVersion {
		return Document{}, ErrStaleState
	}
	next := cloneCatalog(s.document)
	private := cloneSecretDocument(s.secrets)
	if err := change(&next, &private); err != nil {
		return Document{}, err
	}
	next.StateVersion++
	var copiedResult *RecordedResult
	if result != nil {
		copyValue := *result
		copiedResult = &copyValue
	}
	next.Operations = append(next.Operations, operationRecord{Key: key, RequestHash: digest, StateVersion: next.StateVersion, Result: copiedResult})
	trimOperations(&next)
	if err := validateStored(next, private); err != nil {
		return Document{}, err
	}
	if err := s.persist(next, private); err != nil {
		return Document{}, err
	}
	s.document = next
	s.secrets = private
	return publicDocument(next), nil
}

func (s *Store) persist(next catalogDocument, private secretDocument) error {
	previousPrivate := cloneSecretDocument(s.secrets)
	if err := writeAtomic(s.paths.Secrets, private, s.rename); err != nil {
		return ErrStorage
	}
	if err := writeAtomic(s.paths.Catalog, next, s.rename); err != nil {
		if rollbackErr := writeAtomic(s.paths.Secrets, previousPrivate, os.Rename); rollbackErr != nil {
			return fmt.Errorf("%w: public write and private rollback failed", ErrStorage)
		}
		return ErrStorage
	}
	return nil
}

func validateStored(document catalogDocument, private secretDocument) error {
	if document.SchemaVersion != SchemaVersion || private.SchemaVersion != SchemaVersion || len(document.Operations) > maxOperations {
		return ErrCorrupt
	}
	if err := publicDocument(document).Validate(); err != nil {
		return fmt.Errorf("%w: %v", ErrCorrupt, err)
	}
	sourceSet := sourceIDs(document.Sources)
	seen := make(map[string]struct{}, len(private.Sources))
	for _, secret := range private.Sources {
		if _, exists := sourceSet[secret.SourceID]; !exists || secret.Raw == "" || len(secret.Raw) > MaxSourceSecretBytes ||
			len(secret.Projection) > MaxSourceSecretBytes || !utf8.ValidString(secret.Projection) || !validSourceKind(secret.Kind) {
			return ErrCorrupt
		}
		if _, exists := seen[secret.SourceID]; exists {
			return ErrCorrupt
		}
		seen[secret.SourceID] = struct{}{}
	}
	for _, operation := range document.Operations {
		if !operationKey.MatchString(operation.Key) || len(operation.RequestHash) != 64 || operation.StateVersion == 0 {
			return ErrCorrupt
		}
		if operation.Result != nil && !validRecordedResult(*operation.Result) {
			return ErrCorrupt
		}
	}
	return nil
}

func writeAtomic(target string, value any, rename func(string, string) error) error {
	directory := filepath.Dir(target)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	body, err := json.Marshal(value)
	if err != nil || len(body) > maxCatalogFileBytes {
		return ErrLimit
	}
	temporary, err := os.CreateTemp(directory, ".keenwg-catalog-*")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(0o600); err != nil {
		temporary.Close()
		return err
	}
	if _, err := temporary.Write(body); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	if err := rename(temporaryPath, target); err != nil {
		return err
	}
	if parent, err := os.Open(directory); err == nil {
		_ = parent.Sync()
		_ = parent.Close()
	}
	return nil
}

func readStrict(path string, destination any) (bool, error) {
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 || info.Size() > maxCatalogFileBytes {
		return false, ErrCorrupt
	}
	file, err := os.Open(path)
	if err != nil {
		return false, ErrStorage
	}
	defer file.Close()
	decoder := json.NewDecoder(io.LimitReader(file, maxCatalogFileBytes+1))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		return false, ErrCorrupt
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return false, ErrCorrupt
	}
	return true, nil
}

func requestDigest(parts ...string) string {
	digest := sha256.Sum256([]byte(strings.Join(parts, "\n")))
	return hex.EncodeToString(digest[:])
}

func (s *Store) uniqueID(existing map[string]struct{}) (string, error) {
	for attempt := 0; attempt < 8; attempt++ {
		buffer := make([]byte, 16)
		if _, err := io.ReadFull(s.random, buffer); err != nil {
			return "", ErrStorage
		}
		id := base64.RawURLEncoding.EncodeToString(buffer)
		if _, exists := existing[id]; !exists {
			return id, nil
		}
	}
	return "", ErrStorage
}

func publicDocument(document catalogDocument) Document {
	return Document{
		SchemaVersion: document.SchemaVersion,
		StateVersion:  document.StateVersion,
		Groups:        append([]Group{}, document.Groups...),
		Sources:       cloneSources(document.Sources),
		Nodes:         cloneNodes(document.Nodes),
	}
}

func cloneCatalog(value catalogDocument) catalogDocument {
	value.Groups = append([]Group(nil), value.Groups...)
	value.Sources = cloneSources(value.Sources)
	value.Nodes = cloneNodes(value.Nodes)
	value.Operations = append([]operationRecord(nil), value.Operations...)
	for index := range value.Operations {
		if value.Operations[index].Result != nil {
			copyValue := *value.Operations[index].Result
			value.Operations[index].Result = &copyValue
		}
	}
	return value
}

func trimOperations(document *catalogDocument) {
	if len(document.Operations) > maxOperations {
		document.Operations = append([]operationRecord(nil), document.Operations[len(document.Operations)-maxOperations:]...)
	}
}

func validRecordedResult(result RecordedResult) bool {
	if result.Kind == "" || len(result.Kind) > 32 || result.Result == "" || len(result.Result) > 32 ||
		len(result.ErrorCode) > 128 || len(result.NodeID) > 128 || result.LatencyMS < 0 || result.ObservedUnix < 0 {
		return false
	}
	return !strings.ContainsAny(result.Kind+result.Result+result.ErrorCode+result.NodeID, "\r\n\x00")
}

func cloneSecretDocument(value secretDocument) secretDocument {
	result := secretDocument{SchemaVersion: value.SchemaVersion, Sources: make([]sourceSecret, len(value.Sources))}
	for index, secret := range value.Sources {
		secret.Headers = cloneHeaders(secret.Headers)
		result.Sources[index] = secret
	}
	return result
}

func cloneSources(values []Source) []Source {
	result := append([]Source{}, values...)
	for index := range result {
		result[index].Warnings = append([]string{}, result[index].Warnings...)
		if result[index].LastRefresh != nil {
			copyValue := *result[index].LastRefresh
			result[index].LastRefresh = &copyValue
		}
	}
	return result
}

func cloneNodes(values []Node) []Node {
	result := append([]Node{}, values...)
	for index := range result {
		result[index].Warnings = append([]string{}, result[index].Warnings...)
	}
	return result
}

func cloneHeaders(headers map[string]string) map[string]string {
	if len(headers) == 0 {
		return map[string]string{}
	}
	result := make(map[string]string, len(headers))
	for key, value := range headers {
		result[key] = value
	}
	return result
}

func validSourceDraft(draft SourceDraft) bool {
	return opaqueID.MatchString(draft.GroupID) && opaqueID.MatchString(draft.AdapterID) && validLabel(draft.Label) &&
		(draft.Kind == SourceSubscription || draft.Kind == SourceShareLink || draft.Kind == SourceConfig) && len(draft.Headers) <= 16
}

func findOperation(values []operationRecord, key string) (operationRecord, bool) {
	for _, operation := range values {
		if operation.Key == key {
			return operation, true
		}
	}
	return operationRecord{}, false
}

func containsGroup(values []Group, id string) bool {
	for _, value := range values {
		if value.ID == id {
			return true
		}
	}
	return false
}

func indexSource(values []Source, id string) int {
	for index, value := range values {
		if value.ID == id {
			return index
		}
	}
	return -1
}

func groupIDs(values []Group) map[string]struct{} {
	result := make(map[string]struct{}, len(values))
	for _, value := range values {
		result[value.ID] = struct{}{}
	}
	return result
}

func sourceIDs(values []Source) map[string]struct{} {
	result := make(map[string]struct{}, len(values))
	for _, value := range values {
		result[value.ID] = struct{}{}
	}
	return result
}

func nodeIDs(values []Node) map[string]struct{} {
	result := make(map[string]struct{}, len(values))
	for _, value := range values {
		result[value.ID] = struct{}{}
	}
	return result
}

func filterNodes(values []Node, sourceID string) []Node {
	result := make([]Node, 0, len(values))
	for _, value := range values {
		if value.SourceID != sourceID {
			result = append(result, value)
		}
	}
	return result
}

func filterSecrets(values []sourceSecret, sourceID string) []sourceSecret {
	result := make([]sourceSecret, 0, len(values))
	for _, value := range values {
		if value.SourceID != sourceID {
			result = append(result, value)
		}
	}
	return result
}

func countNodes(values []Node, sourceID string) int {
	count := 0
	for _, value := range values {
		if value.SourceID == sourceID {
			count++
		}
	}
	return count
}

func secretZero(value []byte) {
	for index := range value {
		value[index] = 0
	}
}

func sortDocument(document *catalogDocument) {
	sort.SliceStable(document.Groups, func(i, j int) bool { return document.Groups[i].Order < document.Groups[j].Order })
	sort.SliceStable(document.Sources, func(i, j int) bool { return document.Sources[i].ID < document.Sources[j].ID })
	sort.SliceStable(document.Nodes, func(i, j int) bool { return document.Nodes[i].ID < document.Nodes[j].ID })
}
