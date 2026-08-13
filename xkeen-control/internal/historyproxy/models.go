package historyproxy

import (
	"encoding/hex"
	"errors"
	"math"
	"strings"
)

var (
	ErrInvalidQuery = errors.New("invalid history query")
	ErrUnavailable  = errors.New("history unavailable")
)

type Query struct {
	PeerID     string
	From       int64
	To         int64
	Resolution string
	Limit      int
}

type Point struct {
	At                  int64 `json:"at"`
	ObservedSeconds     int64 `json:"observed_seconds"`
	OnlineSeconds       int64 `json:"online_seconds"`
	ClientUploadBytes   int64 `json:"client_upload_bytes"`
	ClientDownloadBytes int64 `json:"client_download_bytes"`
}

type History struct {
	PeerID              string  `json:"peer_id"`
	From                int64   `json:"from"`
	To                  int64   `json:"to"`
	Resolution          string  `json:"resolution"`
	ObservedSeconds     int64   `json:"observed_seconds"`
	OnlineSeconds       int64   `json:"online_seconds"`
	LastOnlineAt        *int64  `json:"last_online_at"`
	ClientUploadBytes   int64   `json:"client_upload_bytes"`
	ClientDownloadBytes int64   `json:"client_download_bytes"`
	CounterResets       int64   `json:"counter_resets"`
	CoverageRatio       float64 `json:"coverage_ratio"`
	Points              []Point `json:"points"`
}

func ValidateQuery(query Query) error {
	decoded, err := hex.DecodeString(query.PeerID)
	if err != nil || len(decoded) != 32 || len(query.PeerID) != 64 || strings.ToLower(query.PeerID) != query.PeerID {
		return ErrInvalidQuery
	}
	if query.From < 0 || query.To <= query.From || query.To-query.From > 400*24*60*60 {
		return ErrInvalidQuery
	}
	if query.Resolution != "raw" && query.Resolution != "5m" && query.Resolution != "1h" {
		return ErrInvalidQuery
	}
	bucket := bucketSeconds(query.Resolution)
	if bucket > 0 && query.To > int64(1<<63-1)-(bucket-1) {
		return ErrInvalidQuery
	}
	if query.Limit < 1 || query.Limit > 2000 {
		return ErrInvalidQuery
	}
	return nil
}

func ValidateHistory(query Query, history History) error {
	expectedFrom, expectedTo := expectedWindow(query)
	if history.PeerID != query.PeerID || history.From != expectedFrom || history.To != expectedTo || history.Resolution != query.Resolution {
		return ErrUnavailable
	}
	if history.ObservedSeconds < 0 || history.OnlineSeconds < 0 || history.OnlineSeconds > history.ObservedSeconds ||
		history.ClientUploadBytes < 0 || history.ClientDownloadBytes < 0 || history.CounterResets < 0 ||
		math.IsNaN(history.CoverageRatio) || math.IsInf(history.CoverageRatio, 0) || history.CoverageRatio < 0 || history.CoverageRatio > 1 ||
		len(history.Points) > query.Limit {
		return ErrUnavailable
	}
	if history.LastOnlineAt != nil && (*history.LastOnlineAt < history.From || *history.LastOnlineAt >= history.To) {
		return ErrUnavailable
	}
	var previous int64 = -1
	for _, point := range history.Points {
		if point.At < history.From || point.At >= history.To || point.At <= previous ||
			point.ObservedSeconds < 0 || point.OnlineSeconds < 0 || point.OnlineSeconds > point.ObservedSeconds ||
			point.ClientUploadBytes < 0 || point.ClientDownloadBytes < 0 {
			return ErrUnavailable
		}
		previous = point.At
	}
	return nil
}

func expectedWindow(query Query) (int64, int64) {
	bucket := bucketSeconds(query.Resolution)
	if bucket == 0 {
		return query.From, query.To
	}
	from := (query.From / bucket) * bucket
	to := ((query.To + bucket - 1) / bucket) * bucket
	return from, to
}

func bucketSeconds(resolution string) int64 {
	switch resolution {
	case "5m":
		return 5 * 60
	case "1h":
		return 60 * 60
	default:
		return 0
	}
}
