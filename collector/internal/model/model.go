package model

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"regexp"
	"time"
)

var (
	ErrInvalidInterfaceID = errors.New("invalid interface id")
	ErrInvalidPublicKey   = errors.New("invalid WireGuard public key")
	interfaceIDPattern    = regexp.MustCompile(`^[A-Za-z0-9/_-]{1,64}$`)
)

type RuntimePeer struct {
	PeerID        string
	InterfaceID   string
	Label         string
	Online        bool
	Enabled       bool
	HandshakeRaw  *int64
	RouterRXBytes uint64
	RouterTXBytes uint64
	ObservedAt    time.Time
}

func ValidateInterfaceID(interfaceID string) error {
	if !interfaceIDPattern.MatchString(interfaceID) {
		return ErrInvalidInterfaceID
	}
	return nil
}

func PeerID(interfaceID, publicKey string) (string, error) {
	if err := ValidateInterfaceID(interfaceID); err != nil {
		return "", err
	}
	raw, err := base64.StdEncoding.Strict().DecodeString(publicKey)
	if err != nil || len(raw) != 32 || base64.StdEncoding.EncodeToString(raw) != publicKey {
		return "", ErrInvalidPublicKey
	}
	sum := sha256.Sum256([]byte(interfaceID + "\n" + publicKey))
	return hex.EncodeToString(sum[:]), nil
}

type HandshakeKind string

const (
	HandshakeUnknown HandshakeKind = "unknown"
	HandshakeInvalid HandshakeKind = "invalid"
	HandshakeJustNow HandshakeKind = "just_now"
	HandshakeNever   HandshakeKind = "never"
	HandshakeAge     HandshakeKind = "age"
)

type HistoryPoint struct {
	At                  int64 `json:"at"`
	ObservedSeconds     int64 `json:"observed_seconds"`
	OnlineSeconds       int64 `json:"online_seconds"`
	ClientUploadBytes   int64 `json:"client_upload_bytes"`
	ClientDownloadBytes int64 `json:"client_download_bytes"`
}

type History struct {
	PeerID              string         `json:"peer_id"`
	From                int64          `json:"from"`
	To                  int64          `json:"to"`
	Resolution          string         `json:"resolution"`
	ObservedSeconds     int64          `json:"observed_seconds"`
	OnlineSeconds       int64          `json:"online_seconds"`
	LastOnlineAt        *int64         `json:"last_online_at"`
	ClientUploadBytes   int64          `json:"client_upload_bytes"`
	ClientDownloadBytes int64          `json:"client_download_bytes"`
	CounterResets       int64          `json:"counter_resets"`
	CoverageRatio       float64        `json:"coverage_ratio"`
	Points              []HistoryPoint `json:"points"`
}

type Health struct {
	Version      string `json:"version"`
	Status       string `json:"status"`
	Stale        bool   `json:"stale"`
	LastSampleAt *int64 `json:"last_sample_at"`
	Storage      string `json:"storage"`
}
