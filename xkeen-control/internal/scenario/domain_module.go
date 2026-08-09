package scenario

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"reflect"

	"github.com/goldb/keenwg/xkeen-control/internal/domainpolicy"
)

var ErrDomainModule = errors.New("scenario_domain_module_rejected")

type DomainReplaceManager interface {
	Status(context.Context) (domainpolicy.Status, error)
	Replace(context.Context, domainpolicy.ReplaceRequest) domainpolicy.Result
}

type DomainModule struct{ manager DomainReplaceManager }

func NewDomainModule(manager DomainReplaceManager) *DomainModule {
	return &DomainModule{manager: manager}
}
func (*DomainModule) ID() string { return "routes" }

type domainSnapshot struct {
	SchemaVersion int                 `json:"schema_version"`
	StateVersion  uint64              `json:"state_version"`
	Rules         []domainpolicy.Rule `json:"rules"`
}

func (m *DomainModule) Version(ctx context.Context) (uint64, error) {
	status, err := m.status(ctx)
	return status.StateVersion, err
}
func (m *DomainModule) Capture(ctx context.Context) ([]byte, error) {
	status, err := m.status(ctx)
	if err != nil {
		return nil, err
	}
	return json.Marshal(domainSnapshot{SchemaVersion: 1, StateVersion: status.StateVersion, Rules: append([]domainpolicy.Rule(nil), status.Rules...)})
}
func (m *DomainModule) Stage(_ context.Context, before, payload []byte) ([]byte, error) {
	snapshot, err := decodeDomainSnapshot(before)
	if err != nil {
		return nil, err
	}
	var steps []Step
	if err := decodeStrict(payload, &steps); err != nil || len(steps) == 0 || len(steps) > 256 {
		return nil, ErrDomainModule
	}
	rules := append([]domainpolicy.Rule(nil), snapshot.Rules...)
	for _, step := range steps {
		kind := ""
		if step.Module == "domains" && (step.MatchKind == "domain" || step.MatchKind == "suffix" || step.MatchKind == "geosite") {
			kind = step.MatchKind
		}
		if step.Module == "ip" && step.MatchKind == "cidr" {
			kind = "cidr"
		}
		if kind == "" {
			return nil, ErrDomainModule
		}
		effect := "direct"
		if step.Outcome.Mode == "group" && identifier.MatchString(step.Outcome.GroupID) {
			effect = "vpn"
		} else if step.Outcome.Mode != "direct" || step.Outcome.GroupID != "" {
			return nil, ErrDomainModule
		}
		candidate, canonicalErr := domainpolicy.CanonicalizeRule(domainpolicy.Rule{Kind: kind, Value: step.Value, Effect: effect, Label: "Сценарий · " + step.Value, Enabled: true})
		if canonicalErr != nil {
			return nil, ErrDomainModule
		}
		filtered := rules[:0]
		for _, existing := range rules {
			if existing.Kind == candidate.Kind && existing.Value == candidate.Value {
				if existing.Protected {
					return nil, ErrDomainModule
				}
				continue
			}
			filtered = append(filtered, existing)
		}
		rules = append(filtered, candidate)
	}
	candidate := domainSnapshot{SchemaVersion: 1, StateVersion: snapshot.StateVersion, Rules: rules}
	body, marshalErr := json.Marshal(candidate)
	if marshalErr != nil {
		return nil, ErrDomainModule
	}
	return body, nil
}
func (m *DomainModule) Validate(_ context.Context, staged []byte) error {
	snapshot, err := decodeDomainSnapshot(staged)
	if err != nil {
		return err
	}
	if domainpolicy.ValidatePolicy(domainpolicy.Policy{SchemaVersion: 1, Rules: snapshot.Rules}) != nil {
		return ErrDomainModule
	}
	return nil
}
func (m *DomainModule) Apply(ctx context.Context, staged []byte) error {
	snapshot, err := decodeDomainSnapshot(staged)
	if err != nil {
		return err
	}
	result := m.manager.Replace(ctx, domainpolicy.ReplaceRequest{StateVersion: snapshot.StateVersion, IdempotencyKey: domainOperationKey("apply", staged), Rules: snapshot.Rules})
	if result.Result != "committed" {
		return ErrDomainModule
	}
	return nil
}
func (m *DomainModule) Verify(ctx context.Context, staged []byte) error {
	snapshot, err := decodeDomainSnapshot(staged)
	if err != nil {
		return err
	}
	status, err := m.status(ctx)
	if err != nil || !reflect.DeepEqual(status.Rules, snapshot.Rules) {
		return ErrDomainModule
	}
	return nil
}
func (m *DomainModule) Restore(ctx context.Context, before []byte) error {
	snapshot, err := decodeDomainSnapshot(before)
	if err != nil {
		return err
	}
	current, err := m.manager.Status(ctx)
	if err != nil || current.SchemaVersion != 1 || current.StateVersion == 0 {
		return ErrDomainModule
	}
	result := m.manager.Replace(ctx, domainpolicy.ReplaceRequest{StateVersion: current.StateVersion, IdempotencyKey: domainOperationKey("restore", before), Rules: snapshot.Rules})
	if result.Result != "committed" {
		return ErrDomainModule
	}
	return nil
}
func (m *DomainModule) VerifyRestore(ctx context.Context, before []byte) error {
	return m.Verify(ctx, before)
}

func (m *DomainModule) status(ctx context.Context) (domainpolicy.Status, error) {
	if m == nil || m.manager == nil {
		return domainpolicy.Status{}, ErrDomainModule
	}
	status, err := m.manager.Status(ctx)
	if err != nil || status.SchemaVersion != 1 || status.StateVersion == 0 || len(status.Warnings) > 0 {
		return domainpolicy.Status{}, ErrDomainModule
	}
	return status, nil
}
func decodeDomainSnapshot(body []byte) (domainSnapshot, error) {
	var snapshot domainSnapshot
	if len(body) == 0 || len(body) > 1<<20 || decodeStrict(body, &snapshot) != nil || snapshot.SchemaVersion != 1 || snapshot.StateVersion == 0 || domainpolicy.ValidatePolicy(domainpolicy.Policy{SchemaVersion: 1, Rules: snapshot.Rules}) != nil {
		return domainSnapshot{}, ErrDomainModule
	}
	return snapshot, nil
}
func decodeStrict(body []byte, destination any) error {
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		return err
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return ErrDomainModule
	}
	return nil
}
func domainOperationKey(kind string, body []byte) string {
	hash := sha256.Sum256(append([]byte(kind+"\x00"), body...))
	return "scenario-" + kind + "-" + hex.EncodeToString(hash[:8])
}
