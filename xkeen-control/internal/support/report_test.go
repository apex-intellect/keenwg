package support

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"strings"
	"testing"
	"time"
)

type fakeResolver struct {
	ips []net.IP
	err error
}

func (f fakeResolver) LookupIP(context.Context, string) ([]net.IP, error) {
	return append([]net.IP(nil), f.ips...), f.err
}

type fakeDialer struct {
	errors map[string]error
	calls  []string
}

func (f *fakeDialer) Dial(_ context.Context, network, address string) error {
	f.calls = append(f.calls, network+" "+address)
	return f.errors[network]
}

func TestBuildRecordsSupportedDNSIPv4IPv6TCPAndQUICBranches(t *testing.T) {
	now := time.Date(2026, 8, 9, 5, 10, 11, 0, time.UTC)
	dialer := &fakeDialer{errors: map[string]error{}}
	service := New(fakeResolver{ips: []net.IP{net.ParseIP("192.0.2.10"), net.ParseIP("2001:db8::10")}}, dialer, time.Second, func() time.Time { return now })

	bundle := service.Build(context.Background(), Input{
		Version: "0.9.0", StateVersion: 17, Active: true, NodeCount: 3,
		Target: &Target{Host: "edge.private.example", Port: 443, Transport: "quic"},
	})

	if bundle.SchemaVersion != 1 || bundle.GeneratedAt != now.Format(time.RFC3339) || len(bundle.Report.Checks) != 5 {
		t.Fatalf("bundle=%+v", bundle)
	}
	want := map[string]string{"dns": StatusOK, "ipv4": StatusOK, "ipv6": StatusOK, "tcp": StatusOK, "quic": StatusOK}
	for _, check := range bundle.Report.Checks {
		if want[check.Layer] != check.Status || check.Observation.At != bundle.GeneratedAt || check.Inference.At != bundle.GeneratedAt {
			t.Fatalf("check=%+v", check)
		}
		if check.Observation.Code == "" || check.Inference.Code == "" {
			t.Fatalf("missing evidence: %+v", check)
		}
	}
	if strings.Contains(bundle.ReviewText, "edge.private.example") || !strings.Contains(bundle.ReviewText, "DNS") {
		t.Fatalf("review=%q", bundle.ReviewText)
	}
	if got := strings.Join(dialer.calls, ","); !strings.Contains(got, "tcp4") || !strings.Contains(got, "udp4") {
		t.Fatalf("calls=%v", dialer.calls)
	}
}

func TestBuildRecordsFailedAndUnsupportedBranchesWithoutRawErrors(t *testing.T) {
	now := time.Date(2026, 8, 9, 5, 11, 0, 0, time.UTC)
	secret := "https://user:password@hidden.example/sub/private-token"
	service := New(fakeResolver{err: errors.New(secret)}, &fakeDialer{errors: map[string]error{}}, time.Second, func() time.Time { return now })

	bundle := service.Build(context.Background(), Input{Target: &Target{Host: "hidden.example", Port: 443, Transport: "tcp"}})

	statuses := map[string]string{}
	for _, check := range bundle.Report.Checks {
		statuses[check.Layer] = check.Status
	}
	if statuses["dns"] != StatusFailed || statuses["ipv4"] != StatusUnsupported || statuses["ipv6"] != StatusUnsupported || statuses["tcp"] != StatusUnsupported || statuses["quic"] != StatusUnsupported {
		t.Fatalf("statuses=%v", statuses)
	}
	body, _ := json.Marshal(bundle)
	if strings.Contains(string(body), secret) || strings.Contains(string(body), "hidden.example") || strings.Contains(string(body), "private-token") {
		t.Fatalf("secret leaked: %s", body)
	}
}

func TestSanitizeRemovesCredentialsURLsUUIDsKeysHostsMACsAndFullIPs(t *testing.T) {
	privateKey := strings.Repeat("A", 43) + "="
	raw := "Bearer live-token password=hunter2 https://user:pass@vpn.secret.example/sub/abc " +
		"550e8400-e29b-41d4-a716-446655440000 " + privateKey +
		" peer.secret.example 4c:3b:df:a6:1e:24 203.0.113.42 2001:db8::cafe"

	clean := Sanitize(raw)

	for _, secret := range []string{"live-token", "hunter2", "vpn.secret.example", "550e8400", privateKey, "peer.secret.example", "4c:3b", "203.0.113.42", "2001:db8::cafe"} {
		if strings.Contains(clean, secret) {
			t.Fatalf("%q leaked in %q", secret, clean)
		}
	}
	for _, marker := range []string{"[credential]", "[url]", "[uuid]", "[key]", "[host]", "[mac]", "[ip]"} {
		if !strings.Contains(clean, marker) {
			t.Fatalf("marker %q missing in %q", marker, clean)
		}
	}
}

func TestBuildNeverIncludesSubscriptionConfigurationNamesOrValues(t *testing.T) {
	secret := "https://vpn.example.test/sub/private"
	service := New(fakeResolver{}, &fakeDialer{errors: map[string]error{}}, time.Second, time.Now)

	bundle := service.Build(context.Background(), Input{Notes: []string{"subscription_url=" + secret}})
	body, err := json.Marshal(bundle)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(body), secret) || strings.Contains(string(body), "subscription_url") {
		t.Fatalf("subscription configuration leaked: %s", body)
	}
}

func TestBundleIsBoundedAndNotesAreReviewable(t *testing.T) {
	service := New(fakeResolver{ips: []net.IP{net.ParseIP("192.0.2.10")}}, &fakeDialer{errors: map[string]error{}}, time.Second, time.Now)
	notes := make([]string, 1000)
	for index := range notes {
		notes[index] = strings.Repeat("x", 1000) + " private.example"
	}

	bundle := service.Build(context.Background(), Input{Notes: notes})
	body, err := json.Marshal(bundle)
	if err != nil {
		t.Fatal(err)
	}
	if len(body) > MaxBundleBytes || len(bundle.ReviewText) > MaxReviewTextBytes || len(bundle.Report.Notes) > MaxNotes {
		t.Fatalf("json=%d text=%d notes=%d", len(body), len(bundle.ReviewText), len(bundle.Report.Notes))
	}
	if strings.Contains(string(body), "private.example") {
		t.Fatal("hostname leaked from notes")
	}
}

func TestReviewTextByteLimitHoldsForMultibyteInput(t *testing.T) {
	service := New(fakeResolver{}, &fakeDialer{errors: map[string]error{}}, time.Second, time.Now)
	notes := make([]string, MaxNotes)
	for index := range notes {
		notes[index] = strings.Repeat("😀", 2000)
	}

	bundle := service.Build(context.Background(), Input{Notes: notes})

	if len([]byte(bundle.ReviewText)) > MaxReviewTextBytes {
		t.Fatalf("review bytes=%d", len([]byte(bundle.ReviewText)))
	}
	if !json.Valid(mustJSON(t, bundle)) {
		t.Fatal("bundle JSON became invalid after UTF-8 truncation")
	}
}

func mustJSON(t *testing.T, value any) []byte {
	t.Helper()
	body, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	return body
}
