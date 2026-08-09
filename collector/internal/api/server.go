package api

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/goldb/keenwg/collector/internal/history"
	"github.com/goldb/keenwg/collector/internal/model"
)

type Config struct {
	Address string
	Token   string
	Version string
}

type HistoryReader interface {
	History(context.Context, string, int64, int64, history.Resolution, int) (model.History, error)
}

type HealthReader interface {
	Health() model.Health
}

type handler struct {
	tokenHash [32]byte
	history   HistoryReader
	health    HealthReader
	version   string
	queries   chan struct{}
	limiter   *ipLimiter
}

func New(cfg Config, historyReader HistoryReader, healthReader HealthReader) *http.Server {
	h := &handler{
		tokenHash: sha256.Sum256([]byte(cfg.Token)), history: historyReader, health: healthReader,
		version: cfg.Version, queries: make(chan struct{}, 4), limiter: newIPLimiter(),
	}
	return &http.Server{
		Addr: cfg.Address, Handler: h, ReadHeaderTimeout: 3 * time.Second,
		ReadTimeout: 5 * time.Second, WriteTimeout: 10 * time.Second,
		IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8 << 10,
	}
}

func (h *handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if !h.limiter.allow(remoteIP(r.RemoteAddr), time.Now()) {
		writeError(w, r, http.StatusTooManyRequests, "rate_limited")
		return
	}
	path := r.URL.Path
	if path == "/v1/health" {
		if !allowReadMethod(w, r) {
			return
		}
		writeJSON(w, r, http.StatusOK, h.health.Health())
		return
	}
	if !h.authorized(r) {
		w.Header().Set("WWW-Authenticate", "Bearer")
		writeError(w, r, http.StatusUnauthorized, "unauthorized")
		return
	}
	if path == "/v1/meta" {
		if !allowReadMethod(w, r) {
			return
		}
		writeJSON(w, r, http.StatusOK, map[string]any{"version": h.version, "max_points": 2000})
		return
	}
	if strings.HasPrefix(path, "/v1/peers/") {
		h.handleHistory(w, r)
		return
	}
	writeError(w, r, http.StatusNotFound, "not_found")
}

func allowReadMethod(w http.ResponseWriter, r *http.Request) bool {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeError(w, r, http.StatusMethodNotAllowed, "method_not_allowed")
		return false
	}
	if r.ContentLength > 0 {
		writeError(w, r, http.StatusBadRequest, "body_not_allowed")
		return false
	}
	r.Body = http.MaxBytesReader(w, r.Body, 0)
	if _, err := io.ReadAll(r.Body); err != nil {
		writeError(w, r, http.StatusBadRequest, "body_not_allowed")
		return false
	}
	return true
}

func (h *handler) authorized(r *http.Request) bool {
	value := r.Header.Get("Authorization")
	if !strings.HasPrefix(value, "Bearer ") {
		return false
	}
	presented := sha256.Sum256([]byte(strings.TrimPrefix(value, "Bearer ")))
	return subtle.ConstantTimeCompare(presented[:], h.tokenHash[:]) == 1
}

func (h *handler) handleHistory(w http.ResponseWriter, r *http.Request) {
	if !allowReadMethod(w, r) {
		return
	}
	rest := strings.TrimPrefix(r.URL.Path, "/v1/peers/")
	if !strings.HasSuffix(rest, "/history") {
		writeError(w, r, http.StatusBadRequest, "invalid_peer_id")
		return
	}
	id := strings.TrimSuffix(rest, "/history")
	if !validPeerID(id) {
		writeError(w, r, http.StatusBadRequest, "invalid_peer_id")
		return
	}
	query := r.URL.Query()
	allowed := map[string]bool{"from": true, "to": true, "limit": true, "resolution": true}
	for key, values := range query {
		if !allowed[key] || len(values) != 1 {
			writeError(w, r, http.StatusBadRequest, "invalid_query")
			return
		}
	}
	if len(query["from"]) != 1 || len(query["to"]) != 1 || len(query["limit"]) != 1 {
		writeError(w, r, http.StatusBadRequest, "invalid_query")
		return
	}
	from, err1 := strconv.ParseInt(query.Get("from"), 10, 64)
	to, err2 := strconv.ParseInt(query.Get("to"), 10, 64)
	limit, err3 := strconv.Atoi(query.Get("limit"))
	if err1 != nil || err2 != nil || err3 != nil || from < 0 || to <= from || limit < 1 || limit > 2000 {
		writeError(w, r, http.StatusBadRequest, "invalid_range")
		return
	}
	resolution := history.Resolution(query.Get("resolution"))
	if resolution == "" || resolution == history.ResolutionAuto {
		switch span := to - from; {
		case span <= 7*24*3600:
			resolution = history.ResolutionRaw
		case span <= 90*24*3600:
			resolution = history.Resolution5M
		default:
			resolution = history.Resolution1H
		}
	}
	if resolution != history.ResolutionRaw && resolution != history.Resolution5M && resolution != history.Resolution1H {
		writeError(w, r, http.StatusBadRequest, "invalid_resolution")
		return
	}
	select {
	case h.queries <- struct{}{}:
		defer func() { <-h.queries }()
	default:
		writeError(w, r, http.StatusServiceUnavailable, "busy")
		return
	}
	result, err := h.history.History(r.Context(), id, from, to, resolution, limit)
	if err != nil {
		if errors.Is(err, context.Canceled) {
			return
		}
		writeError(w, r, http.StatusServiceUnavailable, "history_unavailable")
		return
	}
	writeJSON(w, r, http.StatusOK, result)
}

func validPeerID(id string) bool {
	if len(id) != 64 {
		return false
	}
	b, err := hex.DecodeString(id)
	return err == nil && len(b) == 32 && strings.ToLower(id) == id
}

func writeError(w http.ResponseWriter, r *http.Request, status int, code string) {
	writeJSON(w, r, status, map[string]any{"error": map[string]string{"code": code}})
}

func writeJSON(w http.ResponseWriter, r *http.Request, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if r.Method != http.MethodHead {
		_ = json.NewEncoder(w).Encode(value)
	}
}

type bucket struct {
	tokens float64
	last   time.Time
}

type ipLimiter struct {
	mu      sync.Mutex
	buckets map[string]bucket
}

func newIPLimiter() *ipLimiter { return &ipLimiter{buckets: make(map[string]bucket)} }

func (l *ipLimiter) allow(ip string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	b := l.buckets[ip]
	if b.last.IsZero() {
		b.tokens = 20
		b.last = now
	}
	b.tokens += now.Sub(b.last).Seconds() * 10
	if b.tokens > 20 {
		b.tokens = 20
	}
	b.last = now
	if b.tokens < 1 {
		l.buckets[ip] = b
		return false
	}
	b.tokens--
	l.buckets[ip] = b
	return true
}

func remoteIP(remote string) string {
	host, _, err := net.SplitHostPort(remote)
	if err == nil {
		return host
	}
	return remote
}
