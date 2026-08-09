package model

import "testing"

func TestPeerIDUsesInterfaceAndCanonicalPublicKey(t *testing.T) {
	got, err := PeerID("Wireguard0", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
	if err != nil {
		t.Fatal(err)
	}
	if want := "ffd4acdb594e6d7ada46e71a1e709a8e0d997570d40f7b61d99ba8c64069905f"; got != want {
		t.Fatalf("PeerID() = %q, want %q", got, want)
	}
}

func TestPeerIDRejectsNonCanonicalKeyWithoutEchoingIt(t *testing.T) {
	key := "not-a-secret-key"
	_, err := PeerID("Wireguard0", key)
	if err == nil {
		t.Fatal("PeerID() succeeded with invalid key")
	}
	if err.Error() == key {
		t.Fatal("PeerID() error exposed the supplied key")
	}
}

func TestPeerIDRejectsUnsafeInterfaceID(t *testing.T) {
	_, err := PeerID("Wireguard0; reboot", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
	if err == nil {
		t.Fatal("PeerID() accepted unsafe interface ID")
	}
}
