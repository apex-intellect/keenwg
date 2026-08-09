package history

import (
	"math"
	"strings"
	"testing"
	"time"

	"github.com/goldb/keenwg/collector/internal/model"
)

func ptr64(v int64) *int64 { return &v }

func runtimePeer(at int64, online bool, handshake *int64, rx, tx uint64) model.RuntimePeer {
	return model.RuntimePeer{
		PeerID: strings.Repeat("a", 64), InterfaceID: "Wireguard0", Online: online,
		HandshakeRaw: handshake, RouterRXBytes: rx, RouterTXBytes: tx, ObservedAt: time.Unix(at, 0),
	}
}

func TestNonIncreasingTimestampIsRejectedWithoutAdvancingState(t *testing.T) {
	r := NewReducer()
	first := r.Reduce(nil, runtimePeer(60, true, ptr64(1), 10, 20))
	got := r.Reduce(first.State(), runtimePeer(60, true, ptr64(2), 20, 30))
	if got.Accepted || got.ClientUploadBytes != 0 || !got.State().ObservedAt.Equal(first.State().ObservedAt) {
		t.Fatalf("non-increasing sample = %+v state=%+v", got, got.State())
	}
}

func TestLongGapDoesNotAccrueTraffic(t *testing.T) {
	r := NewReducer()
	first := r.Reduce(nil, runtimePeer(0, true, ptr64(1), 10, 20))
	got := r.Reduce(first.State(), runtimePeer(151, true, ptr64(2), 20, 30))
	if got.ClientUploadBytes != 0 || got.ClientDownloadBytes != 0 || got.OnlineDeltaSeconds != 0 || got.ObservedSeconds != 0 {
		t.Fatalf("long-gap sample accrued deltas: %+v", got)
	}
}

func TestCounterDeltaOverflowStartsResetGeneration(t *testing.T) {
	r := NewReducer()
	first := r.Reduce(nil, runtimePeer(0, true, ptr64(1), 0, 0))
	got := r.Reduce(first.State(), runtimePeer(60, true, ptr64(2), math.MaxUint64, 1))
	if got.ClientUploadBytes != 0 || got.ClientDownloadBytes != 0 || !got.CounterReset || got.CounterGeneration != 1 {
		t.Fatalf("overflow sample = %+v", got)
	}
}

func TestSentinelHandshakeIsInvalidNotFiftyYears(t *testing.T) {
	raw := int64(2_147_483_647)
	got := NewReducer().Reduce(nil, runtimePeer(60, false, &raw, 0, 0))
	if got.HandshakeKind != model.HandshakeInvalid || got.HandshakeAgeSeconds != nil {
		t.Fatalf("handshake = (%s,%v), want (invalid,nil)", got.HandshakeKind, got.HandshakeAgeSeconds)
	}
}

func TestCounterDecreaseStartsGenerationWithoutNegativeDelta(t *testing.T) {
	r := NewReducer()
	first := r.Reduce(nil, runtimePeer(0, true, ptr64(1), 1000, 2000))
	got := r.Reduce(first.State(), runtimePeer(60, true, ptr64(2), 8, 9))
	if got.ClientUploadBytes != 0 || got.ClientDownloadBytes != 0 || got.CounterGeneration != 1 || !got.CounterReset {
		t.Fatalf("reset sample = %+v", got)
	}
}

func TestClientDirectionsUseRouterPerspective(t *testing.T) {
	r := NewReducer()
	first := r.Reduce(nil, runtimePeer(0, true, ptr64(1), 100, 200))
	got := r.Reduce(first.State(), runtimePeer(60, true, ptr64(2), 120, 270))
	if got.ClientUploadBytes != 20 || got.ClientDownloadBytes != 70 {
		t.Fatalf("upload/download = %d/%d, want 20/70", got.ClientUploadBytes, got.ClientDownloadBytes)
	}
}

func TestOnlineDurationRequiresTwoOnlineSamplesWithinGap(t *testing.T) {
	r := NewReducer()
	first := r.Reduce(nil, runtimePeer(0, true, ptr64(1), 0, 0))
	second := r.Reduce(first.State(), runtimePeer(60, true, ptr64(2), 0, 0))
	late := r.Reduce(second.State(), runtimePeer(240, true, ptr64(3), 0, 0))
	if second.OnlineDeltaSeconds != 60 || late.OnlineDeltaSeconds != 0 {
		t.Fatalf("online deltas = %d/%d, want 60/0", second.OnlineDeltaSeconds, late.OnlineDeltaSeconds)
	}
}

func TestZeroHandshakeDistinguishesOnlineAndOffline(t *testing.T) {
	r := NewReducer()
	online := r.Reduce(nil, runtimePeer(1, true, ptr64(0), 0, 0))
	offline := r.Reduce(nil, runtimePeer(1, false, ptr64(0), 0, 0))
	if online.HandshakeKind != model.HandshakeJustNow || offline.HandshakeKind != model.HandshakeNever {
		t.Fatalf("kinds = %q/%q", online.HandshakeKind, offline.HandshakeKind)
	}
}

func TestNegativeHandshakeIsInvalid(t *testing.T) {
	got := NewReducer().Reduce(nil, runtimePeer(1, false, ptr64(-1), 0, 0))
	if got.HandshakeKind != model.HandshakeInvalid || got.HandshakeAgeSeconds != nil {
		t.Fatalf("negative handshake = (%q,%v)", got.HandshakeKind, got.HandshakeAgeSeconds)
	}
}
