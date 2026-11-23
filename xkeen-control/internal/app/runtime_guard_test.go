package app

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/selfupdate"
)

func TestSelfUpdateStagingSharesRuntimeGuardAndReleasesOnRejectedUpload(t *testing.T) {
	u := &companionSelfUpdater{mutationGuard: func() (func(), error) { return nil, errors.New("busy") }}
	if _, err := u.Stage(context.Background(), strings.NewReader("invalid")); !errors.Is(err, selfupdate.ErrUpdateBusy) {
		t.Fatalf("busy=%v", err)
	}
	released := 0
	u.mutationGuard = func() (func(), error) { return func() { released++ }, nil }
	u.stager = selfupdate.NewStager(selfupdate.StagerConfig{UpdateDirectory: t.TempDir()})
	if _, err := u.Stage(context.Background(), strings.NewReader("invalid")); err == nil {
		t.Fatal("invalid update accepted")
	}
	if released != 1 {
		t.Fatalf("releases=%d", released)
	}
}

func TestPendingUpdateMarkerAndUnknownStorageBlockRuntime(t *testing.T) {
	root := t.TempDir()
	u := &companionSelfUpdater{requestPath: filepath.Join(root, "pending.json")}
	if busy, err := u.pending(); busy || err != nil {
		t.Fatalf("absent=%v %v", busy, err)
	}
	if err := os.WriteFile(u.requestPath, []byte("private pending request"), 0600); err != nil {
		t.Fatal(err)
	}
	if busy, err := u.pending(); !busy || err != nil {
		t.Fatalf("pending=%v %v", busy, err)
	}
	u.requestPath = filepath.Join(u.requestPath, "impossible-child")
	if _, err := u.pending(); err == nil {
		t.Fatal("unreadable pending state treated as absent")
	}
}

func TestHTTPWriteBudgetAccommodatesVerifiedRuntimeMutation(t *testing.T) {
	if hardenedServer(nil).WriteTimeout <= 60*time.Second {
		t.Fatal("runtime response can time out before bounded operation finishes")
	}
}
