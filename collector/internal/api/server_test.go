package api

import (
	"context"
	"encoding/json"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"testing"

	"github.com/goldb/keenwg/collector/internal/history"
	"github.com/goldb/keenwg/collector/internal/model"
)

type memoryReader struct {
	mu         sync.Mutex
	resolution history.Resolution
}

func (m *memoryReader) History(_ context.Context, peerID string, from, to int64, resolution history.Resolution, _ int) (model.History, error) {
	m.mu.Lock()
	m.resolution = resolution
	m.mu.Unlock()
	return model.History{PeerID: peerID, From: from, To: to, Resolution: string(resolution), Points: []model.HistoryPoint{{At: from}}}, nil
}

func (m *memoryReader) Resolution() history.Resolution {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.resolution
}

type memoryHealth struct{}

func (memoryHealth) Health() model.Health {
	return model.Health{Version: "0.3.0", Status: "ok", Storage: "ok"}
}

func newTestServer(t *testing.T, token string) (*httptest.Server, *memoryReader) {
	t.Helper()
	reader := &memoryReader{}
	server := New(Config{Token: token, Version: "0.3.0"}, reader, memoryHealth{})
	ts := httptest.NewServer(server.Handler)
	t.Cleanup(ts.Close)
	return ts, reader
}

func authorizedGet(t *testing.T, endpoint, token string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, endpoint, nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func TestHistoryRequiresBearerToken(t *testing.T) {
	ts, _ := newTestServer(t, "test-token")
	peer := strings.Repeat("a", 64)
	for _, tc := range []struct {
		auth string
		want int
	}{{"", 401}, {"Bearer wrong", 401}, {"Bearer test-token", 200}} {
		req, _ := http.NewRequest(http.MethodGet, ts.URL+"/v1/peers/"+peer+"/history?from=1&to=2&limit=10", nil)
		if tc.auth != "" {
			req.Header.Set("Authorization", tc.auth)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != tc.want {
			t.Fatalf("auth %q status = %d, want %d", tc.auth, resp.StatusCode, tc.want)
		}
	}
}

func TestHistoryRejectsNonHexPeerID(t *testing.T) {
	ts, _ := newTestServer(t, "test-token")
	for _, id := range []string{"../etc/passwd", strings.Repeat("a", 63), strings.Repeat("a", 65), strings.Repeat("z", 64)} {
		resp := authorizedGet(t, ts.URL+"/v1/peers/"+url.PathEscape(id)+"/history?from=1&to=2&limit=10", "test-token")
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("id %q status = %d", id, resp.StatusCode)
		}
		resp.Body.Close()
	}
}

func TestServerRejectsPostAndSetsNoStore(t *testing.T) {
	ts, _ := newTestServer(t, "test-token")
	peer := strings.Repeat("a", 64)
	post, _ := http.NewRequest(http.MethodPost, ts.URL+"/v1/peers/"+peer+"/history", strings.NewReader("x"))
	post.Header.Set("Authorization", "Bearer test-token")
	postResp, err := http.DefaultClient.Do(post)
	if err != nil {
		t.Fatal(err)
	}
	if postResp.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("POST status = %d", postResp.StatusCode)
	}
	postResp.Body.Close()
	getResp := authorizedGet(t, ts.URL+"/v1/peers/"+peer+"/history?from=1&to=2&limit=10", "test-token")
	if got := getResp.Header.Get("Cache-Control"); got != "no-store" {
		t.Fatalf("Cache-Control = %q", got)
	}
	getResp.Body.Close()
}

func TestHistoryRejectsChunkedBodyAndUnrecognizedQueryShape(t *testing.T) {
	ts, _ := newTestServer(t, "test-token")
	peer := strings.Repeat("d", 64)
	request, _ := http.NewRequest(http.MethodGet, ts.URL+"/v1/peers/"+peer+"/history?from=1&to=2&limit=10", io.NopCloser(strings.NewReader("x")))
	request.ContentLength = -1
	request.Header.Set("Authorization", "Bearer test-token")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("chunked body status = %d", response.StatusCode)
	}

	for _, query := range []string{
		"from=1&to=2&limit=10&sql=select",
		"from=1&from=2&to=3&limit=10",
		"from=1&to=2&limit=10&resolution=raw&resolution=1h",
	} {
		response = authorizedGet(t, ts.URL+"/v1/peers/"+peer+"/history?"+query, "test-token")
		response.Body.Close()
		if response.StatusCode != http.StatusBadRequest {
			t.Fatalf("query %q status = %d", query, response.StatusCode)
		}
	}
}

func TestHistoryValidatesLimitAndChoosesHourlyForLongRange(t *testing.T) {
	ts, reader := newTestServer(t, "test-token")
	peer := strings.Repeat("b", 64)
	resp := authorizedGet(t, ts.URL+"/v1/peers/"+peer+"/history?from=1&to=2&limit=2001", "test-token")
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("limit status = %d", resp.StatusCode)
	}
	resp.Body.Close()
	resp = authorizedGet(t, ts.URL+"/v1/peers/"+peer+"/history?from=1&to=8640001&limit=10", "test-token")
	resp.Body.Close()
	if reader.Resolution() != history.Resolution1H {
		t.Fatalf("resolution = %q, want 1h", reader.Resolution())
	}
}

func TestHistoryResolutionDoesNotOverflowAtMaximumEpoch(t *testing.T) {
	ts, reader := newTestServer(t, "test-token")
	peer := strings.Repeat("e", 64)
	resp := authorizedGet(t, ts.URL+"/v1/peers/"+peer+"/history?from=0&to="+strconv.FormatInt(math.MaxInt64, 10)+"&limit=10", "test-token")
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK || reader.Resolution() != history.Resolution1H {
		t.Fatalf("status/resolution = %d/%q", resp.StatusCode, reader.Resolution())
	}
}

func TestSuccessfulJSONNeverContainsRawKeyOrEndpoint(t *testing.T) {
	ts, _ := newTestServer(t, "test-token")
	peer := strings.Repeat("c", 64)
	resp := authorizedGet(t, ts.URL+"/v1/peers/"+peer+"/history?from=1&to=2&limit=10", "test-token")
	defer resp.Body.Close()
	var body any
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	keys := map[string]bool{}
	collectKeys(body, keys)
	if keys["public_key"] || keys["endpoint"] {
		t.Fatalf("unsafe JSON keys: %v", keys)
	}
}

func collectKeys(v any, keys map[string]bool) {
	switch x := v.(type) {
	case map[string]any:
		for k, child := range x {
			keys[k] = true
			collectKeys(child, keys)
		}
	case []any:
		for _, child := range x {
			collectKeys(child, keys)
		}
	}
}

func TestHealthIsUnauthenticatedMinimalAndHeadHasNoBody(t *testing.T) {
	ts, _ := newTestServer(t, "test-token")
	resp, err := http.Get(ts.URL + "/v1/health")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || strings.Contains(string(body), "test-token") || strings.Contains(string(body), "peer") {
		t.Fatalf("health status/body = %d %s", resp.StatusCode, body)
	}
	req, _ := http.NewRequest(http.MethodHead, ts.URL+"/v1/health", nil)
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ = io.ReadAll(resp.Body)
	resp.Body.Close()
	if len(body) != 0 {
		t.Fatalf("HEAD body = %q", body)
	}
}

func FuzzHistoryRequest(f *testing.F) {
	f.Add(strings.Repeat("a", 64), "1", "2", "10")
	f.Fuzz(func(t *testing.T, id, from, to, limit string) {
		reader := &memoryReader{}
		server := New(Config{Token: "test-token", Version: "test"}, reader, memoryHealth{})
		target := "/v1/peers/" + url.PathEscape(id) + "/history?from=" + url.QueryEscape(from) + "&to=" + url.QueryEscape(to) + "&limit=" + url.QueryEscape(limit)
		req := httptest.NewRequest(http.MethodGet, target, nil)
		req.Header.Set("Authorization", "Bearer test-token")
		recorder := httptest.NewRecorder()
		server.Handler.ServeHTTP(recorder, req)
		if recorder.Code < 200 || recorder.Code > 599 {
			t.Fatalf("invalid status %d", recorder.Code)
		}
	})
}
