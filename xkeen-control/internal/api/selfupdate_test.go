package api

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/selfupdate"
)

func TestSelfUpdateGETAllowsViewerButHidesOperationDetails(t *testing.T) {
	secure, _, _, viewer, updater := newSelfUpdateServer(t)
	updater.status = selfupdate.Status{SchemaVersion: 1, CurrentVersion: "2.1.2", Supported: true, Phase: "installing", Result: "running", TargetVersion: "2.2.0"}
	request := httptest.NewRequest(http.MethodGet, "/v1/system/update", nil)
	request.Header.Set("Authorization", "Bearer "+viewer.Token)
	recorder := httptest.NewRecorder()
	secure.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusOK || !bytes.Contains(recorder.Body.Bytes(), []byte(`"current_version":"2.1.2"`)) {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.Bytes())
	}
	if bytes.Contains(recorder.Body.Bytes(), []byte("target_version")) || bytes.Contains(recorder.Body.Bytes(), []byte("installing")) {
		t.Fatal("viewer received owner update details")
	}
}

func TestSelfUpdatePOSTRequiresOwnerAndExactEnvelopeType(t *testing.T) {
	secure, owner, operator, _, _ := newSelfUpdateServer(t)
	for _, test := range []struct {
		name, token, contentType string
		want                     int
	}{
		{"operator", operator.Token, selfUpdateMediaType, http.StatusForbidden},
		{"missing type", owner.Token, "", http.StatusBadRequest},
		{"wrong type", owner.Token, "application/octet-stream", http.StatusBadRequest},
		{"owner", owner.Token, selfUpdateMediaType, http.StatusAccepted},
	} {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodPost, "/v1/system/update", strings.NewReader("envelope"))
			request.Header.Set("Authorization", "Bearer "+test.token)
			if test.contentType != "" {
				request.Header.Set("Content-Type", test.contentType)
			}
			recorder := httptest.NewRecorder()
			secure.ServeHTTP(recorder, request)
			if recorder.Code != test.want {
				t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.Bytes())
			}
		})
	}
}

func TestSelfUpdatePOSTMapsBoundedFailuresAndSanitizesResponses(t *testing.T) {
	secure, owner, _, _, updater := newSelfUpdateServer(t)
	secret := "archive-private-content signature-private-content /opt/private/path"
	for _, test := range []struct {
		name string
		err  error
		want int
	}{
		{"invalid", selfupdate.ErrInvalidUpdate, http.StatusBadRequest},
		{"busy", selfupdate.ErrUpdateBusy, http.StatusConflict},
		{"storage", selfupdate.ErrUpdateStorage, http.StatusServiceUnavailable},
	} {
		t.Run(test.name, func(t *testing.T) {
			updater.stageErr = test.err
			request := httptest.NewRequest(http.MethodPost, "/v1/system/update", strings.NewReader(secret))
			request.Header.Set("Authorization", "Bearer "+owner.Token)
			request.Header.Set("Content-Type", selfUpdateMediaType)
			recorder := httptest.NewRecorder()
			secure.ServeHTTP(recorder, request)
			if recorder.Code != test.want || strings.Contains(recorder.Body.String(), secret) || strings.Contains(recorder.Body.String(), "/opt/") {
				t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.Bytes())
			}
		})
	}
	updater.stageErr = nil
	large := httptest.NewRequest(http.MethodPost, "/v1/system/update", io.LimitReader(strings.NewReader(strings.Repeat("x", 1024)), 1024))
	large.ContentLength = maximumUpdateEnvelopeBytes + 1
	large.Header.Set("Authorization", "Bearer "+owner.Token)
	large.Header.Set("Content-Type", selfUpdateMediaType)
	recorder := httptest.NewRecorder()
	secure.ServeHTTP(recorder, large)
	if recorder.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("large status=%d", recorder.Code)
	}
}

func TestSelfUpdateLaunchesOnlyAfterAcceptance(t *testing.T) {
	secure, owner, _, _, updater := newSelfUpdateServer(t)
	request := httptest.NewRequest(http.MethodPost, "/v1/system/update", strings.NewReader("envelope"))
	request.Header.Set("Authorization", "Bearer "+owner.Token)
	request.Header.Set("Content-Type", selfUpdateMediaType)
	recorder := httptest.NewRecorder()
	secure.ServeHTTP(recorder, request)
	if recorder.Code != http.StatusAccepted {
		t.Fatalf("status=%d", recorder.Code)
	}
	select {
	case <-updater.launched:
	case <-time.After(time.Second):
		t.Fatal("updater was not launched")
	}
}

func newSelfUpdateServer(t *testing.T) (*SecureServer, auth.PlainCredential, auth.PlainCredential, auth.PlainCredential, *fakeSelfUpdater) {
	t.Helper()
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	owner := pairDevice(t, devices, auth.ScopeOwner, "Owner")
	operator := pairFromOwner(t, devices, auth.ScopeOperator, "Operator")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	updater := &fakeSelfUpdater{status: selfupdate.Status{SchemaVersion: 1, CurrentVersion: "2.1.2", Supported: true}, launched: make(chan struct{}, 1)}
	return NewSecure(legacy, devices, staticCapabilities(), WithSelfUpdater(updater)), owner, operator, viewer, updater
}

type fakeSelfUpdater struct {
	mu       sync.Mutex
	status   selfupdate.Status
	stageErr error
	launched chan struct{}
}

func (f *fakeSelfUpdater) Status(context.Context) (selfupdate.Status, error) { return f.status, nil }
func (f *fakeSelfUpdater) Stage(_ context.Context, reader io.Reader) (selfupdate.AcceptedUpdate, error) {
	_, _ = io.Copy(io.Discard, reader)
	if f.stageErr != nil {
		return selfupdate.AcceptedUpdate{}, f.stageErr
	}
	return selfupdate.AcceptedUpdate{OperationID: strings.Repeat("a", 32), TargetVersion: "2.2.0"}, nil
}
func (f *fakeSelfUpdater) Launch(selfupdate.AcceptedUpdate) error {
	f.launched <- struct{}{}
	return nil
}
