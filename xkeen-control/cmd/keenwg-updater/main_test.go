package main

import "testing"

func TestRequestPathIsFixed(t *testing.T) {
	if err := validateRequestPath("/opt/etc/keenwg/update/pending.json"); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"", "/tmp/pending.json", "/opt/etc/keenwg/update/../pending.json"} {
		if err := validateRequestPath(path); err == nil {
			t.Fatalf("unsafe path accepted: %q", path)
		}
	}
}

func TestSafeUpdaterErrorCodes(t *testing.T) {
	for _, value := range []string{"invalid_request", "verification_failed", "install_failed", "health_failed"} {
		if !safeErrorCode(value) {
			t.Fatalf("safe error rejected: %s", value)
		}
	}
	for _, value := range []string{"/opt/private", "token=secret", "arbitrary failure"} {
		if safeErrorCode(value) {
			t.Fatalf("unsafe error accepted: %s", value)
		}
	}
}
