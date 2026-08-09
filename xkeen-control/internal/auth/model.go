package auth

import (
	"errors"
	"time"
)

type Scope string

const (
	ScopeOwner    Scope = "owner"
	ScopeOperator Scope = "operator"
	ScopeViewer   Scope = "viewer"
)

var (
	ErrUnauthorized   = errors.New("unauthorized")
	ErrForbidden      = errors.New("forbidden")
	ErrInvalidScope   = errors.New("invalid_scope")
	ErrInvalidLabel   = errors.New("invalid_label")
	ErrInvalidTTL     = errors.New("invalid_ttl")
	ErrOfferNotFound  = errors.New("offer_not_found")
	ErrOfferUsed      = errors.New("offer_used")
	ErrOfferExpired   = errors.New("offer_expired")
	ErrDeviceNotFound = errors.New("device_not_found")
	ErrLastOwner      = errors.New("last_owner")
	ErrStoreCorrupt   = errors.New("store_corrupt")
	ErrStoreIO        = errors.New("store_io")
	ErrAlreadyPaired  = errors.New("already_paired")
)

type Device struct {
	ID        string    `json:"id"`
	Label     string    `json:"label"`
	Scope     Scope     `json:"scope"`
	TokenHash [32]byte  `json:"-"`
	CreatedAt time.Time `json:"created_at"`
	LastUsed  time.Time `json:"last_used,omitempty"`
}

type Offer struct {
	ID         string
	SecretHash [32]byte
	Scope      Scope
	ExpiresAt  time.Time
	UsedAt     *time.Time
}

type PlainOffer struct {
	ID        string    `json:"offer_id"`
	Secret    string    `json:"secret"`
	Scope     Scope     `json:"scope"`
	ExpiresAt time.Time `json:"expires_at"`
}

type PlainCredential struct {
	Device Device `json:"device"`
	Token  string `json:"token"`
}
