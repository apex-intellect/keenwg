package app

import (
	"context"
	"errors"
	"net"
	"net/http"
	"time"
)

func runHTTPServers(ctx context.Context, servers []*http.Server, listeners []net.Listener) error {
	if len(servers) == 0 || len(servers) != len(listeners) {
		return errors.New("invalid server set")
	}
	errorsChannel := make(chan error, len(servers))
	for i := range servers {
		server := servers[i]
		listener := listeners[i]
		go func() { errorsChannel <- server.Serve(listener) }()
	}

	var firstError error
	select {
	case <-ctx.Done():
	case err := <-errorsChannel:
		if !errors.Is(err, http.ErrServerClosed) {
			firstError = err
		}
	}
	shutdownContext, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for _, server := range servers {
		if err := server.Shutdown(shutdownContext); err != nil && firstError == nil {
			firstError = err
		}
	}
	return firstError
}
