package api

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/adapter"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/catalog"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/connection"
)

const (
	maxCatalogMutationBody             int64 = 1 << 20
	catalogFeaturesHeader                    = "KeenWG-Catalog-Features"
	catalogFeatureSubscriptionMetadata       = "subscription-metadata-v1"
)

type CatalogStore interface {
	Snapshot(context.Context) (catalog.Document, error)
	CreateGroup(context.Context, uint64, string, string) (catalog.Document, error)
	CreateSource(context.Context, uint64, string, catalog.SourceDraft, []byte) (catalog.Document, error)
	DeleteSource(context.Context, uint64, string, string) (catalog.Document, error)
}

type catalogOperationEnvelope struct {
	SchemaVersion int                 `json:"schema_version"`
	Result        string              `json:"result"`
	Catalog       *catalog.Document   `json:"catalog,omitempty"`
	Test          *adapter.TestResult `json:"test,omitempty"`
	Error         string              `json:"error,omitempty"`
}

type ConnectionCoordinator interface {
	RefreshSource(context.Context, uint64, string, string) connection.Result
	TestNode(context.Context, uint64, string, string) connection.Result
	ActivateNode(context.Context, uint64, string, string) connection.Result
}

type catalogMutationBase struct {
	SchemaVersion  int    `json:"schema_version"`
	StateVersion   uint64 `json:"state_version"`
	IdempotencyKey string `json:"idempotency_key"`
}

func (s *SecureServer) handleCatalog(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w, http.MethodGet)
		return
	}
	document, err := s.catalog.Snapshot(r.Context())
	if err != nil {
		writeCatalogResult(w, http.StatusServiceUnavailable, "uncertain", nil, "catalog_unavailable", "")
		return
	}
	writeJSON(w, http.StatusOK, catalogForFeatures(document, r.Header.Get(catalogFeaturesHeader)))
}

func (s *SecureServer) handleCatalogMutation(w http.ResponseWriter, r *http.Request) {
	switch {
	case r.URL.Path == "/v1/connections/groups":
		s.handleCreateCatalogGroup(w, r)
	case r.URL.Path == "/v1/connections/sources":
		s.handleCreateCatalogSource(w, r)
	default:
		s.handleDeleteCatalogSource(w, r)
	}
}

func isConnectionOperationPath(path string) bool {
	_, _, ok := connectionOperationPath(path)
	return ok
}

func connectionOperationPath(path string) (string, string, bool) {
	if id, ok := onePathSegment(path, "/v1/connections/sources/", "/refresh"); ok {
		return "refresh", id, true
	}
	if id, ok := onePathSegment(path, "/v1/connections/nodes/", "/test"); ok {
		return "test", id, true
	}
	if id, ok := onePathSegment(path, "/v1/connections/nodes/", "/activate"); ok {
		return "activate", id, true
	}
	return "", "", false
}

func (s *SecureServer) handleConnectionOperation(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	var request catalogMutationBase
	if !decodeSecureJSONLimit(w, r, &request, maxMutationBody) || !validCatalogSchema(w, request.SchemaVersion) {
		return
	}
	operation, id, ok := connectionOperationPath(r.URL.Path)
	if !ok {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	var result connection.Result
	switch operation {
	case "refresh":
		result = s.connections.RefreshSource(r.Context(), request.StateVersion, request.IdempotencyKey, id)
	case "test":
		result = s.connections.TestNode(r.Context(), request.StateVersion, request.IdempotencyKey, id)
	default:
		result = s.connections.ActivateNode(r.Context(), request.StateVersion, request.IdempotencyKey, id)
	}
	writeConnectionResult(w, result, r.Header.Get(catalogFeaturesHeader))
}

func writeConnectionResult(w http.ResponseWriter, result connection.Result, features string) {
	status := http.StatusOK
	if result.Result == adapter.ResultRejected {
		status = http.StatusConflict
		switch result.ErrorCode {
		case "node_not_found", "source_not_found":
			status = http.StatusNotFound
		case "invalid_request":
			status = http.StatusBadRequest
		}
	} else if result.Result == adapter.ResultUncertain {
		status = http.StatusServiceUnavailable
	}
	var document *catalog.Document
	if result.Catalog.SchemaVersion != 0 {
		projected := catalogForFeatures(result.Catalog, features)
		document = &projected
	}
	writeJSON(w, status, catalogOperationEnvelope{
		SchemaVersion: 1, Result: result.Result, Catalog: document, Test: result.Test, Error: result.ErrorCode,
	})
}

func (s *SecureServer) handleCreateCatalogGroup(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	var request struct {
		catalogMutationBase
		Label string `json:"label"`
	}
	if !decodeSecureJSONLimit(w, r, &request, maxMutationBody) || !validCatalogSchema(w, request.SchemaVersion) {
		return
	}
	document, err := s.catalog.CreateGroup(r.Context(), request.StateVersion, request.IdempotencyKey, request.Label)
	writeCatalogMutation(w, document, err, r.Header.Get(catalogFeaturesHeader))
}

func (s *SecureServer) handleCreateCatalogSource(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	var request struct {
		catalogMutationBase
		GroupID   string             `json:"group_id"`
		Kind      catalog.SourceKind `json:"kind"`
		Label     string             `json:"label"`
		AdapterID string             `json:"adapter_id"`
		Source    string             `json:"source"`
	}
	if !decodeSecureJSONLimit(w, r, &request, maxCatalogMutationBody) || !validCatalogSchema(w, request.SchemaVersion) {
		return
	}
	secret := []byte(request.Source)
	request.Source = ""
	document, err := s.catalog.CreateSource(r.Context(), request.StateVersion, request.IdempotencyKey, catalog.SourceDraft{
		GroupID: request.GroupID, Kind: request.Kind, Label: request.Label, AdapterID: request.AdapterID,
	}, secret)
	writeCatalogMutation(w, document, err, r.Header.Get(catalogFeaturesHeader))
}

func (s *SecureServer) handleDeleteCatalogSource(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		methodNotAllowed(w, http.MethodDelete)
		return
	}
	sourceID, ok := onePathSegment(r.URL.Path, "/v1/connections/sources/", "")
	if !ok {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	var request catalogMutationBase
	if !decodeSecureJSONLimit(w, r, &request, maxMutationBody) || !validCatalogSchema(w, request.SchemaVersion) {
		return
	}
	document, err := s.catalog.DeleteSource(r.Context(), request.StateVersion, request.IdempotencyKey, sourceID)
	writeCatalogMutation(w, document, err, r.Header.Get(catalogFeaturesHeader))
}

func validCatalogSchema(w http.ResponseWriter, version int) bool {
	if version != 1 {
		writeError(w, http.StatusBadRequest, "unsupported_schema")
		return false
	}
	return true
}

func writeCatalogMutation(w http.ResponseWriter, document catalog.Document, err error, features string) {
	if err == nil {
		writeCatalogResult(w, http.StatusOK, "committed", &document, "", features)
		return
	}
	switch {
	case errors.Is(err, catalog.ErrStaleState):
		writeCatalogResult(w, http.StatusConflict, "rejected", nil, "stale_state", features)
	case errors.Is(err, catalog.ErrOperationConflict):
		writeCatalogResult(w, http.StatusConflict, "rejected", nil, "idempotency_conflict", features)
	case errors.Is(err, catalog.ErrNotFound):
		writeCatalogResult(w, http.StatusNotFound, "rejected", nil, "not_found", features)
	case errors.Is(err, catalog.ErrInvalid), errors.Is(err, catalog.ErrLimit):
		writeCatalogResult(w, http.StatusBadRequest, "rejected", nil, "invalid_request", features)
	default:
		writeCatalogResult(w, http.StatusServiceUnavailable, "uncertain", nil, "catalog_unavailable", features)
	}
}

func writeCatalogResult(w http.ResponseWriter, status int, result string, document *catalog.Document, code, features string) {
	if document != nil {
		projected := catalogForFeatures(*document, features)
		document = &projected
	}
	writeJSON(w, status, catalogOperationEnvelope{SchemaVersion: 1, Result: result, Catalog: document, Error: code})
}

func catalogForFeatures(document catalog.Document, features string) catalog.Document {
	for _, feature := range strings.FieldsFunc(features, func(character rune) bool {
		return character == ',' || character == ' ' || character == '\t'
	}) {
		if feature == catalogFeatureSubscriptionMetadata {
			return document
		}
	}
	projected := document
	projected.Sources = append([]catalog.Source(nil), document.Sources...)
	for index := range projected.Sources {
		projected.Sources[index].Subscription = nil
	}
	return projected
}
