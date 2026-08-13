package historyproxy

import (
	"context"
	"encoding/base64"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const testPeerID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

func TestClientReadsLocalConfigurationAndReturnsStrictHistory(t *testing.T) {
	token := base64.StdEncoding.EncodeToString(make([]byte, 32))
	collector := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Authorization"); got != "Bearer "+token {
			t.Fatalf("authorization=%q", got)
		}
		if r.URL.Path != "/v1/peers/"+testPeerID+"/history" {
			t.Fatalf("path=%q", r.URL.Path)
		}
		query := r.URL.Query()
		if query.Get("from") != "100" || query.Get("to") != "200" || query.Get("resolution") != "raw" || query.Get("limit") != "100" {
			t.Fatalf("query=%v", query)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(w, validHistoryJSON(testPeerID))
	}))
	defer collector.Close()

	configPath := writeConfig(t, strings.TrimPrefix(collector.URL, "http://"), token)
	got, err := New(configPath).History(context.Background(), Query{
		PeerID: testPeerID, From: 100, To: 200, Resolution: "raw", Limit: 100,
	})
	if err != nil {
		t.Fatal(err)
	}
	if got.PeerID != testPeerID || got.ObservedSeconds != 100 || len(got.Points) != 1 {
		t.Fatalf("history=%+v", got)
	}
}

func TestClientRejectsNonLocalCollectorBeforeNetworkAccess(t *testing.T) {
	token := base64.StdEncoding.EncodeToString(make([]byte, 32))
	for _, address := range []string{"collector.example:18777", "8.8.8.8:18777", "0.0.0.0:18777"} {
		t.Run(address, func(t *testing.T) {
			_, err := New(writeConfig(t, address, token)).History(context.Background(), validQuery())
			if err == nil || !strings.Contains(err.Error(), "history unavailable") {
				t.Fatalf("error=%v", err)
			}
			if strings.Contains(err.Error(), address) || strings.Contains(err.Error(), token) {
				t.Fatalf("sensitive error=%q", err)
			}
		})
	}
}

func TestClientRejectsInvalidOrOversizedCollectorResponses(t *testing.T) {
	tests := map[string]string{
		"mismatched peer": validHistoryJSON(strings.Repeat("a", 64)),
		"unknown field":   strings.TrimSuffix(validHistoryJSON(testPeerID), "}") + `,"token":"leak"}`,
		"too many points": strings.Repeat("x", maximumHistoryResponseBytes+1),
	}
	for name, body := range tests {
		t.Run(name, func(t *testing.T) {
			collector := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				_, _ = fmt.Fprint(w, body)
			}))
			defer collector.Close()
			token := base64.StdEncoding.EncodeToString(make([]byte, 32))
			_, err := New(writeConfig(t, strings.TrimPrefix(collector.URL, "http://"), token)).History(context.Background(), validQuery())
			if err == nil || !strings.Contains(err.Error(), "history unavailable") {
				t.Fatalf("error=%v", err)
			}
			if strings.Contains(err.Error(), "leak") {
				t.Fatalf("upstream body leaked: %q", err)
			}
		})
	}
}

func TestClientRejectsInvalidQueryWithoutReadingConfiguration(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "missing.json")
	for name, query := range map[string]Query{
		"peer":            {PeerID: "bad", From: 1, To: 2, Resolution: "raw", Limit: 1},
		"range":           {PeerID: testPeerID, From: 2, To: 2, Resolution: "raw", Limit: 1},
		"resolution":      {PeerID: testPeerID, From: 1, To: 2, Resolution: "auto", Limit: 1},
		"limit":           {PeerID: testPeerID, From: 1, To: 2, Resolution: "raw", Limit: 2001},
		"bucket overflow": {PeerID: testPeerID, From: 1<<63 - 2, To: 1<<63 - 1, Resolution: "1h", Limit: 1},
	} {
		t.Run(name, func(t *testing.T) {
			_, err := New(missing).History(context.Background(), query)
			if err != ErrInvalidQuery {
				t.Fatalf("error=%v, want ErrInvalidQuery", err)
			}
		})
	}
}

func TestValidateHistoryAcceptsCollectorBucketAlignment(t *testing.T) {
	query := Query{PeerID: testPeerID, From: 101, To: 3700, Resolution: "1h", Limit: 100}
	history := History{
		PeerID: testPeerID, From: 0, To: 7200, Resolution: "1h",
		CoverageRatio: 0, Points: []Point{},
	}
	if err := ValidateHistory(query, history); err != nil {
		t.Fatalf("aligned history rejected: %v", err)
	}
}

func writeConfig(t *testing.T, address, token string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "config.json")
	content := fmt.Sprintf(`{"interface_id":"Wireguard0","listen_address":%q,"token":%q,"database_path":"/opt/var/lib/keenwg/history.db","raw_retention_days":7}`, address, token)
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func validQuery() Query {
	return Query{PeerID: testPeerID, From: 100, To: 200, Resolution: "raw", Limit: 100}
}

func validHistoryJSON(peerID string) string {
	return fmt.Sprintf(`{"peer_id":%q,"from":100,"to":200,"resolution":"raw","observed_seconds":100,"online_seconds":60,"last_online_at":150,"client_upload_bytes":12,"client_download_bytes":34,"counter_resets":0,"coverage_ratio":1,"points":[{"at":100,"observed_seconds":100,"online_seconds":60,"client_upload_bytes":12,"client_download_bytes":34}]}`, peerID)
}
