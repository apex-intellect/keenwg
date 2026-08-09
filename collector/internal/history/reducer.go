package history

import (
	"math"
	"time"

	"github.com/goldb/keenwg/collector/internal/model"
)

type Reducer struct{}

func NewReducer() *Reducer { return &Reducer{} }

type State struct {
	PeerID            string
	ObservedAt        time.Time
	Online            bool
	RouterRXBytes     uint64
	RouterTXBytes     uint64
	CounterGeneration int64
}

type ReducedSample struct {
	PeerID               string
	InterfaceID          string
	Label                string
	ObservedAt           time.Time
	Online               bool
	ObservedSeconds      int64
	OnlineDeltaSeconds   int64
	HandshakeRaw         *int64
	HandshakeKind        model.HandshakeKind
	HandshakeAgeSeconds  *int64
	ClientUploadBytes    int64
	ClientDownloadBytes  int64
	CounterGeneration    int64
	CounterReset         bool
	Accepted             bool
	currentRouterRXBytes uint64
	currentRouterTXBytes uint64
}

func (r *Reducer) Reduce(previous *State, current model.RuntimePeer) ReducedSample {
	result := ReducedSample{
		PeerID: current.PeerID, InterfaceID: current.InterfaceID, Label: current.Label,
		ObservedAt: current.ObservedAt, Online: current.Online, HandshakeRaw: current.HandshakeRaw,
		currentRouterRXBytes: current.RouterRXBytes, currentRouterTXBytes: current.RouterTXBytes, Accepted: true,
	}
	result.HandshakeKind, result.HandshakeAgeSeconds = normalizeHandshake(current.HandshakeRaw, current.Online)
	if previous == nil {
		return result
	}
	result.CounterGeneration = previous.CounterGeneration
	gap := current.ObservedAt.Unix() - previous.ObservedAt.Unix()
	if gap <= 0 {
		result.Accepted = false
		result.ObservedAt = previous.ObservedAt
		result.Online = previous.Online
		result.currentRouterRXBytes = previous.RouterRXBytes
		result.currentRouterTXBytes = previous.RouterTXBytes
		return result
	}
	if gap > 0 && gap <= 150 {
		result.ObservedSeconds = gap
		if previous.Online && current.Online {
			result.OnlineDeltaSeconds = gap
		}
	}
	rxDelta := current.RouterRXBytes - previous.RouterRXBytes
	txDelta := current.RouterTXBytes - previous.RouterTXBytes
	if current.RouterRXBytes < previous.RouterRXBytes || current.RouterTXBytes < previous.RouterTXBytes || rxDelta > math.MaxInt64 || txDelta > math.MaxInt64 {
		result.CounterGeneration++
		result.CounterReset = true
		return result
	}
	if gap <= 150 {
		result.ClientUploadBytes = int64(rxDelta)
		result.ClientDownloadBytes = int64(txDelta)
	}
	return result
}

func (s ReducedSample) State() *State {
	return &State{
		PeerID: s.PeerID, ObservedAt: s.ObservedAt, Online: s.Online,
		RouterRXBytes: s.currentRouterRXBytes, RouterTXBytes: s.currentRouterTXBytes,
		CounterGeneration: s.CounterGeneration,
	}
}

func normalizeHandshake(raw *int64, online bool) (model.HandshakeKind, *int64) {
	switch {
	case raw == nil:
		return model.HandshakeUnknown, nil
	case *raw < 0:
		return model.HandshakeInvalid, nil
	case *raw >= 1_000_000_000:
		return model.HandshakeInvalid, nil
	case *raw == 0 && online:
		age := int64(0)
		return model.HandshakeJustNow, &age
	case *raw == 0:
		return model.HandshakeNever, nil
	default:
		age := *raw
		return model.HandshakeAge, &age
	}
}
