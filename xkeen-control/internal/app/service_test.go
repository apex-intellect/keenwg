package app

import (
	"context"
	"crypto/rand"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/config"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/state"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/xray"
)

func TestBuildControllerModeStartsProtectedAccessWithoutXKeen(t *testing.T) {
	root := t.TempDir()
	cfg := rootedConfig(config.NewSecure("192.168.1.1:18779"), root)
	store := state.New(state.Paths{
		Subscription: cfg.SubscriptionCache,
		State:        cfg.StatePath,
		BackupDir:    cfg.BackupDir,
	}, rand.Reader)

	runtime, err := buildControllerMode(
		context.Background(), cfg, "2.1.0", store, xray.NewSystem(cfg), false, false,
	)
	if err != nil {
		t.Fatal(err)
	}
	if runtime.engine != nil || runtime.domains != nil {
		t.Fatal("protected-access-only runtime unexpectedly owns XKeen modules")
	}

	request := httptest.NewRequest(http.MethodGet, "/v1/xkeen/health", nil)
	response := httptest.NewRecorder()
	runtime.handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("health status=%d body=%s", response.Code, response.Body.String())
	}
}
