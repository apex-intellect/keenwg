package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	collectorapi "github.com/goldb/keenwg/collector/internal/api"
	"github.com/goldb/keenwg/collector/internal/daemon"
	"github.com/goldb/keenwg/collector/internal/history"
	"github.com/goldb/keenwg/collector/internal/source"
)

var version = "dev"
var commit = "unknown"

type httpListener struct {
	server     *http.Server
	handlers   *handlerGate
	address    string
	listen     func(string, string) (net.Listener, error)
	retryDelay time.Duration
	rebind     chan struct{}
	stop       chan struct{}
	stopOnce   sync.Once
}

type handlerGate struct {
	next       http.Handler
	mu         sync.Mutex
	accepting  bool
	active     int
	nextID     uint64
	cancels    map[uint64]context.CancelFunc
	idle       chan struct{}
	idleClosed bool
}

func newHandlerGate(next http.Handler) *handlerGate {
	return &handlerGate{
		next: next, accepting: true, cancels: make(map[uint64]context.CancelFunc), idle: make(chan struct{}),
	}
}

func (g *handlerGate) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	g.mu.Lock()
	if !g.accepting {
		g.mu.Unlock()
		http.Error(w, "service unavailable", http.StatusServiceUnavailable)
		return
	}
	requestCtx, cancel := context.WithCancel(r.Context())
	g.nextID++
	id := g.nextID
	g.active++
	g.cancels[id] = cancel
	g.mu.Unlock()

	defer func() {
		cancel()
		g.mu.Lock()
		delete(g.cancels, id)
		g.active--
		g.closeIdleLocked()
		g.mu.Unlock()
	}()
	g.next.ServeHTTP(w, r.WithContext(requestCtx))
}

func (g *handlerGate) stop() <-chan struct{} {
	g.mu.Lock()
	g.accepting = false
	cancels := make([]context.CancelFunc, 0, len(g.cancels))
	for _, cancel := range g.cancels {
		cancels = append(cancels, cancel)
	}
	g.closeIdleLocked()
	idle := g.idle
	g.mu.Unlock()
	for _, cancel := range cancels {
		cancel()
	}
	return idle
}

func (g *handlerGate) closeIdleLocked() {
	if !g.accepting && g.active == 0 && !g.idleClosed {
		close(g.idle)
		g.idleClosed = true
	}
}

func newHTTPListener(server *http.Server, address string) *httpListener {
	next := server.Handler
	if next == nil {
		next = http.DefaultServeMux
	}
	handlers := newHandlerGate(next)
	server.Handler = handlers
	return &httpListener{
		server: server, address: address, listen: net.Listen, retryDelay: 5 * time.Second,
		rebind: make(chan struct{}, 1), stop: make(chan struct{}), handlers: handlers,
	}
}

func (h *httpListener) Serve() error {
	for {
		select {
		case <-h.stop:
			return nil
		default:
		}
		listener, err := h.listen("tcp4", h.address)
		if err != nil {
			if h.waitForRetryOrSignal() {
				return nil
			}
			continue
		}
		select {
		case <-h.stop:
			_ = listener.Close()
			return nil
		default:
		}
		serveDone := make(chan error, 1)
		go func() { serveDone <- h.server.Serve(listener) }()
		select {
		case err := <-serveDone:
			if h.stopped() || errors.Is(err, http.ErrServerClosed) {
				return nil
			}
			return err
		case <-h.rebind:
			_ = listener.Close()
			err := <-serveDone
			if err != nil && !errors.Is(err, net.ErrClosed) && !errors.Is(err, http.ErrServerClosed) {
				return err
			}
		case <-h.stop:
			err := <-serveDone
			if err != nil && !errors.Is(err, net.ErrClosed) && !errors.Is(err, http.ErrServerClosed) {
				return err
			}
			return nil
		}
	}
}

func (h *httpListener) waitForRetryOrSignal() bool {
	timer := time.NewTimer(h.retryDelay)
	defer timer.Stop()
	select {
	case <-h.stop:
		return true
	case <-h.rebind:
		return false
	case <-timer.C:
		return false
	}
}

func (h *httpListener) stopped() bool {
	select {
	case <-h.stop:
		return true
	default:
		return false
	}
}

func (h *httpListener) RequestRebind() {
	select {
	case h.rebind <- struct{}{}:
	default:
	}
}

func (h *httpListener) Shutdown(ctx context.Context) error {
	h.stopOnce.Do(func() { close(h.stop) })
	return h.server.Shutdown(ctx)
}

func (h *httpListener) Close() error {
	idle := h.handlers.stop()
	h.stopOnce.Do(func() { close(h.stop) })
	err := h.server.Close()
	<-idle
	if errors.Is(err, http.ErrServerClosed) || errors.Is(err, net.ErrClosed) {
		return nil
	}
	return err
}

func main() {
	configPath := flag.String("config", "/opt/etc/keenwg/config.json", "path to collector configuration")
	check := flag.Bool("check", false, "validate configuration, database, and ndmq source, then exit")
	showVersion := flag.Bool("version", false, "print version and commit")
	flag.Parse()
	if *showVersion {
		fmt.Printf("keenwg-collector %s (%s)\n", version, commit)
		return
	}
	if err := run(*configPath, *check); err != nil {
		fmt.Fprintln(os.Stderr, "keenwg-collector:", err)
		os.Exit(1)
	}
}

func run(configPath string, check bool) error {
	file, err := os.Open(configPath)
	if err != nil {
		return fmt.Errorf("open config: %w", err)
	}
	cfg, err := daemon.DecodeConfig(file)
	closeErr := file.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	store, err := history.Open(ctx, history.Config{Path: cfg.DatabasePath, MaxBytes: cfg.MaxDatabaseBytes, RawRetention: time.Duration(cfg.RawRetentionDays) * 24 * time.Hour, FiveMinuteRetention: time.Duration(cfg.FiveMinuteRetentionDays) * 24 * time.Hour, HourlyRetention: time.Duration(cfg.HourlyRetentionDays) * 24 * time.Hour})
	if err != nil {
		return err
	}
	runner := source.Runner{}
	if check {
		defer store.Close()
		if _, err := runner.Run(ctx, cfg.InterfaceID); err != nil {
			return fmt.Errorf("source check: %w", err)
		}
		return nil
	}
	state := daemon.NewState(version)
	server := collectorapi.New(collectorapi.Config{Address: cfg.ListenAddress, Token: cfg.Token, Version: version}, store, state)
	apiListener := newHTTPListener(server, cfg.ListenAddress)
	hupSignals := make(chan os.Signal, 1)
	signal.Notify(hupSignals, syscall.SIGHUP)
	defer signal.Stop(hupSignals)
	probe := make(chan struct{}, 1)
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case <-hupSignals:
				apiListener.RequestRebind()
				select {
				case probe <- struct{}{}:
				default:
				}
			}
		}
	}()
	return daemon.Run(ctx, daemon.Config{InterfaceID: cfg.InterfaceID, State: state, Probe: probe}, runner, store, apiListener)
}
