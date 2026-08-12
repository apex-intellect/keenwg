package routerlocal

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"
)

const (
	oldPeerKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
	newPeerKey = "CAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg="
)

func TestServiceReservationReviewApplyIsTransactionalAndIdempotent(t *testing.T) {
	runner := newStatefulRunner(t)
	service := newTestService(runner)
	before, err := service.SnapshotHome(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	after := "192.168.1.40"
	review, err := service.ReviewReservation(context.Background(), ReservationReviewRequest{
		StateVersion: before.StateVersion,
		MAC:          "02:00:00:00:00:01",
		ReservedIP:   &after,
	})
	if err != nil {
		t.Fatal(err)
	}
	if runner.mutationCount != 0 {
		t.Fatalf("review mutated router %d times", runner.mutationCount)
	}
	request := ReservationApplyRequest{
		PlanID:         review.PlanID,
		StateVersion:   before.StateVersion,
		MAC:            "02:00:00:00:00:01",
		ReservedIP:     &after,
		IdempotencyKey: "d8fc0190-4194-4f0b-907f-8beae7f3527f",
	}
	result, err := service.ApplyReservation(context.Background(), request)
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != MutationCommitted || result.Home == nil {
		t.Fatalf("result = %+v", result)
	}
	if got := reservationFor(result.Home.Devices, request.MAC); got != after {
		t.Fatalf("reservation = %q, want %q", got, after)
	}
	mutations := runner.mutationCount
	replayed, err := service.ApplyReservation(context.Background(), request)
	if err != nil {
		t.Fatal(err)
	}
	if replayed.Status != MutationCommitted || runner.mutationCount != mutations {
		t.Fatalf("replay = %+v, mutations %d -> %d", replayed, mutations, runner.mutationCount)
	}
}

func TestServiceRejectsStaleReservationPlanWithoutMutation(t *testing.T) {
	runner := newStatefulRunner(t)
	service := newTestService(runner)
	document, err := service.SnapshotHome(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	ip := "192.168.1.41"
	plan, err := service.ReviewReservation(context.Background(), ReservationReviewRequest{StateVersion: document.StateVersion, MAC: "02:00:00:00:00:01", ReservedIP: &ip})
	if err != nil {
		t.Fatal(err)
	}
	runner.reservations["02:00:00:00:00:05"] = "192.168.1.31"
	_, err = service.ApplyReservation(context.Background(), ReservationApplyRequest{PlanID: plan.PlanID, StateVersion: document.StateVersion, MAC: "02:00:00:00:00:01", ReservedIP: &ip, IdempotencyKey: "48fb201a-a8e9-4d7c-a0f3-a967f732daf0"})
	if !errors.Is(err, ErrStaleState) {
		t.Fatalf("error = %v", err)
	}
	if runner.mutationCount != 0 {
		t.Fatalf("stale apply mutated router %d times", runner.mutationCount)
	}
}

func TestServicePeerRotationStagesBeforeRemoval(t *testing.T) {
	runner := newStatefulRunner(t)
	service := newTestService(runner)
	document, err := service.SnapshotWireGuard(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	request := PeerReviewRequest{StateVersion: document.StateVersion, InterfaceID: "Wireguard0", Action: PeerRotate, PublicKey: oldPeerKey, NewPublicKey: newPeerKey}
	plan, err := service.ReviewPeer(context.Background(), request)
	if err != nil {
		t.Fatal(err)
	}
	if runner.mutationCount != 0 {
		t.Fatalf("review mutated router %d times", runner.mutationCount)
	}
	result, err := service.ApplyPeer(context.Background(), PeerApplyRequest{PeerReviewRequest: request, PlanID: plan.PlanID, IdempotencyKey: "2b33f61c-6d51-48d8-b95b-53974d26bafa"})
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != MutationCommitted || result.WireGuard == nil {
		t.Fatalf("result = %+v", result)
	}
	created, removed := -1, -1
	for index, command := range runner.commands {
		if strings.Contains(command, "peer "+newPeerKey+" !") && created == -1 {
			created = index
		}
		if strings.Contains(command, "no wireguard peer "+oldPeerKey) {
			removed = index
		}
	}
	if created == -1 || removed == -1 || created >= removed {
		t.Fatalf("unsafe rotation order: %v", runner.commands)
	}
	stagedRead := -1
	for index := created + 1; index < removed; index++ {
		if runner.commands[index] == "show interface Wireguard0" {
			stagedRead = index
			break
		}
	}
	if stagedRead == -1 {
		t.Fatalf("new peer was not read back before old peer removal: %v", runner.commands)
	}
}

func TestServiceRollsBackPeerWhenSaveFails(t *testing.T) {
	runner := newStatefulRunner(t)
	service := newTestService(runner)
	document, err := service.SnapshotWireGuard(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	enabled := false
	reviewRequest := PeerReviewRequest{StateVersion: document.StateVersion, InterfaceID: "Wireguard0", Action: PeerSetEnabled, PublicKey: oldPeerKey, Enabled: &enabled}
	plan, err := service.ReviewPeer(context.Background(), reviewRequest)
	if err != nil {
		t.Fatal(err)
	}
	runner.failNextSave = true
	result, err := service.ApplyPeer(context.Background(), PeerApplyRequest{PeerReviewRequest: reviewRequest, PlanID: plan.PlanID, IdempotencyKey: "c83209f4-4e18-4978-9130-1472bc24ae61"})
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != MutationRolledBack {
		t.Fatalf("result = %+v", result)
	}
	if peer := runner.peers[oldPeerKey]; !peer.Enabled {
		t.Fatalf("peer was not restored: %+v", peer)
	}
}

func TestUncertainPeerMutationBlocksWritesUntilFreshInventory(t *testing.T) {
	runner := newStatefulRunner(t)
	service := newTestService(runner)
	document, err := service.SnapshotWireGuard(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	enabled := false
	request := PeerReviewRequest{StateVersion: document.StateVersion, InterfaceID: "Wireguard0", Action: PeerSetEnabled, PublicKey: oldPeerKey, Enabled: &enabled}
	plan, err := service.ReviewPeer(context.Background(), request)
	if err != nil {
		t.Fatal(err)
	}
	runner.failSaves = 2
	result, err := service.ApplyPeer(context.Background(), PeerApplyRequest{PeerReviewRequest: request, PlanID: plan.PlanID, IdempotencyKey: "2469afda-c823-4530-a66e-03834bb541bc"})
	if err != nil || result.Status != MutationUncertain {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	if _, err := service.ReviewPeer(context.Background(), request); !errors.Is(err, ErrRecoveryRequired) {
		t.Fatalf("review error=%v, want recovery required", err)
	}
	stable, err := service.RecoverWireGuard(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := service.ReviewPeer(context.Background(), PeerReviewRequest{StateVersion: stable.StateVersion, InterfaceID: "Wireguard0", Action: PeerSetEnabled, PublicKey: oldPeerKey, Enabled: &enabled}); err != nil {
		t.Fatalf("fresh inventory did not release write lock: %v", err)
	}
}

func TestUncertainReservationMutationBlocksReviewBeforeExplicitInventory(t *testing.T) {
	runner := newStatefulRunner(t)
	service := newTestService(runner)
	document, err := service.SnapshotHome(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	ip := "192.168.1.40"
	request := ReservationReviewRequest{StateVersion: document.StateVersion, MAC: "02:00:00:00:00:01", ReservedIP: &ip}
	plan, err := service.ReviewReservation(context.Background(), request)
	if err != nil {
		t.Fatal(err)
	}
	runner.failSaves = 2
	result, err := service.ApplyReservation(context.Background(), ReservationApplyRequest{PlanID: plan.PlanID, StateVersion: request.StateVersion, MAC: request.MAC, ReservedIP: request.ReservedIP, IdempotencyKey: "9a9e0204-b5b8-4efc-b793-44968c5ce26d"})
	if err != nil || result.Status != MutationUncertain {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	if _, err := service.ReviewReservation(context.Background(), request); !errors.Is(err, ErrRecoveryRequired) {
		t.Fatalf("review error=%v, want recovery required", err)
	}
}

func newTestService(runner Runner) *Service {
	ids := 0
	return newService(runner, func() time.Time { return time.Unix(1_800_000_000, 0) }, func() string { ids++; return fmt.Sprintf("plan-%d", ids) })
}

func reservationFor(devices []HomeDevice, mac string) string {
	for _, device := range devices {
		if device.MAC == mac {
			return device.ReservedIP
		}
	}
	return ""
}

type statefulRunner struct {
	t             *testing.T
	reservations  map[string]string
	peers         map[string]WireGuardPeer
	commands      []string
	mutationCount int
	failNextSave  bool
	failSaves     int
}

func newStatefulRunner(t *testing.T) *statefulRunner {
	t.Helper()
	return &statefulRunner{
		t:            t,
		reservations: map[string]string{"02:00:00:00:00:01": "192.168.1.10", "02:00:00:00:00:05": "192.168.1.30"},
		peers: map[string]WireGuardPeer{
			oldPeerKey: {PublicKey: oldPeerKey, Name: "artem_phone", AllowedIP: "10.8.0.2", Keepalive: 25, Enabled: true, Online: true, RXBytes: 1200, TXBytes: 800},
			"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=": {PublicKey: "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=", Name: "laptop", AllowedIP: "10.8.0.3", Keepalive: 25, Enabled: true},
		},
	}
}

func (r *statefulRunner) Run(_ context.Context, command Command) ([]byte, error) {
	switch command.value {
	case QueryHotspot().value:
		return fixture(r.t, "home.xml"), nil
	case QueryLeases().value:
		return fixture(r.t, "leases.xml"), nil
	case QueryRunningConfig().value:
		return []byte(r.runningXML()), nil
	case "show interface Wireguard0":
		r.commands = append(r.commands, command.value)
		return []byte(r.wireGuardXML()), nil
	case SaveConfiguration().value:
		r.commands = append(r.commands, command.value)
		if r.failSaves > 0 {
			r.failSaves--
			return nil, ErrCommandFailed
		}
		if r.failNextSave {
			r.failNextSave = false
			return nil, ErrCommandFailed
		}
		return []byte(`<response/>`), nil
	default:
		return r.mutate(command.value)
	}
}

func (r *statefulRunner) mutate(command string) ([]byte, error) {
	r.commands = append(r.commands, command)
	r.mutationCount++
	fields := strings.Fields(command)
	switch {
	case strings.HasPrefix(command, "ip dhcp host "):
		r.reservations[fields[3]] = fields[4]
	case strings.HasPrefix(command, "no ip dhcp host "):
		delete(r.reservations, fields[4])
	case strings.Contains(command, " no wireguard peer "):
		delete(r.peers, fields[5])
	case strings.Contains(command, " wireguard peer "):
		key := fields[4]
		peer := r.peers[key]
		peer.PublicKey = key
		suffix := fields[5:]
		switch {
		case len(suffix) == 1 && strings.HasPrefix(suffix[0], "!"):
			peer.Name = strings.TrimPrefix(suffix[0], "!")
		case len(suffix) == 3 && suffix[0] == "allow-ips":
			peer.AllowedIP = suffix[1]
		case len(suffix) == 2 && suffix[0] == "keepalive-interval":
			fmt.Sscanf(suffix[1], "%d", &peer.Keepalive)
		case len(suffix) == 1 && suffix[0] == "connect":
			peer.Enabled = true
		case len(suffix) == 2 && suffix[0] == "no" && suffix[1] == "connect":
			peer.Enabled = false
		default:
			return nil, ErrInvalidCommand
		}
		r.peers[key] = peer
	default:
		return nil, ErrInvalidCommand
	}
	return []byte(`<response/>`), nil
}

func (r *statefulRunner) runningXML() string {
	var out strings.Builder
	out.WriteString(`<response>`)
	for mac, ip := range r.reservations {
		fmt.Fprintf(&out, `<message>ip dhcp host %s %s</message>`, mac, ip)
	}
	out.WriteString(`<message>interface Wireguard0</message><message>ip address 10.8.0.1 255.255.255.0</message><message>wireguard listen-port 51820</message>`)
	for _, key := range sortedPeerKeys(r.peers) {
		peer := r.peers[key]
		fmt.Fprintf(&out, `<message>wireguard peer %s !%s</message><message>allow-ips %s 255.255.255.255</message><message>keepalive-interval %d</message>`, peer.PublicKey, peer.Name, peer.AllowedIP, peer.Keepalive)
		if peer.Enabled {
			out.WriteString(`<message>connect</message>`)
		} else {
			out.WriteString(`<message>no connect</message>`)
		}
		out.WriteString(`<message>!</message>`)
	}
	out.WriteString(`</response>`)
	return out.String()
}

func (r *statefulRunner) wireGuardXML() string {
	var out strings.Builder
	out.WriteString(`<response><interface><id>Wireguard0</id><mtu>1420</mtu><wireguard><public-key>BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=</public-key>`)
	for _, key := range sortedPeerKeys(r.peers) {
		peer := r.peers[key]
		fmt.Fprintf(&out, `<peer><public-key>%s</public-key><online>%t</online><enabled>%t</enabled><rxbytes>%d</rxbytes><txbytes>%d</txbytes></peer>`, key, peer.Online, peer.Enabled, peer.RXBytes, peer.TXBytes)
	}
	out.WriteString(`</wireguard></interface></response>`)
	return out.String()
}

func sortedPeerKeys(peers map[string]WireGuardPeer) []string {
	keys := make([]string, 0, len(peers))
	for key := range peers {
		keys = append(keys, key)
	}
	for i := 0; i < len(keys); i++ {
		for j := i + 1; j < len(keys); j++ {
			if keys[j] < keys[i] {
				keys[i], keys[j] = keys[j], keys[i]
			}
		}
	}
	return keys
}
