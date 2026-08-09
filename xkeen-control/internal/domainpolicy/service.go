package domainpolicy

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"io/fs"
	"regexp"
	"sync"
)

type RuntimeSystem interface {
	ReadFile(string) ([]byte, error)
	WriteAtomic(string, []byte, fs.FileMode) error
	Validate(context.Context) error
	Restart(context.Context) error
	CheckGeoSite(context.Context, string) error
}

type cachedResult struct {
	request [32]byte
	result  Result
}

type Service struct {
	store       *Store
	policyPath  string
	routingPath string
	system      RuntimeSystem
	mu          sync.Mutex
	results     map[string]cachedResult
	blocked     bool
}

func NewService(policyPath, backupPath, routingPath string, system RuntimeSystem) *Service {
	return &Service{
		store:       NewStore(policyPath, backupPath, system),
		policyPath:  policyPath,
		routingPath: routingPath,
		system:      system,
		results:     make(map[string]cachedResult),
	}
}

func (s *Service) Status(ctx context.Context) (Status, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	status, err := s.statusUnlocked(ctx)
	if err == nil && len(status.Warnings) == 0 {
		s.blocked = false
	}
	return status, err
}

func (s *Service) Mutate(ctx context.Context, mutation Mutation) Result {
	s.mu.Lock()
	defer s.mu.Unlock()
	request := mutationHash(mutation)
	if cached, ok := s.results[mutation.IdempotencyKey]; ok {
		if cached.request == request {
			return cached.result
		}
		status, _ := s.statusUnlocked(ctx)
		return Result{Result: "rejected", Status: status}
	}
	current, originalPolicy, err := s.loadStatusPolicy(ctx)
	if err != nil {
		return Result{Result: "uncertain", Status: emptyDomainStatus()}
	}
	if s.blocked || !validIdempotencyKey(mutation.IdempotencyKey) || mutation.StateVersion != current.StateVersion {
		return Result{Result: "rejected", Status: current}
	}
	policy, _, err := s.store.Load()
	if err != nil {
		return Result{Result: "uncertain", Status: current}
	}
	candidate, err := applyMutation(policy, mutation)
	if err != nil || !s.presetsAvailable(ctx, candidate) {
		return Result{Result: "rejected", Status: current}
	}
	originalRouting, err := s.system.ReadFile(s.routingPath)
	if err != nil {
		return Result{Result: "uncertain", Status: current}
	}
	candidateRouting, err := RenderRouting(originalRouting, candidate)
	if err != nil {
		return Result{Result: "rejected", Status: current}
	}
	changed := false
	if err = s.store.Save(candidate, originalPolicy); err == nil {
		changed = true
		if err = s.system.WriteAtomic(s.routingPath, candidateRouting, 0o600); err == nil {
			if err = s.system.Validate(ctx); err == nil {
				if err = s.system.Restart(ctx); err == nil {
					var publishedPolicy []byte
					_, publishedPolicy, err = s.store.Load()
					if err == nil {
						var publishedRouting []byte
						publishedRouting, err = s.system.ReadFile(s.routingPath)
						if err == nil && (!bytes.Equal(publishedRouting, candidateRouting) || PolicyVersion(publishedPolicy) == current.StateVersion) {
							err = ErrInvalidPolicy
						}
					}
				}
			}
		}
	}
	if err == nil {
		status, statusErr := s.statusUnlocked(ctx)
		if statusErr == nil && len(status.Warnings) == 0 {
			result := Result{Result: "committed", Status: status}
			s.results[mutation.IdempotencyKey] = cachedResult{request: request, result: result}
			return result
		}
		err = statusErr
		if err == nil {
			err = ErrInvalidPolicy
		}
	}
	if !changed {
		result := Result{Result: "rolled_back", Status: current}
		s.results[mutation.IdempotencyKey] = cachedResult{request: request, result: result}
		return result
	}
	if s.rollback(ctx, originalPolicy, originalRouting) == nil {
		status, statusErr := s.statusUnlocked(ctx)
		if statusErr == nil {
			result := Result{Result: "rolled_back", Status: status}
			s.results[mutation.IdempotencyKey] = cachedResult{request: request, result: result}
			return result
		}
	}
	s.blocked = true
	result := Result{Result: "uncertain", Status: current}
	s.results[mutation.IdempotencyKey] = cachedResult{request: request, result: result}
	return result
}

func (s *Service) Replace(ctx context.Context, request ReplaceRequest) Result {
	s.mu.Lock()
	defer s.mu.Unlock()
	body, _ := json.Marshal(request)
	digest := sha256.Sum256(body)
	if cached, ok := s.results[request.IdempotencyKey]; ok {
		if cached.request == digest {
			return cached.result
		}
		status, _ := s.statusUnlocked(ctx)
		return Result{Result: "rejected", Status: status}
	}
	current, originalPolicy, err := s.loadStatusPolicy(ctx)
	if err != nil {
		return Result{Result: "uncertain", Status: emptyDomainStatus()}
	}
	if s.blocked || !validIdempotencyKey(request.IdempotencyKey) || request.StateVersion != current.StateVersion {
		return Result{Result: "rejected", Status: current}
	}
	candidate := Policy{SchemaVersion: 1, Rules: append([]Rule(nil), request.Rules...)}
	if ValidatePolicy(candidate) != nil || !protectedRulesPreserved(current.Rules, candidate.Rules) || !s.presetsAvailable(ctx, candidate) {
		return Result{Result: "rejected", Status: current}
	}
	originalRouting, err := s.system.ReadFile(s.routingPath)
	if err != nil {
		return Result{Result: "uncertain", Status: current}
	}
	candidateRouting, err := RenderRouting(originalRouting, candidate)
	if err != nil {
		return Result{Result: "rejected", Status: current}
	}
	changed := false
	if err = s.store.Save(candidate, originalPolicy); err == nil {
		changed = true
		if err = s.system.WriteAtomic(s.routingPath, candidateRouting, 0o600); err == nil {
			if err = s.system.Validate(ctx); err == nil {
				if err = s.system.Restart(ctx); err == nil {
					publishedPolicy, loadErr := s.system.ReadFile(s.policyPath)
					publishedRouting, routeErr := s.system.ReadFile(s.routingPath)
					if loadErr != nil || routeErr != nil || !bytes.Equal(publishedRouting, candidateRouting) || PolicyVersion(publishedPolicy) == current.StateVersion {
						err = ErrInvalidPolicy
					}
				}
			}
		}
	}
	if err == nil {
		status, statusErr := s.statusUnlocked(ctx)
		if statusErr == nil && len(status.Warnings) == 0 {
			result := Result{Result: "committed", Status: status}
			s.results[request.IdempotencyKey] = cachedResult{request: digest, result: result}
			return result
		}
		err = statusErr
		if err == nil {
			err = ErrInvalidPolicy
		}
	}
	if !changed {
		result := Result{Result: "rolled_back", Status: current}
		s.results[request.IdempotencyKey] = cachedResult{request: digest, result: result}
		return result
	}
	if s.rollback(ctx, originalPolicy, originalRouting) == nil {
		status, statusErr := s.statusUnlocked(ctx)
		if statusErr == nil {
			result := Result{Result: "rolled_back", Status: status}
			s.results[request.IdempotencyKey] = cachedResult{request: digest, result: result}
			return result
		}
	}
	s.blocked = true
	result := Result{Result: "uncertain", Status: current}
	s.results[request.IdempotencyKey] = cachedResult{request: digest, result: result}
	return result
}

func protectedRulesPreserved(before, after []Rule) bool {
	byID := make(map[string]Rule, len(after))
	for _, rule := range after {
		byID[rule.ID] = rule
	}
	for _, rule := range before {
		if rule.Protected {
			if candidate, ok := byID[rule.ID]; !ok || candidate != rule {
				return false
			}
		}
	}
	return true
}

func (s *Service) loadStatusPolicy(ctx context.Context) (Status, []byte, error) {
	policy, body, err := s.store.Load()
	if err != nil {
		return Status{}, nil, err
	}
	status, err := s.statusFor(ctx, policy, body)
	return status, body, err
}

func (s *Service) statusUnlocked(ctx context.Context) (Status, error) {
	status, _, err := s.loadStatusPolicy(ctx)
	return status, err
}

func (s *Service) statusFor(ctx context.Context, policy Policy, body []byte) (Status, error) {
	routing, err := s.system.ReadFile(s.routingPath)
	if err != nil {
		return Status{}, err
	}
	warnings := []string{}
	expected, renderErr := RenderRouting(routing, policy)
	if renderErr != nil {
		return Status{}, renderErr
	}
	if !bytes.Equal(expected, routing) {
		warnings = append(warnings, "routing_projection_mismatch")
	}
	available := s.system.CheckGeoSite(ctx, "category-gov-ru") == nil
	enabled := false
	for _, rule := range policy.Rules {
		if rule.Kind == "geosite" && rule.Value == "category-gov-ru" && rule.Enabled {
			enabled = true
		}
	}
	presets := []Preset{{ID: "category-gov-ru", Label: "Государственные сайты РФ", Matcher: "ext:geosite_v2fly.dat:category-gov-ru", Available: available, Enabled: enabled}}
	return NewStatus(PolicyVersion(body), policy.Rules, presets, warnings), nil
}

func (s *Service) presetsAvailable(ctx context.Context, policy Policy) bool {
	for _, rule := range policy.Rules {
		if rule.Enabled && rule.Kind == "geosite" && s.system.CheckGeoSite(ctx, rule.Value) != nil {
			return false
		}
	}
	return true
}

func (s *Service) rollback(ctx context.Context, policy, routing []byte) error {
	var failures []error
	if err := s.system.WriteAtomic(s.policyPath, policy, 0o600); err != nil {
		failures = append(failures, err)
	}
	if err := s.system.WriteAtomic(s.routingPath, routing, 0o600); err != nil {
		failures = append(failures, err)
	}
	if len(failures) == 0 {
		if err := s.system.Restart(ctx); err != nil {
			failures = append(failures, err)
		}
	}
	if len(failures) == 0 {
		actualPolicy, policyErr := s.system.ReadFile(s.policyPath)
		actualRouting, routingErr := s.system.ReadFile(s.routingPath)
		if policyErr != nil || routingErr != nil || !bytes.Equal(actualPolicy, policy) || !bytes.Equal(actualRouting, routing) {
			failures = append(failures, ErrInvalidPolicy)
		}
	}
	return errors.Join(failures...)
}

func applyMutation(policy Policy, mutation Mutation) (Policy, error) {
	rules := append([]Rule{}, policy.Rules...)
	switch mutation.Action {
	case "create":
		if mutation.Rule == nil || mutation.RuleID != "" || mutation.Rule.Protected || mutation.Rule.Source == "system" {
			return Policy{}, ErrInvalidPolicy
		}
		draft := *mutation.Rule
		draft.ID = ""
		draft.Source = ""
		canonical, err := CanonicalizeRule(draft)
		if err != nil {
			return Policy{}, err
		}
		rules = append(rules, canonical)
	case "update":
		if mutation.Rule == nil || !validID(mutation.RuleID) {
			return Policy{}, ErrInvalidPolicy
		}
		index := findRule(rules, mutation.RuleID)
		if index < 0 || rules[index].Protected {
			return Policy{}, ErrInvalidPolicy
		}
		draft := *mutation.Rule
		draft.ID = mutation.RuleID
		draft.Source = ""
		draft.Protected = false
		canonical, err := CanonicalizeRule(draft)
		if err != nil {
			return Policy{}, err
		}
		rules[index] = canonical
	case "delete":
		if mutation.Rule != nil || !validID(mutation.RuleID) {
			return Policy{}, ErrInvalidPolicy
		}
		index := findRule(rules, mutation.RuleID)
		if index < 0 || rules[index].Protected {
			return Policy{}, ErrInvalidPolicy
		}
		rules = append(rules[:index], rules[index+1:]...)
	default:
		return Policy{}, ErrInvalidPolicy
	}
	candidate := Policy{SchemaVersion: 1, Rules: rules}
	if ValidatePolicy(candidate) != nil {
		return Policy{}, ErrInvalidPolicy
	}
	return candidate, nil
}

func findRule(rules []Rule, id string) int {
	for index, rule := range rules {
		if rule.ID == id {
			return index
		}
	}
	return -1
}

var idempotencyKeyPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{7,63}$`)

func validIdempotencyKey(value string) bool { return idempotencyKeyPattern.MatchString(value) }

func mutationHash(mutation Mutation) [32]byte {
	body, _ := json.Marshal(mutation)
	return sha256.Sum256(body)
}

func emptyDomainStatus() Status { return NewStatus(0, nil, nil, nil) }
