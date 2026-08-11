package auth

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"strings"
	"time"
)

func (s *FileStore) CreateBootstrapOffer(ctx context.Context, scope Scope, ttl time.Duration) (PlainOffer, error) {
	if err := ctx.Err(); err != nil {
		return PlainOffer{}, err
	}
	if scope != ScopeOwner {
		return PlainOffer{}, ErrInvalidScope
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.devices) != 0 {
		return PlainOffer{}, ErrAlreadyPaired
	}
	return s.createOfferLocked(scope, ttl)
}

func (s *FileStore) CreateOffer(ctx context.Context, requester, scope Scope, ttl time.Duration) (PlainOffer, error) {
	if err := ctx.Err(); err != nil {
		return PlainOffer{}, err
	}
	if requester != ScopeOwner {
		return PlainOffer{}, ErrForbidden
	}
	if !validScope(scope) {
		return PlainOffer{}, ErrInvalidScope
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.createOfferLocked(scope, ttl)
}

func (s *FileStore) RevokeOffer(ctx context.Context, id string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if id == "" {
		return ErrOfferNotFound
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	index := -1
	for i := range s.offers {
		if s.offers[i].ID == id {
			index = i
			break
		}
	}
	if index < 0 {
		return ErrOfferNotFound
	}
	removed := s.offers[index]
	s.offers = append(s.offers[:index], s.offers[index+1:]...)
	if err := s.persistOffers(); err != nil {
		s.offers = append(s.offers, Offer{})
		copy(s.offers[index+1:], s.offers[index:])
		s.offers[index] = removed
		return err
	}
	return nil
}

func (s *FileStore) createOfferLocked(scope Scope, ttl time.Duration) (PlainOffer, error) {
	if ttl <= 0 || ttl > maxOfferTTL {
		return PlainOffer{}, ErrInvalidTTL
	}
	id, err := randomText(s.random, 16)
	if err != nil {
		return PlainOffer{}, err
	}
	secret, err := randomText(s.random, 24)
	if err != nil {
		return PlainOffer{}, err
	}
	now := s.now().UTC()
	offer := Offer{ID: id, SecretHash: sha256.Sum256([]byte(secret)), Scope: scope, ExpiresAt: now.Add(ttl)}
	s.offers = append(s.offers, offer)
	if err := s.persistOffers(); err != nil {
		s.offers = s.offers[:len(s.offers)-1]
		return PlainOffer{}, err
	}
	return PlainOffer{ID: id, Secret: secret, Scope: scope, ExpiresAt: offer.ExpiresAt}, nil
}

func (s *FileStore) Exchange(ctx context.Context, id, secret, label string) (PlainCredential, error) {
	if err := ctx.Err(); err != nil {
		return PlainCredential{}, err
	}
	label = strings.TrimSpace(label)
	if !validLabel(label) {
		return PlainCredential{}, ErrInvalidLabel
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	// Pairing offers can also be issued by the short-lived root CLI used over
	// the separately verified SSH channel. Refresh the atomically persisted
	// offer document so the long-running HTTPS process can exchange it without
	// a service restart.
	offers, err := loadOffers(s.offerPath)
	if err != nil {
		return PlainCredential{}, err
	}
	s.offers = offers

	index := -1
	for i := range s.offers {
		if s.offers[i].ID == id {
			index = i
			break
		}
	}
	if index < 0 {
		return PlainCredential{}, ErrOfferNotFound
	}
	offer := &s.offers[index]
	if offer.UsedAt != nil {
		return PlainCredential{}, ErrOfferUsed
	}
	now := s.now().UTC()
	if now.After(offer.ExpiresAt) {
		return PlainCredential{}, ErrOfferExpired
	}
	digest := sha256.Sum256([]byte(secret))
	if subtle.ConstantTimeCompare(digest[:], offer.SecretHash[:]) != 1 {
		return PlainCredential{}, ErrUnauthorized
	}
	deviceID, err := randomText(s.random, 16)
	if err != nil {
		return PlainCredential{}, err
	}
	token, err := randomText(s.random, 32)
	if err != nil {
		return PlainCredential{}, err
	}
	usedAt := now
	offer.UsedAt = &usedAt
	if err := s.persistOffers(); err != nil {
		offer.UsedAt = nil
		return PlainCredential{}, err
	}
	device := Device{
		ID: deviceID, Label: label, Scope: offer.Scope,
		TokenHash: sha256.Sum256([]byte(token)), CreatedAt: now,
	}
	s.devices = append(s.devices, device)
	if err := s.persistDevices(); err != nil {
		s.devices = s.devices[:len(s.devices)-1]
		return PlainCredential{}, err
	}
	return PlainCredential{Device: publicDevice(device), Token: token}, nil
}
