package api

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/support"
)

func TestSupportReportRequiresViewerAndReturnsBoundedReviewBundle(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	pairDevice(t, devices, auth.ScopeOwner, "Owner")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	bundle := support.Bundle{
		SchemaVersion: 1, GeneratedAt: "2026-08-09T05:30:00Z",
		Report:     support.Report{SchemaVersion: 1, GeneratedAt: "2026-08-09T05:30:00Z", Checks: []support.Check{}, Notes: []string{}},
		ReviewText: "KeenWG support report\n",
	}
	reporter := &fakeSupportReporter{bundle: bundle}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithSupport(reporter))

	unauthorized := httptest.NewRecorder()
	secure.ServeHTTP(unauthorized, httptest.NewRequest(http.MethodGet, "/v1/support/report", nil))
	if unauthorized.Code != http.StatusUnauthorized {
		t.Fatalf("unauthorized=%d", unauthorized.Code)
	}

	response := httptest.NewRecorder()
	secure.ServeHTTP(response, catalogRequest(http.MethodGet, "/v1/support/report", viewer.Token, ""))
	if response.Code != http.StatusOK || reporter.calls != 1 || !bytes.Contains(response.Body.Bytes(), []byte(`"review_text":"KeenWG support report\n"`)) {
		t.Fatalf("response=%d body=%s calls=%d", response.Code, response.Body.Bytes(), reporter.calls)
	}
	if response.Body.Len() > support.MaxBundleBytes {
		t.Fatalf("response too large: %d", response.Body.Len())
	}
}

func TestSupportReportRejectsOversizedBundleFromReporter(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	pairDevice(t, devices, auth.ScopeOwner, "Owner")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	reporter := &fakeSupportReporter{bundle: support.Bundle{
		SchemaVersion: 1,
		ReviewText:    strings.Repeat("x", support.MaxBundleBytes+1),
	}}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithSupport(reporter))

	response := httptest.NewRecorder()
	secure.ServeHTTP(response, catalogRequest(http.MethodGet, "/v1/support/report", viewer.Token, ""))
	if response.Code != http.StatusServiceUnavailable || response.Body.Len() > 1024 {
		t.Fatalf("response=%d size=%d", response.Code, response.Body.Len())
	}
}

func TestSupportReportRejectsMethodsAndUnavailableReporter(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	pairDevice(t, devices, auth.ScopeOwner, "Owner")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	reporter := &fakeSupportReporter{err: context.DeadlineExceeded}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithSupport(reporter))

	unavailable := httptest.NewRecorder()
	secure.ServeHTTP(unavailable, catalogRequest(http.MethodGet, "/v1/support/report", viewer.Token, ""))
	if unavailable.Code != http.StatusServiceUnavailable {
		t.Fatalf("unavailable=%d", unavailable.Code)
	}
	method := httptest.NewRecorder()
	secure.ServeHTTP(method, catalogRequest(http.MethodPost, "/v1/support/report", viewer.Token, `{}`))
	if method.Code != http.StatusMethodNotAllowed {
		t.Fatalf("method=%d", method.Code)
	}
}

type fakeSupportReporter struct {
	bundle support.Bundle
	err    error
	calls  int
}

func (f *fakeSupportReporter) SupportReport(context.Context) (support.Bundle, error) {
	f.calls++
	return f.bundle, f.err
}
