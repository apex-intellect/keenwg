package auth

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestPairingExchangeIsOneUseAndPersistsOnlyHashes(t *testing.T) {
	store := newTestStore(t)
	offer, err := store.CreateBootstrapOffer(context.Background(), ScopeOwner, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	credential, err := store.Exchange(context.Background(), offer.ID, offer.Secret, " Pixel 9 ")
	if err != nil {
		t.Fatal(err)
	}
	if credential.Token == "" || credential.Device.ID == "" || credential.Device.Label != "Pixel 9" {
		t.Fatalf("invalid credential: %+v", credential)
	}
	if _, err := store.Exchange(context.Background(), offer.ID, offer.Secret, "Replay"); !errors.Is(err, ErrOfferUsed) {
		t.Fatalf("replay error = %v, want ErrOfferUsed", err)
	}
	authenticated, err := store.Authenticate(context.Background(), credential.Token)
	if err != nil {
		t.Fatal(err)
	}
	if authenticated.ID != credential.Device.ID {
		t.Fatalf("authenticated device %q, want %q", authenticated.ID, credential.Device.ID)
	}

	persisted := readStoreFiles(t, store)
	for _, plaintext := range []string{offer.Secret, credential.Token} {
		if strings.Contains(persisted, plaintext) {
			t.Fatalf("persisted store contains plaintext secret %q", plaintext)
		}
	}
	assertPrivateFile(t, store.devicePath)
	assertPrivateFile(t, store.offerPath)
}

func TestRunningStoreSeesPairingOfferCreatedByLocalCLIProcess(t *testing.T) {
	running := newTestStore(t)
	localCLI, err := NewFileStore(running.devicePath, running.offerPath)
	if err != nil {
		t.Fatal(err)
	}
	offer, err := localCLI.CreateBootstrapOffer(context.Background(), ScopeOwner, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}

	credential, err := running.Exchange(context.Background(), offer.ID, offer.Secret, "Phone")
	if err != nil {
		t.Fatalf("exchange offer created by local CLI: %v", err)
	}
	if credential.Device.Scope != ScopeOwner || credential.Token == "" {
		t.Fatalf("invalid credential: %+v", credential)
	}
}

func TestPairingExchangeRejectsExpiredOffer(t *testing.T) {
	store := newTestStore(t)
	clock := time.Date(2026, 8, 8, 12, 0, 0, 0, time.UTC)
	store.now = func() time.Time { return clock }
	offer, err := store.CreateBootstrapOffer(context.Background(), ScopeOwner, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	clock = clock.Add(time.Minute + time.Nanosecond)
	if _, err := store.Exchange(context.Background(), offer.ID, offer.Secret, "Late phone"); !errors.Is(err, ErrOfferExpired) {
		t.Fatalf("exchange error = %v, want ErrOfferExpired", err)
	}
}

func TestPairingOfferCanBeRevokedBeforeExchange(t *testing.T) {
	store := newTestStore(t)
	exchangeBootstrap(t, store, "Owner")
	offer, err := store.CreateOffer(context.Background(), ScopeOwner, ScopeViewer, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.RevokeOffer(context.Background(), offer.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Exchange(context.Background(), offer.ID, offer.Secret, "Viewer"); !errors.Is(err, ErrOfferNotFound) {
		t.Fatalf("exchange after revoke error = %v, want ErrOfferNotFound", err)
	}
	if err := store.RevokeOffer(context.Background(), offer.ID); !errors.Is(err, ErrOfferNotFound) {
		t.Fatalf("second revoke error = %v, want ErrOfferNotFound", err)
	}
}

func TestOnlyOwnerCreatesOffersAndLastOwnerCannotBeRevoked(t *testing.T) {
	store := newTestStore(t)
	firstOwner := exchangeBootstrap(t, store, "Owner one")
	if _, err := store.CreateOffer(context.Background(), ScopeOperator, ScopeViewer, 5*time.Minute); !errors.Is(err, ErrForbidden) {
		t.Fatalf("operator create error = %v, want ErrForbidden", err)
	}
	secondOffer, err := store.CreateOffer(context.Background(), ScopeOwner, ScopeOwner, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	secondOwner, err := store.Exchange(context.Background(), secondOffer.ID, secondOffer.Secret, "Owner two")
	if err != nil {
		t.Fatal(err)
	}
	viewerOffer, err := store.CreateOffer(context.Background(), ScopeOwner, ScopeViewer, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	viewer, err := store.Exchange(context.Background(), viewerOffer.ID, viewerOffer.Secret, "Tablet")
	if err != nil {
		t.Fatal(err)
	}

	if err := store.RevokeDevice(context.Background(), viewer.Device.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Authenticate(context.Background(), viewer.Token); !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("revoked token error = %v, want ErrUnauthorized", err)
	}
	if err := store.RevokeDevice(context.Background(), secondOwner.Device.ID); err != nil {
		t.Fatal(err)
	}
	if err := store.RevokeDevice(context.Background(), firstOwner.Device.ID); !errors.Is(err, ErrLastOwner) {
		t.Fatalf("last owner revoke error = %v, want ErrLastOwner", err)
	}
}

func TestCorruptStoreFailsClosed(t *testing.T) {
	dir := t.TempDir()
	devicePath := filepath.Join(dir, "devices.json")
	offerPath := filepath.Join(dir, "pairing-offers.json")
	if err := os.WriteFile(devicePath, []byte(`{"schema_version":1,"devices":[`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := NewFileStore(devicePath, offerPath); !errors.Is(err, ErrStoreCorrupt) {
		t.Fatalf("NewFileStore error = %v, want ErrStoreCorrupt", err)
	}
}

func TestAuthenticateThrottlesLastUsedWrites(t *testing.T) {
	store := newTestStore(t)
	clock := time.Date(2026, 8, 8, 12, 0, 0, 0, time.UTC)
	store.now = func() time.Time { return clock }
	credential := exchangeBootstrap(t, store, "Phone")
	if _, err := store.Authenticate(context.Background(), credential.Token); err != nil {
		t.Fatal(err)
	}
	first := findDevice(t, store, credential.Device.ID).LastUsed
	clock = clock.Add(30 * time.Second)
	if _, err := store.Authenticate(context.Background(), credential.Token); err != nil {
		t.Fatal(err)
	}
	if got := findDevice(t, store, credential.Device.ID).LastUsed; !got.Equal(first) {
		t.Fatalf("last_used changed inside throttle window: %s != %s", got, first)
	}
	clock = clock.Add(31 * time.Second)
	if _, err := store.Authenticate(context.Background(), credential.Token); err != nil {
		t.Fatal(err)
	}
	if got := findDevice(t, store, credential.Device.ID).LastUsed; !got.After(first) {
		t.Fatalf("last_used was not updated after throttle window: %s <= %s", got, first)
	}
}

func newTestStore(t *testing.T) *FileStore {
	t.Helper()
	dir := t.TempDir()
	store, err := NewFileStore(filepath.Join(dir, "devices.json"), filepath.Join(dir, "pairing-offers.json"))
	if err != nil {
		t.Fatal(err)
	}
	return store
}

func exchangeBootstrap(t *testing.T, store *FileStore, label string) PlainCredential {
	t.Helper()
	offer, err := store.CreateBootstrapOffer(context.Background(), ScopeOwner, 5*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	credential, err := store.Exchange(context.Background(), offer.ID, offer.Secret, label)
	if err != nil {
		t.Fatal(err)
	}
	return credential
}

func findDevice(t *testing.T, store *FileStore, id string) Device {
	t.Helper()
	devices, err := store.ListDevices(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	for _, device := range devices {
		if device.ID == id {
			return device
		}
	}
	t.Fatalf("device %q not found", id)
	return Device{}
}

func readStoreFiles(t *testing.T, store *FileStore) string {
	t.Helper()
	devices, err := os.ReadFile(store.devicePath)
	if err != nil {
		t.Fatal(err)
	}
	offers, err := os.ReadFile(store.offerPath)
	if err != nil {
		t.Fatal(err)
	}
	return string(devices) + string(offers)
}

func assertPrivateFile(t *testing.T, path string) {
	t.Helper()
	if runtime.GOOS == "windows" {
		return
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if got := info.Mode().Perm(); got != 0o600 {
		t.Fatalf("mode for %s = %o, want 600", path, got)
	}
}
