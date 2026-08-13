package routerlocal

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"errors"
	"net/netip"
	"reflect"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	ErrInvalidRequest   = errors.New("invalid router request")
	ErrStaleState       = errors.New("router state changed")
	ErrPlanExpired      = errors.New("router plan expired")
	ErrPlanMismatch     = errors.New("router plan mismatch")
	ErrNotFound         = errors.New("router item not found")
	ErrConflict         = errors.New("router item conflict")
	ErrRecoveryRequired = errors.New("router state recovery required")
)

const (
	MutationCommitted  = "committed"
	MutationRolledBack = "rolled_back"
	MutationRejected   = "rejected"
	MutationUncertain  = "uncertain"

	PeerCreate     = "create"
	PeerRename     = "rename"
	PeerSetEnabled = "set_enabled"
	PeerRotate     = "rotate"
	PeerRevoke     = "revoke"

	planTTL           = 5 * time.Minute
	maxStoredPlans    = 256
	maxStoredResults  = 256
	wireGuardCacheTTL = 2 * time.Second
)

type HomeDocument struct {
	SchemaVersion int          `json:"schema_version"`
	StateVersion  string       `json:"state_version"`
	Devices       []HomeDevice `json:"devices"`
}

type WireGuardDocument struct {
	SchemaVersion int                  `json:"schema_version"`
	StateVersion  string               `json:"state_version"`
	Interfaces    []WireGuardInterface `json:"interfaces"`
}

type ReservationReviewRequest struct {
	StateVersion string  `json:"state_version"`
	MAC          string  `json:"mac"`
	ReservedIP   *string `json:"reserved_ip"`
}

type ReservationPlan struct {
	SchemaVersion int       `json:"schema_version"`
	PlanID        string    `json:"plan_id"`
	ExpiresAt     time.Time `json:"expires_at"`
	StateVersion  string    `json:"state_version"`
	MAC           string    `json:"mac"`
	BeforeIP      string    `json:"before_ip,omitempty"`
	AfterIP       string    `json:"after_ip,omitempty"`
}

type ReservationApplyRequest struct {
	PlanID         string  `json:"plan_id"`
	StateVersion   string  `json:"state_version"`
	MAC            string  `json:"mac"`
	ReservedIP     *string `json:"reserved_ip"`
	IdempotencyKey string  `json:"idempotency_key"`
}

type PeerReviewRequest struct {
	StateVersion string `json:"state_version"`
	InterfaceID  string `json:"interface_id"`
	Action       string `json:"action"`
	PublicKey    string `json:"public_key,omitempty"`
	NewPublicKey string `json:"new_public_key,omitempty"`
	Name         string `json:"name,omitempty"`
	AllowedIP    string `json:"allowed_ip,omitempty"`
	Keepalive    int    `json:"keepalive,omitempty"`
	Enabled      *bool  `json:"enabled,omitempty"`
}

type PeerPlan struct {
	SchemaVersion int               `json:"schema_version"`
	PlanID        string            `json:"plan_id"`
	ExpiresAt     time.Time         `json:"expires_at"`
	Request       PeerReviewRequest `json:"request"`
	Before        *WireGuardPeer    `json:"before,omitempty"`
	After         *WireGuardPeer    `json:"after,omitempty"`
}

type PeerApplyRequest struct {
	PeerReviewRequest
	PlanID         string `json:"plan_id"`
	IdempotencyKey string `json:"idempotency_key"`
}

func (r PeerApplyRequest) MarshalJSON() ([]byte, error) {
	type wire struct {
		StateVersion   string `json:"state_version"`
		InterfaceID    string `json:"interface_id"`
		Action         string `json:"action"`
		PublicKey      string `json:"public_key,omitempty"`
		NewPublicKey   string `json:"new_public_key,omitempty"`
		Name           string `json:"name,omitempty"`
		AllowedIP      string `json:"allowed_ip,omitempty"`
		Keepalive      int    `json:"keepalive,omitempty"`
		Enabled        *bool  `json:"enabled,omitempty"`
		PlanID         string `json:"plan_id"`
		IdempotencyKey string `json:"idempotency_key"`
	}
	return json.Marshal(wire{r.StateVersion, r.InterfaceID, r.Action, r.PublicKey, r.NewPublicKey, r.Name, r.AllowedIP, r.Keepalive, r.Enabled, r.PlanID, r.IdempotencyKey})
}

type MutationResult struct {
	SchemaVersion int                `json:"schema_version"`
	Status        string             `json:"status"`
	Code          string             `json:"code,omitempty"`
	Home          *HomeDocument      `json:"home,omitempty"`
	WireGuard     *WireGuardDocument `json:"wireguard,omitempty"`
}

type reservationStoredPlan struct {
	request ReservationReviewRequest
	plan    ReservationPlan
}

type peerStoredPlan struct {
	request PeerReviewRequest
	plan    PeerPlan
}

type storedResult struct {
	hash   string
	result MutationResult
}

type Service struct {
	runner Runner
	clock  func() time.Time
	newID  func() string

	mutationMu         sync.Mutex
	stateMu            sync.Mutex
	reservationPlans   map[string]reservationStoredPlan
	peerPlans          map[string]peerStoredPlan
	results            map[string]storedResult
	homeUncertain      bool
	wireGuardUncertain bool
	wireGuardCache     *cachedWireGuard
}

type cachedWireGuard struct {
	document  WireGuardDocument
	expiresAt time.Time
}

func NewService(runner Runner) *Service {
	return newService(runner, time.Now, randomPlanID)
}

func newService(runner Runner, clock func() time.Time, newID func() string) *Service {
	return &Service{
		runner: runner, clock: clock, newID: newID,
		reservationPlans: make(map[string]reservationStoredPlan),
		peerPlans:        make(map[string]peerStoredPlan),
		results:          make(map[string]storedResult),
	}
}

func randomPlanID() string {
	value := make([]byte, 16)
	if _, err := rand.Read(value); err != nil {
		panic("secure random unavailable")
	}
	return hex.EncodeToString(value)
}

func (s *Service) SnapshotHome(ctx context.Context) (HomeDocument, error) {
	hotspot, err := s.runner.Run(ctx, QueryHotspot())
	if err != nil {
		return HomeDocument{}, err
	}
	leases, err := s.runner.Run(ctx, QueryLeases())
	if err != nil {
		return HomeDocument{}, err
	}
	running, err := s.runner.Run(ctx, QueryRunningConfig())
	if err != nil {
		return HomeDocument{}, err
	}
	devices, err := ParseHomeDevices(hotspot, leases, running)
	if err != nil {
		return HomeDocument{}, err
	}
	if devices == nil {
		devices = []HomeDevice{}
	}
	return HomeDocument{SchemaVersion: 1, StateVersion: homeStateVersion(devices), Devices: devices}, nil
}

// RecoverHome is the explicit read barrier after an uncertain mutation. Only
// a complete, successfully parsed inventory releases subsequent home writes.
func (s *Service) RecoverHome(ctx context.Context) (HomeDocument, error) {
	document, err := s.SnapshotHome(ctx)
	if err == nil {
		s.stateMu.Lock()
		s.homeUncertain = false
		s.stateMu.Unlock()
	}
	return document, err
}

func (s *Service) SnapshotWireGuard(ctx context.Context) (WireGuardDocument, error) {
	running, err := s.runner.Run(ctx, QueryRunningConfig())
	if err != nil {
		return WireGuardDocument{}, err
	}
	ids, err := DiscoverWireGuardInterfaces(running)
	if err != nil {
		return WireGuardDocument{}, err
	}
	interfaces := make([]WireGuardInterface, 0, len(ids))
	for _, id := range ids {
		command, err := QueryWireGuard(id)
		if err != nil {
			return WireGuardDocument{}, err
		}
		runtime, err := s.runner.Run(ctx, command)
		if err != nil {
			return WireGuardDocument{}, err
		}
		value, err := ParseWireGuardInterface(runtime, running, id)
		if err != nil {
			return WireGuardDocument{}, err
		}
		if value.Addresses == nil {
			value.Addresses = []string{}
		}
		if value.Peers == nil {
			value.Peers = []WireGuardPeer{}
		}
		interfaces = append(interfaces, value)
	}
	return WireGuardDocument{SchemaVersion: 1, StateVersion: wireGuardStateVersion(interfaces), Interfaces: interfaces}, nil
}

// ReadWireGuard serves a very short-lived, verified inventory and serializes
// cache misses with mutations. It never uses cached state as a recovery
// barrier after an uncertain write.
func (s *Service) ReadWireGuard(ctx context.Context) (WireGuardDocument, error) {
	s.mutationMu.Lock()
	defer s.mutationMu.Unlock()
	if s.wireGuardWritesNeedRecovery() {
		return s.RecoverWireGuard(ctx)
	}
	if document, ok := s.cachedWireGuard(); ok {
		return document, nil
	}
	document, err := s.SnapshotWireGuard(ctx)
	if err == nil {
		s.storeWireGuardCache(document)
	}
	return document, err
}

// RecoverWireGuard is the explicit read barrier after an uncertain mutation.
func (s *Service) RecoverWireGuard(ctx context.Context) (WireGuardDocument, error) {
	document, err := s.SnapshotWireGuard(ctx)
	if err == nil {
		s.stateMu.Lock()
		s.wireGuardUncertain = false
		s.wireGuardCache = &cachedWireGuard{document: cloneWireGuardDocument(document), expiresAt: s.clock().Add(wireGuardCacheTTL)}
		s.stateMu.Unlock()
	}
	return document, err
}

func (s *Service) cachedWireGuard() (WireGuardDocument, bool) {
	s.stateMu.Lock()
	defer s.stateMu.Unlock()
	if s.wireGuardCache == nil || !s.clock().Before(s.wireGuardCache.expiresAt) {
		s.wireGuardCache = nil
		return WireGuardDocument{}, false
	}
	return cloneWireGuardDocument(s.wireGuardCache.document), true
}

func (s *Service) storeWireGuardCache(document WireGuardDocument) {
	s.stateMu.Lock()
	s.wireGuardCache = &cachedWireGuard{document: cloneWireGuardDocument(document), expiresAt: s.clock().Add(wireGuardCacheTTL)}
	s.stateMu.Unlock()
}

func cloneWireGuardDocument(document WireGuardDocument) WireGuardDocument {
	copyDocument := document
	copyDocument.Interfaces = append([]WireGuardInterface(nil), document.Interfaces...)
	for index := range copyDocument.Interfaces {
		copyDocument.Interfaces[index].Addresses = append([]string(nil), document.Interfaces[index].Addresses...)
		copyDocument.Interfaces[index].Peers = append([]WireGuardPeer(nil), document.Interfaces[index].Peers...)
	}
	return copyDocument
}

func (s *Service) ReviewReservation(ctx context.Context, request ReservationReviewRequest) (ReservationPlan, error) {
	if s.homeWritesNeedRecovery() {
		return ReservationPlan{}, ErrRecoveryRequired
	}
	normalized, err := normalizeReservationReview(request)
	if err != nil {
		return ReservationPlan{}, err
	}
	document, err := s.SnapshotHome(ctx)
	if err != nil {
		return ReservationPlan{}, err
	}
	if document.StateVersion != normalized.StateVersion {
		return ReservationPlan{}, ErrStaleState
	}
	device, ok := findDevice(document.Devices, normalized.MAC)
	if !ok {
		return ReservationPlan{}, ErrNotFound
	}
	after := pointerValue(normalized.ReservedIP)
	if after != "" {
		if err := validateHomeAddress(device, after, document.Devices); err != nil {
			return ReservationPlan{}, err
		}
	}
	plan := ReservationPlan{SchemaVersion: 1, PlanID: s.newID(), ExpiresAt: s.clock().Add(planTTL), StateVersion: document.StateVersion, MAC: normalized.MAC, BeforeIP: device.ReservedIP, AfterIP: after}
	s.stateMu.Lock()
	s.evictLocked()
	s.reservationPlans[plan.PlanID] = reservationStoredPlan{request: normalized, plan: plan}
	s.stateMu.Unlock()
	return plan, nil
}

func (s *Service) ApplyReservation(ctx context.Context, request ReservationApplyRequest) (MutationResult, error) {
	normalized, err := normalizeReservationApply(request)
	if err != nil {
		return MutationResult{}, err
	}
	hash := requestHash(normalized)
	if result, ok, err := s.replayed(normalized.IdempotencyKey, hash); ok || err != nil {
		return result, err
	}
	s.mutationMu.Lock()
	defer s.mutationMu.Unlock()
	if result, ok, err := s.replayed(normalized.IdempotencyKey, hash); ok || err != nil {
		return result, err
	}
	if s.homeWritesNeedRecovery() {
		return MutationResult{}, ErrRecoveryRequired
	}
	stored, err := s.reservationPlan(normalized.PlanID)
	if err != nil {
		return MutationResult{}, err
	}
	if stored.request.StateVersion != normalized.StateVersion || stored.request.MAC != normalized.MAC || !equalStringPointers(stored.request.ReservedIP, normalized.ReservedIP) {
		return MutationResult{}, ErrPlanMismatch
	}
	before, err := s.SnapshotHome(ctx)
	if err != nil {
		return MutationResult{}, err
	}
	if before.StateVersion != normalized.StateVersion {
		return MutationResult{}, ErrStaleState
	}
	if err := s.changeReservation(ctx, normalized.MAC, normalized.ReservedIP); err != nil {
		result := MutationResult{SchemaVersion: 1, Status: MutationRejected, Code: "mutation_failed"}
		s.remember(normalized.IdempotencyKey, hash, result)
		return result, nil
	}
	verified, verifyErr := s.SnapshotHome(ctx)
	if verifyErr == nil && reservationForDevices(verified.Devices, normalized.MAC) != pointerValue(normalized.ReservedIP) {
		verifyErr = ErrConflict
	}
	if verifyErr == nil {
		verifyErr = s.runAndValidate(ctx, SaveConfiguration())
	}
	var committed HomeDocument
	if verifyErr == nil {
		committed, verifyErr = s.SnapshotHome(ctx)
		if verifyErr == nil && reservationForDevices(committed.Devices, normalized.MAC) != pointerValue(normalized.ReservedIP) {
			verifyErr = ErrConflict
		}
	}
	if verifyErr != nil {
		result := s.rollbackReservation(ctx, normalized.MAC, stored.plan.BeforeIP, before.StateVersion)
		if result.Status == MutationUncertain {
			s.markHomeUncertain()
		}
		s.remember(normalized.IdempotencyKey, hash, result)
		return result, nil
	}
	result := MutationResult{SchemaVersion: 1, Status: MutationCommitted, Home: &committed}
	s.remember(normalized.IdempotencyKey, hash, result)
	return result, nil
}

func (s *Service) ReviewPeer(ctx context.Context, request PeerReviewRequest) (PeerPlan, error) {
	if s.wireGuardWritesNeedRecovery() {
		return PeerPlan{}, ErrRecoveryRequired
	}
	normalized, err := normalizePeerRequest(request)
	if err != nil {
		return PeerPlan{}, err
	}
	document, err := s.SnapshotWireGuard(ctx)
	if err != nil {
		return PeerPlan{}, err
	}
	if document.StateVersion != normalized.StateVersion {
		return PeerPlan{}, ErrStaleState
	}
	iface, ok := findInterface(document.Interfaces, normalized.InterfaceID)
	if !ok {
		return PeerPlan{}, ErrNotFound
	}
	before, after, err := reviewPeerChange(iface, normalized)
	if err != nil {
		return PeerPlan{}, err
	}
	plan := PeerPlan{SchemaVersion: 1, PlanID: s.newID(), ExpiresAt: s.clock().Add(planTTL), Request: normalized, Before: clonePeer(before), After: clonePeer(after)}
	s.stateMu.Lock()
	s.evictLocked()
	s.peerPlans[plan.PlanID] = peerStoredPlan{request: normalized, plan: plan}
	s.stateMu.Unlock()
	return plan, nil
}

func (s *Service) ApplyPeer(ctx context.Context, request PeerApplyRequest) (MutationResult, error) {
	normalizedReview, err := normalizePeerRequest(request.PeerReviewRequest)
	if err != nil {
		return MutationResult{}, err
	}
	request.PeerReviewRequest = normalizedReview
	if !uuidPattern.MatchString(request.IdempotencyKey) || request.PlanID == "" {
		return MutationResult{}, ErrInvalidRequest
	}
	hash := requestHash(request)
	if result, ok, err := s.replayed(request.IdempotencyKey, hash); ok || err != nil {
		return result, err
	}
	s.mutationMu.Lock()
	defer s.mutationMu.Unlock()
	if result, ok, err := s.replayed(request.IdempotencyKey, hash); ok || err != nil {
		return result, err
	}
	if s.wireGuardWritesNeedRecovery() {
		return MutationResult{}, ErrRecoveryRequired
	}
	stored, err := s.peerPlan(request.PlanID)
	if err != nil {
		return MutationResult{}, err
	}
	if !reflect.DeepEqual(stored.request, normalizedReview) {
		return MutationResult{}, ErrPlanMismatch
	}
	beforeDocument, err := s.SnapshotWireGuard(ctx)
	if err != nil {
		return MutationResult{}, err
	}
	if beforeDocument.StateVersion != normalizedReview.StateVersion {
		return MutationResult{}, ErrStaleState
	}
	if err := s.applyPeerCommands(ctx, stored.plan, false); err != nil {
		result := MutationResult{SchemaVersion: 1, Status: MutationRejected, Code: "mutation_failed"}
		s.remember(request.IdempotencyKey, hash, result)
		return result, nil
	}
	verified, verifyErr := s.SnapshotWireGuard(ctx)
	if verifyErr == nil {
		verifyErr = verifyPeerPlan(verified, stored.plan, false)
	}
	if verifyErr == nil {
		verifyErr = s.runAndValidate(ctx, SaveConfiguration())
	}
	var committed WireGuardDocument
	if verifyErr == nil {
		committed, verifyErr = s.SnapshotWireGuard(ctx)
		if verifyErr == nil {
			verifyErr = verifyPeerPlan(committed, stored.plan, false)
		}
	}
	if verifyErr != nil {
		result := s.rollbackPeer(ctx, stored.plan, beforeDocument)
		if result.Status == MutationUncertain {
			s.markWireGuardUncertain()
		} else if result.WireGuard != nil {
			s.storeWireGuardCache(*result.WireGuard)
		}
		s.remember(request.IdempotencyKey, hash, result)
		return result, nil
	}
	result := MutationResult{SchemaVersion: 1, Status: MutationCommitted, WireGuard: &committed}
	s.storeWireGuardCache(committed)
	s.remember(request.IdempotencyKey, hash, result)
	return result, nil
}

func (s *Service) homeWritesNeedRecovery() bool {
	s.stateMu.Lock()
	defer s.stateMu.Unlock()
	return s.homeUncertain
}

func (s *Service) wireGuardWritesNeedRecovery() bool {
	s.stateMu.Lock()
	defer s.stateMu.Unlock()
	return s.wireGuardUncertain
}

func (s *Service) markHomeUncertain() {
	s.stateMu.Lock()
	s.homeUncertain = true
	s.stateMu.Unlock()
}

func (s *Service) markWireGuardUncertain() {
	s.stateMu.Lock()
	s.wireGuardUncertain = true
	s.wireGuardCache = nil
	s.stateMu.Unlock()
}

func (s *Service) changeReservation(ctx context.Context, mac string, value *string) error {
	var raw string
	if value == nil {
		raw = "no ip dhcp host " + mac
	} else {
		raw = "ip dhcp host " + mac + " " + *value
	}
	command, err := Mutate(raw)
	if err != nil {
		return err
	}
	return s.runAndValidate(ctx, command)
}

func (s *Service) rollbackReservation(ctx context.Context, mac, beforeIP, _ string) MutationResult {
	rollbackCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer cancel()
	var value *string
	if beforeIP != "" {
		copyValue := beforeIP
		value = &copyValue
	}
	if err := s.changeReservation(rollbackCtx, mac, value); err != nil {
		return MutationResult{SchemaVersion: 1, Status: MutationUncertain, Code: "rollback_failed"}
	}
	if err := s.runAndValidate(rollbackCtx, SaveConfiguration()); err != nil {
		return MutationResult{SchemaVersion: 1, Status: MutationUncertain, Code: "rollback_failed"}
	}
	document, err := s.SnapshotHome(rollbackCtx)
	if err != nil || reservationForDevices(document.Devices, mac) != beforeIP {
		return MutationResult{SchemaVersion: 1, Status: MutationUncertain, Code: "rollback_unverified"}
	}
	return MutationResult{SchemaVersion: 1, Status: MutationRolledBack, Code: "commit_failed", Home: &document}
}

func (s *Service) applyPeerCommands(ctx context.Context, plan PeerPlan, rollback bool) error {
	commands, err := peerCommands(plan, rollback)
	if err != nil {
		return err
	}
	apply := func(raw string) error {
		command, err := Mutate(raw)
		if err != nil {
			return err
		}
		return s.runAndValidate(ctx, command)
	}
	if !rollback && plan.Request.Action == PeerRotate {
		if len(commands) < 2 {
			return ErrInvalidRequest
		}
		for _, raw := range commands[:len(commands)-1] {
			if err := apply(raw); err != nil {
				return err
			}
		}
		staged, err := s.SnapshotWireGuard(ctx)
		if err != nil {
			return err
		}
		if err := verifyPeerRotationStaged(staged, plan); err != nil {
			return err
		}
		return apply(commands[len(commands)-1])
	}
	for _, raw := range commands {
		if err := apply(raw); err != nil {
			return err
		}
	}
	return nil
}

func verifyPeerRotationStaged(document WireGuardDocument, plan PeerPlan) error {
	if plan.Request.Action != PeerRotate || plan.Before == nil || plan.After == nil {
		return ErrInvalidRequest
	}
	iface, ok := findInterface(document.Interfaces, plan.Request.InterfaceID)
	if !ok {
		return ErrConflict
	}
	var oldVerified, newVerified bool
	for _, peer := range iface.Peers {
		switch peer.PublicKey {
		case plan.Before.PublicKey:
			oldVerified = samePeerConfig(peer, *plan.Before)
		case plan.After.PublicKey:
			newVerified = samePeerConfig(peer, *plan.After)
		}
	}
	if !oldVerified || !newVerified {
		return ErrConflict
	}
	return nil
}

func (s *Service) rollbackPeer(ctx context.Context, plan PeerPlan, before WireGuardDocument) MutationResult {
	rollbackCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 8*time.Second)
	defer cancel()
	if err := s.applyPeerCommands(rollbackCtx, plan, true); err != nil {
		return MutationResult{SchemaVersion: 1, Status: MutationUncertain, Code: "rollback_failed"}
	}
	if err := s.runAndValidate(rollbackCtx, SaveConfiguration()); err != nil {
		return MutationResult{SchemaVersion: 1, Status: MutationUncertain, Code: "rollback_failed"}
	}
	document, err := s.SnapshotWireGuard(rollbackCtx)
	if err != nil || document.StateVersion != before.StateVersion {
		return MutationResult{SchemaVersion: 1, Status: MutationUncertain, Code: "rollback_unverified"}
	}
	return MutationResult{SchemaVersion: 1, Status: MutationRolledBack, Code: "commit_failed", WireGuard: &document}
}

func peerCommands(plan PeerPlan, rollback bool) ([]string, error) {
	request := plan.Request
	before, after := plan.Before, plan.After
	peerPrefix := "interface " + request.InterfaceID + " wireguard peer "
	remove := func(key string) string { return "interface " + request.InterfaceID + " no wireguard peer " + key }
	restore := func(peer *WireGuardPeer) []string {
		commands := []string{peerPrefix + peer.PublicKey + " !" + peer.Name}
		if peer.AllowedIP != "" {
			commands = append(commands, peerPrefix+peer.PublicKey+" allow-ips "+peer.AllowedIP+" 255.255.255.255")
		}
		commands = append(commands, peerPrefix+peer.PublicKey+" keepalive-interval "+strconv.Itoa(peer.Keepalive))
		if peer.Enabled {
			commands = append(commands, peerPrefix+peer.PublicKey+" connect")
		} else {
			commands = append(commands, peerPrefix+peer.PublicKey+" no connect")
		}
		return commands
	}
	if rollback {
		switch request.Action {
		case PeerCreate:
			return []string{remove(after.PublicKey)}, nil
		case PeerRename:
			return []string{peerPrefix + before.PublicKey + " !" + before.Name}, nil
		case PeerSetEnabled:
			if before.Enabled {
				return []string{peerPrefix + before.PublicKey + " connect"}, nil
			}
			return []string{peerPrefix + before.PublicKey + " no connect"}, nil
		case PeerRevoke:
			return restore(before), nil
		case PeerRotate:
			return append([]string{remove(after.PublicKey)}, restore(before)...), nil
		}
	}
	switch request.Action {
	case PeerCreate:
		return restore(after), nil
	case PeerRename:
		return []string{peerPrefix + before.PublicKey + " !" + after.Name}, nil
	case PeerSetEnabled:
		if after.Enabled {
			return []string{peerPrefix + before.PublicKey + " connect"}, nil
		}
		return []string{peerPrefix + before.PublicKey + " no connect"}, nil
	case PeerRevoke:
		return []string{remove(before.PublicKey)}, nil
	case PeerRotate:
		return append(restore(after), remove(before.PublicKey)), nil
	default:
		return nil, ErrInvalidRequest
	}
}

func (s *Service) runAndValidate(ctx context.Context, command Command) error {
	value, err := s.runner.Run(ctx, command)
	if err != nil {
		return err
	}
	return validateMutationResponse(value)
}

func validateMutationResponse(value []byte) error {
	var document struct {
		XMLName xml.Name `xml:"response"`
	}
	if err := decodeResponse(value, &document); err != nil {
		return err
	}
	if strings.Contains(strings.ToLower(string(value)), "<status>error</status>") {
		return ErrCommandFailed
	}
	return nil
}

func reviewPeerChange(iface WireGuardInterface, request PeerReviewRequest) (*WireGuardPeer, *WireGuardPeer, error) {
	find := func(key string) (*WireGuardPeer, bool) {
		for index := range iface.Peers {
			if iface.Peers[index].PublicKey == key {
				copyValue := iface.Peers[index]
				return &copyValue, true
			}
		}
		return nil, false
	}
	before, exists := find(request.PublicKey)
	switch request.Action {
	case PeerCreate:
		if exists {
			return nil, nil, ErrConflict
		}
		if _, exists := find(request.PublicKey); exists {
			return nil, nil, ErrConflict
		}
		if err := validatePeerAddress(iface, request.AllowedIP, ""); err != nil {
			return nil, nil, err
		}
		enabled := true
		if request.Enabled != nil {
			enabled = *request.Enabled
		}
		after := &WireGuardPeer{PublicKey: request.PublicKey, Name: request.Name, AllowedIP: request.AllowedIP, Keepalive: request.Keepalive, Enabled: enabled}
		return nil, after, nil
	case PeerRename:
		if !exists {
			return nil, nil, ErrNotFound
		}
		after := *before
		after.Name = request.Name
		return before, &after, nil
	case PeerSetEnabled:
		if !exists || request.Enabled == nil {
			return nil, nil, ErrInvalidRequest
		}
		after := *before
		after.Enabled = *request.Enabled
		return before, &after, nil
	case PeerRotate:
		if !exists {
			return nil, nil, ErrNotFound
		}
		if _, duplicate := find(request.NewPublicKey); duplicate {
			return nil, nil, ErrConflict
		}
		after := *before
		after.PublicKey = request.NewPublicKey
		after.Online = false
		after.LastHandshakeSec = nil
		after.RXBytes = 0
		after.TXBytes = 0
		return before, &after, nil
	case PeerRevoke:
		if !exists {
			return nil, nil, ErrNotFound
		}
		return before, nil, nil
	default:
		return nil, nil, ErrInvalidRequest
	}
}

func verifyPeerPlan(document WireGuardDocument, plan PeerPlan, rollback bool) error {
	iface, ok := findInterface(document.Interfaces, plan.Request.InterfaceID)
	if !ok {
		return ErrConflict
	}
	find := func(key string) (*WireGuardPeer, bool) {
		for index := range iface.Peers {
			if iface.Peers[index].PublicKey == key {
				return &iface.Peers[index], true
			}
		}
		return nil, false
	}
	if rollback {
		if plan.Before == nil {
			_, exists := find(plan.After.PublicKey)
			if exists {
				return ErrConflict
			}
			return nil
		}
		value, exists := find(plan.Before.PublicKey)
		if !exists || !samePeerConfig(*value, *plan.Before) {
			return ErrConflict
		}
		if plan.Request.Action == PeerRotate {
			if _, exists := find(plan.After.PublicKey); exists {
				return ErrConflict
			}
		}
		return nil
	}
	if plan.After == nil {
		if _, exists := find(plan.Before.PublicKey); exists {
			return ErrConflict
		}
		return nil
	}
	value, exists := find(plan.After.PublicKey)
	if !exists || !samePeerConfig(*value, *plan.After) {
		return ErrConflict
	}
	if plan.Request.Action == PeerRotate {
		if _, exists := find(plan.Before.PublicKey); exists {
			return ErrConflict
		}
	}
	return nil
}

func normalizeReservationReview(request ReservationReviewRequest) (ReservationReviewRequest, error) {
	mac, err := canonicalMAC(request.MAC)
	if err != nil || request.StateVersion == "" {
		return ReservationReviewRequest{}, ErrInvalidRequest
	}
	request.MAC = mac
	if request.ReservedIP != nil {
		ip, err := optionalIPv4(*request.ReservedIP)
		if err != nil || ip == "" {
			return ReservationReviewRequest{}, ErrInvalidRequest
		}
		request.ReservedIP = &ip
	}
	return request, nil
}

var uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

func normalizeReservationApply(request ReservationApplyRequest) (ReservationApplyRequest, error) {
	review, err := normalizeReservationReview(ReservationReviewRequest{StateVersion: request.StateVersion, MAC: request.MAC, ReservedIP: request.ReservedIP})
	if err != nil || request.PlanID == "" || !uuidPattern.MatchString(request.IdempotencyKey) {
		return ReservationApplyRequest{}, ErrInvalidRequest
	}
	request.StateVersion, request.MAC, request.ReservedIP = review.StateVersion, review.MAC, review.ReservedIP
	return request, nil
}

func normalizePeerRequest(request PeerReviewRequest) (PeerReviewRequest, error) {
	if request.StateVersion == "" || !interfacePattern.MatchString(request.InterfaceID) {
		return PeerReviewRequest{}, ErrInvalidRequest
	}
	if request.PublicKey != "" && !canonicalWireGuardKey(request.PublicKey) {
		return PeerReviewRequest{}, ErrInvalidRequest
	}
	if request.NewPublicKey != "" && !canonicalWireGuardKey(request.NewPublicKey) {
		return PeerReviewRequest{}, ErrInvalidRequest
	}
	if request.Name != "" && !peerNamePattern.MatchString(request.Name) {
		return PeerReviewRequest{}, ErrInvalidRequest
	}
	if request.Keepalive < 0 || request.Keepalive > 3600 {
		return PeerReviewRequest{}, ErrInvalidRequest
	}
	switch request.Action {
	case PeerCreate:
		if request.PublicKey == "" || request.Name == "" || request.AllowedIP == "" {
			return PeerReviewRequest{}, ErrInvalidRequest
		}
	case PeerRename:
		if request.PublicKey == "" || request.Name == "" {
			return PeerReviewRequest{}, ErrInvalidRequest
		}
	case PeerSetEnabled:
		if request.PublicKey == "" || request.Enabled == nil {
			return PeerReviewRequest{}, ErrInvalidRequest
		}
	case PeerRotate:
		if request.PublicKey == "" || request.NewPublicKey == "" || request.PublicKey == request.NewPublicKey {
			return PeerReviewRequest{}, ErrInvalidRequest
		}
	case PeerRevoke:
		if request.PublicKey == "" {
			return PeerReviewRequest{}, ErrInvalidRequest
		}
	default:
		return PeerReviewRequest{}, ErrInvalidRequest
	}
	return request, nil
}

func validateHomeAddress(device HomeDevice, value string, devices []HomeDevice) error {
	address, err := netip.ParseAddr(value)
	if err != nil || !address.Is4() {
		return ErrInvalidRequest
	}
	base, err := netip.ParseAddr(device.IP)
	if err != nil || !base.Is4() {
		return ErrInvalidRequest
	}
	a, b := address.As4(), base.As4()
	if a[0] != b[0] || a[1] != b[1] || a[2] != b[2] || a[3] < 2 || a[3] == 255 {
		return ErrInvalidRequest
	}
	for _, other := range devices {
		if other.MAC != device.MAC && other.ReservedIP == value {
			return ErrConflict
		}
	}
	return nil
}

func validatePeerAddress(iface WireGuardInterface, value, exceptKey string) error {
	address, err := netip.ParseAddr(value)
	if err != nil || !address.Is4() {
		return ErrInvalidRequest
	}
	inside := false
	for _, raw := range iface.Addresses {
		prefix, err := netip.ParsePrefix(raw)
		if err == nil && prefix.Contains(address) && address != prefix.Addr() {
			inside = true
		}
	}
	if !inside {
		return ErrInvalidRequest
	}
	for _, peer := range iface.Peers {
		if peer.PublicKey != exceptKey && peer.AllowedIP == value {
			return ErrConflict
		}
	}
	return nil
}

func (s *Service) reservationPlan(id string) (reservationStoredPlan, error) {
	s.stateMu.Lock()
	defer s.stateMu.Unlock()
	value, ok := s.reservationPlans[id]
	if !ok {
		return reservationStoredPlan{}, ErrPlanMismatch
	}
	if !s.clock().Before(value.plan.ExpiresAt) {
		delete(s.reservationPlans, id)
		return reservationStoredPlan{}, ErrPlanExpired
	}
	return value, nil
}

func (s *Service) peerPlan(id string) (peerStoredPlan, error) {
	s.stateMu.Lock()
	defer s.stateMu.Unlock()
	value, ok := s.peerPlans[id]
	if !ok {
		return peerStoredPlan{}, ErrPlanMismatch
	}
	if !s.clock().Before(value.plan.ExpiresAt) {
		delete(s.peerPlans, id)
		return peerStoredPlan{}, ErrPlanExpired
	}
	return value, nil
}

func (s *Service) replayed(key, hash string) (MutationResult, bool, error) {
	s.stateMu.Lock()
	defer s.stateMu.Unlock()
	value, ok := s.results[key]
	if !ok {
		return MutationResult{}, false, nil
	}
	if value.hash != hash {
		return MutationResult{}, false, ErrPlanMismatch
	}
	return value.result, true, nil
}

func (s *Service) remember(key, hash string, result MutationResult) {
	s.stateMu.Lock()
	defer s.stateMu.Unlock()
	if len(s.results) >= maxStoredResults {
		for existing := range s.results {
			delete(s.results, existing)
			break
		}
	}
	s.results[key] = storedResult{hash: hash, result: result}
}

func (s *Service) evictLocked() {
	now := s.clock()
	for id, plan := range s.reservationPlans {
		if !now.Before(plan.plan.ExpiresAt) {
			delete(s.reservationPlans, id)
		}
	}
	for id, plan := range s.peerPlans {
		if !now.Before(plan.plan.ExpiresAt) {
			delete(s.peerPlans, id)
		}
	}
	for len(s.reservationPlans)+len(s.peerPlans) >= maxStoredPlans {
		for id := range s.reservationPlans {
			delete(s.reservationPlans, id)
			return
		}
		for id := range s.peerPlans {
			delete(s.peerPlans, id)
			return
		}
	}
}

func homeStateVersion(devices []HomeDevice) string {
	type reservation struct{ MAC, IP string }
	values := make([]reservation, 0)
	for _, device := range devices {
		if device.ReservedIP != "" {
			values = append(values, reservation{device.MAC, device.ReservedIP})
		}
	}
	sort.Slice(values, func(i, j int) bool { return values[i].MAC < values[j].MAC })
	return stateHash(values)
}

func wireGuardStateVersion(interfaces []WireGuardInterface) string {
	type peer struct {
		PublicKey, Name, AllowedIP string
		Keepalive                  int
		Enabled                    bool
	}
	type iface struct {
		ID    string
		Peers []peer
	}
	values := make([]iface, 0, len(interfaces))
	for _, item := range interfaces {
		current := iface{ID: item.ID, Peers: make([]peer, 0, len(item.Peers))}
		for _, value := range item.Peers {
			current.Peers = append(current.Peers, peer{value.PublicKey, value.Name, value.AllowedIP, value.Keepalive, value.Enabled})
		}
		sort.Slice(current.Peers, func(i, j int) bool { return current.Peers[i].PublicKey < current.Peers[j].PublicKey })
		values = append(values, current)
	}
	sort.Slice(values, func(i, j int) bool { return values[i].ID < values[j].ID })
	return stateHash(values)
}

func stateHash(value any) string {
	encoded, _ := json.Marshal(value)
	sum := sha256.Sum256(encoded)
	return hex.EncodeToString(sum[:16])
}
func requestHash(value any) string { return stateHash(value) }
func pointerValue(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}
func equalStringPointers(left, right *string) bool {
	return pointerValue(left) == pointerValue(right) && (left == nil) == (right == nil)
}
func findDevice(values []HomeDevice, mac string) (HomeDevice, bool) {
	for _, value := range values {
		if value.MAC == mac {
			return value, true
		}
	}
	return HomeDevice{}, false
}
func findInterface(values []WireGuardInterface, id string) (WireGuardInterface, bool) {
	for _, value := range values {
		if value.ID == id {
			return value, true
		}
	}
	return WireGuardInterface{}, false
}
func reservationForDevices(values []HomeDevice, mac string) string {
	value, ok := findDevice(values, mac)
	if !ok {
		return ""
	}
	return value.ReservedIP
}
func clonePeer(value *WireGuardPeer) *WireGuardPeer {
	if value == nil {
		return nil
	}
	copyValue := *value
	copyValue.LastHandshakeSec = cloneInt64(value.LastHandshakeSec)
	return &copyValue
}
func samePeerConfig(left, right WireGuardPeer) bool {
	return left.PublicKey == right.PublicKey && left.Name == right.Name && left.AllowedIP == right.AllowedIP && left.Keepalive == right.Keepalive && left.Enabled == right.Enabled
}
