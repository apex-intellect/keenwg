package daemon

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"path"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/goldb/keenwg/collector/internal/history"
	"github.com/goldb/keenwg/collector/internal/model"
	collectorsource "github.com/goldb/keenwg/collector/internal/source"
)

type Clock interface {
	Now() time.Time
	After(time.Duration) <-chan time.Time
}

type realClock struct{}

func (realClock) Now() time.Time                         { return time.Now() }
func (realClock) After(d time.Duration) <-chan time.Time { return time.After(d) }

type Source interface {
	Run(context.Context, string) ([]model.RuntimePeer, error)
}
type Store interface {
	Append(context.Context, []history.ReducedSample) error
	Maintain(context.Context, time.Time) error
	Flush(context.Context) error
	Close() error
}
type Listener interface {
	Serve() error
	// Shutdown returns nil only after every active handler has finished.
	Shutdown(context.Context) error
	// Close forcibly stops new work and returns only after active handlers finish.
	Close() error
}

type Config struct {
	InterfaceID     string
	Clock           Clock
	Jitter          func() time.Duration
	State           *State
	MaxBufferedRows int
	FlushInterval   time.Duration
	// ShutdownTimeout bounds the entire listener and persistence shutdown sequence.
	ShutdownTimeout time.Duration
	Probe           <-chan struct{}
}

// The Entware init script waits 10 seconds after TERM before escalation.
// Keep shutdown inside one shorter window so scheduler jitter cannot trigger KILL.
const defaultShutdownTimeout = 8 * time.Second

type State struct {
	mu            sync.RWMutex
	health        model.Health
	peers         []model.RuntimePeer
	source        sourceCondition
	storageIssues storageIssue
}

type sourceCondition uint8

const (
	sourceStarting sourceCondition = iota
	sourceHealthy
	sourceUnavailable
	sourceSchemaUnsupported
)

type storageIssue uint8

const (
	storageAppendIssue storageIssue = 1 << iota
	storageMaintenanceIssue
	storageCapacityIssue
	storageFlushIssue
)

func NewState(version string) *State {
	return &State{health: model.Health{Version: version, Status: "starting", Stale: true, Storage: "ok"}, source: sourceStarting}
}
func (s *State) Health() model.Health { s.mu.RLock(); defer s.mu.RUnlock(); return s.health }
func (s *State) Peers() []model.RuntimePeer {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return append([]model.RuntimePeer(nil), s.peers...)
}
func (s *State) success(peers []model.RuntimePeer, now time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	ts := now.Unix()
	s.peers = append([]model.RuntimePeer(nil), peers...)
	s.source = sourceHealthy
	s.health.LastSampleAt = &ts
	s.recomputeHealthLocked()
}
func (s *State) sourceFailed(err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if errors.Is(err, collectorsource.ErrUnsupportedSchema) {
		s.source = sourceSchemaUnsupported
	} else {
		s.source = sourceUnavailable
	}
	s.recomputeHealthLocked()
}
func (s *State) setStorageIssue(issue storageIssue, active bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if active {
		s.storageIssues |= issue
	} else {
		s.storageIssues &^= issue
	}
	s.recomputeHealthLocked()
}

func (s *State) recomputeHealthLocked() {
	if s.storageIssues != 0 {
		s.health.Storage = "degraded"
	} else {
		s.health.Storage = "ok"
	}
	switch s.source {
	case sourceSchemaUnsupported:
		s.health.Status = "source_schema_unsupported"
		s.health.Stale = true
	case sourceUnavailable:
		s.health.Status = "source_unavailable"
		s.health.Stale = true
	case sourceHealthy:
		s.health.Stale = false
		if s.storageIssues != 0 {
			s.health.Status = "degraded"
		} else {
			s.health.Status = "ok"
		}
	default:
		s.health.Stale = true
		if s.storageIssues != 0 {
			s.health.Status = "degraded"
		} else {
			s.health.Status = "starting"
		}
	}
}

func Run(ctx context.Context, cfg Config, source Source, store Store, listener Listener) (runErr error) {
	if cfg.Clock == nil {
		cfg.Clock = realClock{}
	}
	if cfg.Jitter == nil {
		cfg.Jitter = func() time.Duration { return time.Duration(time.Now().UnixNano()%11-5) * time.Second }
	}
	if cfg.State == nil {
		cfg.State = NewState("unknown")
	}
	if cfg.MaxBufferedRows <= 0 {
		cfg.MaxBufferedRows = 500
	}
	if cfg.FlushInterval <= 0 {
		cfg.FlushInterval = 5 * time.Minute
	}
	if cfg.ShutdownTimeout <= 0 {
		cfg.ShutdownTimeout = defaultShutdownTimeout
	}
	serveDone := make(chan error, 1)
	serveExited := false
	if listener != nil {
		go func() { serveDone <- listener.Serve() }()
	}
	states := map[string]*history.State{}
	pending := make([]history.ReducedSample, 0, cfg.MaxBufferedRows)
	reducer := history.NewReducer()
	lastFlush := cfg.Clock.Now()
	lastMaintenanceDay := ""
	failures := 0
	storageReporter, reportsStorage := store.(interface{ StorageState() history.StorageStatus })
	syncStorageCapacity := func() {
		if reportsStorage {
			cfg.State.setStorageIssue(storageCapacityIssue, storageReporter.StorageState() == history.StorageDegraded)
		}
	}
	syncStorageCapacity()

	flush := func(flushCtx context.Context) error {
		if len(pending) > 0 {
			if err := store.Append(flushCtx, pending); err != nil {
				if errors.Is(err, history.ErrStorageDegraded) {
					cfg.State.setStorageIssue(storageCapacityIssue, true)
				} else {
					cfg.State.setStorageIssue(storageAppendIssue, true)
				}
				syncStorageCapacity()
				return fmt.Errorf("append buffered history: %w", err)
			}
			pending = pending[:0]
			cfg.State.setStorageIssue(storageAppendIssue, false)
			if !reportsStorage {
				cfg.State.setStorageIssue(storageCapacityIssue, false)
			}
		}
		lastFlush = cfg.Clock.Now()
		syncStorageCapacity()
		return nil
	}
	shutdown := func() {
		totalCtx, cancelTotal := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
		defer cancelTotal()
		recordError := func(err error) {
			if err != nil {
				runErr = errors.Join(runErr, err)
			}
		}
		await := func(waitCtx context.Context, operation func() error) (error, bool) {
			done := make(chan error, 1)
			go func() { done <- operation() }()
			select {
			case err := <-done:
				return err, true
			case <-waitCtx.Done():
				select {
				case err := <-done:
					return err, true
				default:
					return waitCtx.Err(), false
				}
			}
		}

		listenerQuiesced := listener == nil
		if listener != nil {
			gracefulBudget := cfg.ShutdownTimeout / 2
			if gracefulBudget <= 0 {
				gracefulBudget = time.Nanosecond
			}
			gracefulCtx, cancelGraceful := context.WithTimeout(totalCtx, gracefulBudget)
			shutdownErr, completed := await(gracefulCtx, func() error { return listener.Shutdown(gracefulCtx) })
			cancelGraceful()
			if completed {
				recordError(wrapError("shutdown collector API", shutdownErr))
				listenerQuiesced = shutdownErr == nil
			} else {
				recordError(fmt.Errorf("shutdown collector API: %w", shutdownErr))
			}

			if !listenerQuiesced {
				forceCloseBudget := cfg.ShutdownTimeout / 4
				if forceCloseBudget <= 0 {
					forceCloseBudget = time.Nanosecond
				}
				forceCtx, cancelForce := context.WithTimeout(totalCtx, forceCloseBudget)
				closeErr, closed := await(forceCtx, listener.Close)
				cancelForce()
				if closed {
					recordError(wrapError("force close collector API", closeErr))
					listenerQuiesced = true
				} else {
					recordError(fmt.Errorf("force close collector API: %w", closeErr))
				}
			}

			if !serveExited {
				select {
				case err := <-serveDone:
					serveExited = true
					recordError(wrapError("serve collector API", err))
				default:
				}
			}
		}

		if !listenerQuiesced {
			return
		}
		recordError(flush(totalCtx))
		if err := store.Flush(totalCtx); err != nil {
			cfg.State.setStorageIssue(storageFlushIssue, true)
			recordError(fmt.Errorf("flush history storage: %w", err))
		}
		closeDone := make(chan error, 1)
		go func() { closeDone <- store.Close() }()
		select {
		case err := <-closeDone:
			recordError(wrapError("close history storage", err))
		case <-totalCtx.Done():
			recordError(fmt.Errorf("close history storage: %w", totalCtx.Err()))
		}
	}
	defer shutdown()

	for {
		now := cfg.Clock.Now()
		peers, err := source.Run(ctx, cfg.InterfaceID)
		var wait time.Duration
		if err != nil {
			cfg.State.sourceFailed(err)
			failures++
			wait = backoff(failures)
		} else {
			failures = 0
			wait = time.Minute
			for i := range peers {
				if peers[i].ObservedAt.IsZero() {
					peers[i].ObservedAt = now
				}
			}
			cfg.State.success(peers, now)
			if now.Unix() >= 1_704_067_200 {
				pollWritable := true
				for _, peer := range peers {
					if len(pending) >= cfg.MaxBufferedRows && flush(ctx) != nil {
						pollWritable = false
						break
					}
					reduced := reducer.Reduce(states[peer.PeerID], peer)
					if reduced.Accepted {
						pending = append(pending, reduced)
						states[peer.PeerID] = reduced.State()
					}
				}
				day := now.Format("2006-01-02")
				if day != lastMaintenanceDay {
					if err := store.Maintain(ctx, now); err != nil {
						cfg.State.setStorageIssue(storageMaintenanceIssue, true)
						syncStorageCapacity()
					} else {
						lastMaintenanceDay = day
						cfg.State.setStorageIssue(storageMaintenanceIssue, false)
						syncStorageCapacity()
					}
				}
				if pollWritable {
					if len(pending) >= cfg.MaxBufferedRows || now.Sub(lastFlush) >= cfg.FlushInterval {
						flush(ctx)
					}
				}
			}
		}
		wait += cfg.Jitter()
		if wait < time.Second {
			wait = time.Second
		}
		select {
		case <-ctx.Done():
			return nil
		case <-cfg.Probe:
			continue
		case err := <-serveDone:
			serveExited = true
			if err != nil {
				return fmt.Errorf("serve collector API: %w", err)
			}
			return nil
		case <-cfg.Clock.After(wait):
		}
	}
}

func wrapError(operation string, err error) error {
	if err == nil {
		return nil
	}
	return fmt.Errorf("%s: %w", operation, err)
}

func backoff(failures int) time.Duration {
	seconds := 60
	for i := 1; i < failures && seconds < 300; i++ {
		seconds *= 2
		if seconds > 300 {
			seconds = 300
		}
	}
	return time.Duration(seconds) * time.Second
}

type RuntimeConfig struct {
	InterfaceID             string `json:"interface_id"`
	ListenAddress           string `json:"listen_address"`
	Token                   string `json:"token"`
	DatabasePath            string `json:"database_path"`
	RawRetentionDays        int    `json:"raw_retention_days,omitempty"`
	FiveMinuteRetentionDays int    `json:"five_minute_retention_days,omitempty"`
	HourlyRetentionDays     int    `json:"hourly_retention_days,omitempty"`
	MaxDatabaseBytes        int64  `json:"max_database_bytes,omitempty"`
}

const maximumDatabaseBytes int64 = 1 << 30

func DecodeConfig(reader io.Reader) (RuntimeConfig, error) {
	var cfg RuntimeConfig
	decoder := json.NewDecoder(reader)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&cfg); err != nil {
		return cfg, fmt.Errorf("decode config: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return cfg, errors.New("config contains trailing JSON")
	}
	if err := model.ValidateInterfaceID(cfg.InterfaceID); err != nil {
		return cfg, err
	}
	host, portText, err := net.SplitHostPort(cfg.ListenAddress)
	if err != nil {
		return cfg, errors.New("invalid listen_address")
	}
	ip := net.ParseIP(host)
	port, portErr := strconv.Atoi(portText)
	if strings.Contains(host, ":") || ip == nil || ip.To4() == nil || ip.IsUnspecified() || ip.IsMulticast() || ip.Equal(net.IPv4bcast) || portErr != nil || port < 1 || port > 65535 {
		return cfg, errors.New("listen_address must be a specific IPv4 address")
	}
	token, err := base64.StdEncoding.Strict().DecodeString(cfg.Token)
	if err != nil || len(token) < 32 {
		return cfg, errors.New("token must decode to at least 32 bytes")
	}
	if !path.IsAbs(cfg.DatabasePath) {
		return cfg, errors.New("database_path must be absolute")
	}
	if cfg.RawRetentionDays == 0 {
		cfg.RawRetentionDays = 7
	}
	if cfg.FiveMinuteRetentionDays == 0 {
		cfg.FiveMinuteRetentionDays = 90
	}
	if cfg.HourlyRetentionDays == 0 {
		cfg.HourlyRetentionDays = 400
	}
	if cfg.MaxDatabaseBytes == 0 {
		cfg.MaxDatabaseBytes = 96 << 20
	}
	if cfg.RawRetentionDays < 1 || cfg.FiveMinuteRetentionDays < cfg.RawRetentionDays || cfg.HourlyRetentionDays < cfg.FiveMinuteRetentionDays || cfg.HourlyRetentionDays > 10000 || cfg.MaxDatabaseBytes < 1 || cfg.MaxDatabaseBytes > maximumDatabaseBytes {
		return cfg, errors.New("invalid retention or database cap")
	}
	return cfg, nil
}
