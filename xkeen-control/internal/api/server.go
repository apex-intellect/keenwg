package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"strings"
	"sync"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/diagnostics"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/domainpolicy"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/exclusions"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/model"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/state"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/transaction"
)

const maxMutationBody = 4 << 10

type Engine interface {
	PrepareRefresh(string, uint64) (model.Operation, transaction.Job, error)
	PrepareSelect(string, string, uint64) (model.Operation, transaction.Job, error)
}

type Store interface {
	LoadSubscription() (model.SubscriptionState, error)
	LoadControllerState() (model.ControllerState, error)
	FindOperation(string) (model.Operation, bool, error)
}

type DiagnosticChecker interface {
	Check(context.Context, []model.Node) diagnostics.Report
}

type ExclusionManager interface {
	Status() (exclusions.Status, error)
	Mutate(context.Context, exclusions.Mutation) exclusions.Result
}

type DomainPolicyManager interface {
	Status(context.Context) (domainpolicy.Status, error)
	Mutate(context.Context, domainpolicy.Mutation) domainpolicy.Result
}

type Option func(*Server)

func WithExclusions(manager ExclusionManager) Option {
	return func(server *Server) { server.exclusions = manager }
}

func WithDomainPolicy(manager DomainPolicyManager) Option {
	return func(server *Server) { server.domains = manager }
}

type Server struct {
	version     string
	engine      Engine
	store       Store
	diagnostics DiagnosticChecker
	exclusions  ExclusionManager
	domains     DomainPolicyManager
	ctx         context.Context
	cancel      context.CancelFunc
	jobs        sync.WaitGroup
}

func NewCore(version string, engine Engine, store Store, options ...Option) *Server {
	ctx, cancel := context.WithCancel(context.Background())
	server := &Server{
		version:     version,
		engine:      engine,
		store:       store,
		diagnostics: diagnostics.NewDefault(),
		ctx:         ctx,
		cancel:      cancel,
	}
	for _, option := range options {
		option(server)
	}
	return server
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if r.URL.RawQuery != "" {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	switch {
	case r.URL.Path == "/v1/xkeen/health":
		s.handleHealth(w, r)
	case r.URL.Path == "/v1/xkeen/status":
		if !s.authorize(w, r, auth.ScopeViewer) {
			return
		}
		s.handleStatus(w, r)
	case r.URL.Path == "/v1/diagnostics/nodes":
		if !s.authorize(w, r, auth.ScopeViewer) {
			return
		}
		s.handleDiagnostics(w, r)
	case r.URL.Path == "/v1/network/exclusions":
		required := auth.ScopeOperator
		if r.Method == http.MethodGet {
			required = auth.ScopeViewer
		}
		if !s.authorize(w, r, required) {
			return
		}
		s.handleExclusions(w, r)
	case r.URL.Path == "/v1/network/domains":
		if !s.authorize(w, r, auth.ScopeViewer) {
			return
		}
		s.handleDomainsStatus(w, r)
	case r.URL.Path == "/v1/network/domains/rules":
		if !s.authorize(w, r, auth.ScopeOperator) {
			return
		}
		s.handleDomainMutation(w, r, "create", "")
	case strings.HasPrefix(r.URL.Path, "/v1/network/domains/rules/"):
		if !s.authorize(w, r, auth.ScopeOperator) {
			return
		}
		ruleID, ok := onePathSegment(r.URL.Path, "/v1/network/domains/rules/", "")
		if !ok {
			writeError(w, http.StatusNotFound, "not_found")
			return
		}
		action := ""
		if r.Method == http.MethodPut {
			action = "update"
		} else if r.Method == http.MethodDelete {
			action = "delete"
		}
		if action == "" {
			methodNotAllowed(w, http.MethodPut+", "+http.MethodDelete)
			return
		}
		s.handleDomainMutation(w, r, action, ruleID)
	case r.URL.Path == "/v1/xkeen/subscription/refresh":
		if !s.authorize(w, r, auth.ScopeOperator) {
			return
		}
		s.handleMutation(w, r, "refresh", "")
	case strings.HasPrefix(r.URL.Path, "/v1/xkeen/nodes/") && strings.HasSuffix(r.URL.Path, "/select"):
		if !s.authorize(w, r, auth.ScopeOperator) {
			return
		}
		nodeID, ok := onePathSegment(r.URL.Path, "/v1/xkeen/nodes/", "/select")
		if !ok {
			writeError(w, http.StatusNotFound, "not_found")
			return
		}
		s.handleMutation(w, r, "select", nodeID)
	case strings.HasPrefix(r.URL.Path, "/v1/xkeen/operations/"):
		if !s.authorize(w, r, auth.ScopeViewer) {
			return
		}
		key, ok := onePathSegment(r.URL.Path, "/v1/xkeen/operations/", "")
		if !ok {
			writeError(w, http.StatusNotFound, "not_found")
			return
		}
		s.handleOperation(w, r, key)
	default:
		writeError(w, http.StatusNotFound, "not_found")
	}
}

func (s *Server) handleDomainsStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w, http.MethodGet)
		return
	}
	if s.domains == nil {
		writeError(w, http.StatusServiceUnavailable, "feature_unavailable")
		return
	}
	status, err := s.domains.Status(r.Context())
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "storage_unavailable")
		return
	}
	normalizeDomainStatus(&status)
	writeJSON(w, http.StatusOK, status)
}

type domainMutationRequest struct {
	StateVersion   uint64             `json:"state_version"`
	IdempotencyKey string             `json:"idempotency_key"`
	Rule           *domainpolicy.Rule `json:"rule,omitempty"`
}

func (s *Server) handleDomainMutation(w http.ResponseWriter, r *http.Request, action, ruleID string) {
	if s.domains == nil {
		writeError(w, http.StatusServiceUnavailable, "feature_unavailable")
		return
	}
	if (action == "create" && r.Method != http.MethodPost) || (action == "update" && r.Method != http.MethodPut) || (action == "delete" && r.Method != http.MethodDelete) {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}
	mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || mediaType != "application/json" {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	if r.ContentLength > maxMutationBody {
		writeError(w, http.StatusRequestEntityTooLarge, "request_too_large")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxMutationBody)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	var request domainMutationRequest
	if err := decoder.Decode(&request); err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			writeError(w, http.StatusRequestEntityTooLarge, "request_too_large")
			return
		}
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	if (action == "delete" && request.Rule != nil) || (action != "delete" && request.Rule == nil) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	result := s.domains.Mutate(r.Context(), domainpolicy.Mutation{
		StateVersion: request.StateVersion, IdempotencyKey: request.IdempotencyKey,
		Action: action, Rule: request.Rule, RuleID: ruleID,
	})
	normalizeDomainStatus(&result.Status)
	code := http.StatusOK
	if result.Result == "rejected" {
		code = http.StatusConflict
	} else if result.Result == "uncertain" {
		code = http.StatusServiceUnavailable
	}
	writeJSON(w, code, result)
}

func normalizeDomainStatus(status *domainpolicy.Status) {
	if status.Rules == nil {
		status.Rules = []domainpolicy.Rule{}
	}
	if status.Presets == nil {
		status.Presets = []domainpolicy.Preset{}
	}
	if status.Warnings == nil {
		status.Warnings = []string{}
	}
}

func (s *Server) handleExclusions(w http.ResponseWriter, r *http.Request) {
	if s.exclusions == nil {
		writeError(w, http.StatusServiceUnavailable, "feature_unavailable")
		return
	}
	switch r.Method {
	case http.MethodGet:
		status, err := s.exclusions.Status()
		if err != nil {
			writeError(w, http.StatusServiceUnavailable, "storage_unavailable")
			return
		}
		writeJSON(w, http.StatusOK, status)
	case http.MethodPost:
		mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
		if err != nil || mediaType != "application/json" {
			writeError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, maxMutationBody)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()
		var mutation exclusions.Mutation
		if err := decoder.Decode(&mutation); err != nil {
			writeError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		var extra any
		if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
			writeError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		result := s.exclusions.Mutate(r.Context(), mutation)
		code := http.StatusOK
		if result.Result == "rejected" {
			code = http.StatusConflict
		}
		if result.Result == "uncertain" {
			code = http.StatusServiceUnavailable
		}
		writeJSON(w, code, result)
	default:
		methodNotAllowed(w, http.MethodGet+", "+http.MethodPost)
	}
}

func (s *Server) handleDiagnostics(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || mediaType != "application/json" {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxMutationBody)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	var request struct{}
	if err := decoder.Decode(&request); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	subscription, err := s.store.LoadSubscription()
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "storage_unavailable")
		return
	}
	report := s.diagnostics.Check(r.Context(), subscription.Nodes)
	if report.Results == nil {
		report.Results = []diagnostics.NodeResult{}
	}
	writeJSON(w, http.StatusOK, report)
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w, http.MethodGet)
		return
	}
	if _, err := s.store.LoadControllerState(); err != nil {
		writeError(w, http.StatusServiceUnavailable, "storage_unavailable")
		return
	}
	if _, err := s.store.LoadSubscription(); err != nil {
		writeError(w, http.StatusServiceUnavailable, "storage_unavailable")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok", "storage": "ok", "version": s.version})
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w, http.MethodGet)
		return
	}
	controller, err := s.store.LoadControllerState()
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "storage_unavailable")
		return
	}
	subscription, err := s.store.LoadSubscription()
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "storage_unavailable")
		return
	}
	publicNodes := make([]model.PublicNode, len(subscription.Nodes))
	activeID := ""
	var active *model.ActiveNode
	if controller.Active != nil {
		activeID = controller.Active.ID
		normalized := *controller.Active
		normalized.Warnings = append([]string{}, controller.Active.Warnings...)
		active = &normalized
	}
	for i, node := range subscription.Nodes {
		publicNodes[i] = model.SanitizeNode(node, activeID != "" && node.ID == activeID)
	}
	var refreshedAt *int64
	if subscription.RefreshedAt > 0 {
		value := subscription.RefreshedAt
		refreshedAt = &value
	}
	var latest *model.Operation
	if len(controller.Operations) > 0 {
		operation := controller.Operations[len(controller.Operations)-1]
		latest = &operation
	}
	status := model.ControllerStatus{
		Version:      s.version,
		StateVersion: controller.StateVersion,
		Active:       active,
		Subscription: model.SubscriptionView{RefreshedAt: refreshedAt, Stale: false, Nodes: publicNodes},
		Operation:    latest,
	}
	writeJSON(w, http.StatusOK, status)
}

type mutationRequest struct {
	StateVersion   uint64 `json:"state_version"`
	IdempotencyKey string `json:"idempotency_key"`
}

func (s *Server) handleMutation(w http.ResponseWriter, r *http.Request, kind, nodeID string) {
	if r.Method != http.MethodPost {
		methodNotAllowed(w, http.MethodPost)
		return
	}
	if s.engine == nil {
		writeError(w, http.StatusServiceUnavailable, "feature_unavailable")
		return
	}
	mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || mediaType != "application/json" {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	if r.ContentLength > maxMutationBody {
		writeError(w, http.StatusRequestEntityTooLarge, "request_too_large")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxMutationBody)
	var request mutationRequest
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&request); err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			writeError(w, http.StatusRequestEntityTooLarge, "request_too_large")
			return
		}
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	var operation model.Operation
	var job transaction.Job
	if kind == "refresh" {
		operation, job, err = s.engine.PrepareRefresh(request.IdempotencyKey, request.StateVersion)
	} else {
		operation, job, err = s.engine.PrepareSelect(request.IdempotencyKey, nodeID, request.StateVersion)
	}
	if err != nil {
		writePreparationError(w, err)
		return
	}
	writeJSON(w, http.StatusAccepted, operation)
	if job != nil {
		s.start(job)
	}
}

func (s *Server) handleOperation(w http.ResponseWriter, r *http.Request, key string) {
	if r.Method != http.MethodGet {
		methodNotAllowed(w, http.MethodGet)
		return
	}
	operation, found, err := s.store.FindOperation(key)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "storage_unavailable")
		return
	}
	if !found {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	writeJSON(w, http.StatusOK, operation)
}

func (s *Server) authorize(w http.ResponseWriter, r *http.Request, required auth.Scope) bool {
	principal, ok := principalFrom(r)
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return false
	}
	if !scopeAllows(principal.Scope, required) {
		writeError(w, http.StatusForbidden, "forbidden")
		return false
	}
	return true
}

func (s *Server) start(job transaction.Job) {
	s.jobs.Add(1)
	go func() {
		defer s.jobs.Done()
		defer func() { _ = recover() }()
		job(s.ctx)
	}()
}

func (s *Server) Shutdown(ctx context.Context) error {
	s.cancel()
	done := make(chan struct{})
	go func() {
		s.jobs.Wait()
		close(done)
	}()
	select {
	case <-done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func onePathSegment(rawPath, prefix, suffix string) (string, bool) {
	if !strings.HasPrefix(rawPath, prefix) || !strings.HasSuffix(rawPath, suffix) {
		return "", false
	}
	value := strings.TrimSuffix(strings.TrimPrefix(rawPath, prefix), suffix)
	return value, value != "" && !strings.Contains(value, "/")
}

func writePreparationError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, transaction.ErrInvalidOperationKey):
		writeError(w, http.StatusBadRequest, "invalid_request")
	case errors.Is(err, transaction.ErrStaleState):
		writeError(w, http.StatusConflict, "stale_state")
	case errors.Is(err, transaction.ErrNodeNotFound):
		writeError(w, http.StatusNotFound, "node_not_found")
	case errors.Is(err, transaction.ErrBusy), errors.Is(err, state.ErrBusy):
		writeError(w, http.StatusServiceUnavailable, "busy")
	default:
		writeError(w, http.StatusServiceUnavailable, "controller_unavailable")
	}
}

func methodNotAllowed(w http.ResponseWriter, allowed ...string) {
	w.Header().Set("Allow", strings.Join(allowed, ", "))
	writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
}

func writeError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]any{"error": map[string]string{"code": code}})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	encoder := json.NewEncoder(w)
	encoder.SetEscapeHTML(false)
	_ = encoder.Encode(value)
}
