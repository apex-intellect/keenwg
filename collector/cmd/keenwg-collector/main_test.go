package main

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

func TestHTTPListenerRebindRecoversAfterAddressBecomesAvailable(t *testing.T) {
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})}
	supervisor := newHTTPListener(server, "10.8.0.1:18777")
	firstAttempt := make(chan struct{})
	boundAddress := make(chan string, 1)
	attempts := 0
	supervisor.listen = func(_, _ string) (net.Listener, error) {
		attempts++
		if attempts == 1 {
			close(firstAttempt)
			return nil, errors.New("WireGuard address is not ready")
		}
		listener, err := net.Listen("tcp4", "127.0.0.1:0")
		if err == nil {
			boundAddress <- listener.Addr().String()
		}
		return listener, err
	}
	supervisor.retryDelay = time.Hour
	done := make(chan error, 1)
	go func() { done <- supervisor.Serve() }()
	<-firstAttempt
	supervisor.RequestRebind()
	address := <-boundAddress
	eventuallyHTTPStatus(t, "http://"+address, http.StatusNoContent)
	shutdownHTTPListener(t, supervisor, done)
}

func TestHTTPListenerRebindMovesServiceToFreshSocket(t *testing.T) {
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})}
	supervisor := newHTTPListener(server, "10.8.0.1:18777")
	boundAddress := make(chan string, 2)
	supervisor.listen = func(_, _ string) (net.Listener, error) {
		listener, err := net.Listen("tcp4", "127.0.0.1:0")
		if err == nil {
			boundAddress <- listener.Addr().String()
		}
		return listener, err
	}
	done := make(chan error, 1)
	go func() { done <- supervisor.Serve() }()
	first := <-boundAddress
	eventuallyHTTPStatus(t, "http://"+first, http.StatusNoContent)
	supervisor.RequestRebind()
	second := <-boundAddress
	if first == second {
		t.Fatalf("rebind reused address %q", first)
	}
	eventuallyHTTPStatus(t, "http://"+second, http.StatusNoContent)
	eventuallyConnectionRefused(t, first)
	shutdownHTTPListener(t, supervisor, done)
}

func TestHTTPListenerShutdownInterruptsBindRetry(t *testing.T) {
	supervisor := newHTTPListener(&http.Server{}, "10.8.0.1:18777")
	attempted := make(chan struct{})
	supervisor.listen = func(_, _ string) (net.Listener, error) {
		select {
		case <-attempted:
		default:
			close(attempted)
		}
		return nil, errors.New("address unavailable")
	}
	supervisor.retryDelay = time.Hour
	done := make(chan error, 1)
	go func() { done <- supervisor.Serve() }()
	<-attempted
	shutdownHTTPListener(t, supervisor, done)
}

func TestHTTPListenerForceCloseWaitsForActiveHandler(t *testing.T) {
	handlerStarted := make(chan struct{})
	contextCanceled := make(chan struct{})
	handlerExited := make(chan struct{})
	releaseHandler := make(chan struct{})
	var releaseOnce sync.Once
	release := func() { releaseOnce.Do(func() { close(releaseHandler) }) }
	defer release()
	server := &http.Server{Handler: http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		close(handlerStarted)
		<-r.Context().Done()
		close(contextCanceled)
		<-releaseHandler
		close(handlerExited)
	})}
	supervisor := newHTTPListener(server, "10.8.0.1:18777")
	boundAddress := make(chan string, 1)
	supervisor.listen = func(_, _ string) (net.Listener, error) {
		listener, err := net.Listen("tcp4", "127.0.0.1:0")
		if err == nil {
			boundAddress <- listener.Addr().String()
		}
		return listener, err
	}
	serveDone := make(chan error, 1)
	go func() { serveDone <- supervisor.Serve() }()
	address := <-boundAddress
	requestDone := make(chan error, 1)
	go func() {
		response, err := http.Get("http://" + address)
		if response != nil {
			response.Body.Close()
		}
		requestDone <- err
	}()
	select {
	case <-handlerStarted:
	case <-time.After(time.Second):
		t.Fatal("active handler did not start")
	}

	gracefulCtx, cancelGraceful := context.WithTimeout(context.Background(), 20*time.Millisecond)
	err := supervisor.Shutdown(gracefulCtx)
	cancelGraceful()
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("Shutdown error = %v, want active-handler deadline", err)
	}
	forceDone := make(chan error, 1)
	go func() { forceDone <- supervisor.Close() }()
	select {
	case <-contextCanceled:
	case err := <-forceDone:
		release()
		if err != nil {
			t.Fatalf("Close error = %v", err)
		}
		t.Fatal("Close returned before canceling and waiting for the active handler")
	case <-time.After(time.Second):
		release()
		t.Fatal("force-close did not cancel active handler context")
	}
	select {
	case err := <-forceDone:
		release()
		if err != nil {
			t.Fatalf("Close error = %v", err)
		}
		t.Fatal("Close returned before the canceled handler exited")
	case <-time.After(10 * time.Millisecond):
	}
	release()
	select {
	case err := <-forceDone:
		if err != nil {
			t.Fatalf("Close after handler release = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Close did not return after active handler exited")
	}

	select {
	case err := <-serveDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("Serve did not stop after force-close")
	}
	select {
	case <-requestDone:
	case <-time.After(time.Second):
		t.Fatal("client request remained blocked after force-close")
	}
}

func TestHTTPListenerForceCloseGatesHandlersStartingAfterClose(t *testing.T) {
	entered := 0
	server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		entered++
		w.WriteHeader(http.StatusNoContent)
	})}
	supervisor := newHTTPListener(server, "10.8.0.1:18777")
	if err := supervisor.Close(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		t.Fatal(err)
	}
	recorder := httptest.NewRecorder()
	server.Handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "http://router/v1/health", nil))
	if entered != 0 {
		t.Fatal("handler entered after force-close gate")
	}
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("status after force-close = %d, want %d", recorder.Code, http.StatusServiceUnavailable)
	}
}

func eventuallyHTTPStatus(t *testing.T, endpoint string, want int) {
	t.Helper()
	client := &http.Client{Timeout: 200 * time.Millisecond, Transport: &http.Transport{DisableKeepAlives: true}}
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		response, err := client.Get(endpoint)
		if err == nil {
			response.Body.Close()
			if response.StatusCode == want {
				return
			}
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("%s did not return HTTP %d", endpoint, want)
}

func eventuallyConnectionRefused(t *testing.T, address string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		connection, err := net.DialTimeout("tcp4", address, 50*time.Millisecond)
		if err != nil {
			return
		}
		connection.Close()
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("old listener %s still accepts connections", address)
}

func shutdownHTTPListener(t *testing.T, supervisor *httpListener, done <-chan error) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := supervisor.Shutdown(ctx); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("Serve did not stop after Shutdown")
	}
}
