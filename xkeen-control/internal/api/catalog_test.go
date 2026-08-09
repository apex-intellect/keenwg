package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/goldb/keenwg/xkeen-control/internal/auth"
	"github.com/goldb/keenwg/xkeen-control/internal/catalog"
)

func TestCatalogRoutesEnforceScopesAndNeverReturnSourceSecret(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	owner := pairDevice(t, devices, auth.ScopeOwner, "Owner")
	operator := pairFromOwner(t, devices, auth.ScopeOperator, "Operator")
	viewer := pairFromOwner(t, devices, auth.ScopeViewer, "Viewer")
	store := newAPICatalogStore(t)
	secure := NewSecure(legacy, devices, staticCapabilities(), WithCatalog(store))

	get := catalogRequest(http.MethodGet, "/v1/connections/catalog", viewer.Token, "")
	getRecorder := httptest.NewRecorder()
	secure.ServeHTTP(getRecorder, get)
	if getRecorder.Code != http.StatusOK {
		t.Fatalf("GET status=%d body=%s", getRecorder.Code, getRecorder.Body.Bytes())
	}

	forbidden := catalogRequest(http.MethodPost, "/v1/connections/groups", viewer.Token,
		`{"schema_version":1,"state_version":1,"idempotency_key":"create-group-0001","label":"Work"}`)
	forbiddenRecorder := httptest.NewRecorder()
	secure.ServeHTTP(forbiddenRecorder, forbidden)
	if forbiddenRecorder.Code != http.StatusForbidden {
		t.Fatalf("viewer mutation status=%d body=%s", forbiddenRecorder.Code, forbiddenRecorder.Body.Bytes())
	}

	secret := "vless://private-user-id@vpn.example:443?security=reality"
	create := catalogRequest(http.MethodPost, "/v1/connections/sources", operator.Token,
		`{"schema_version":1,"state_version":1,"idempotency_key":"create-source-0001","group_id":"primary","kind":"share_link","label":"Personal","adapter_id":"catalog","source":"`+secret+`"}`)
	createRecorder := httptest.NewRecorder()
	secure.ServeHTTP(createRecorder, create)
	if createRecorder.Code != http.StatusOK || !bytes.Contains(createRecorder.Body.Bytes(), []byte(`"result":"committed"`)) {
		t.Fatalf("create status=%d body=%s", createRecorder.Code, createRecorder.Body.Bytes())
	}

	get = catalogRequest(http.MethodGet, "/v1/connections/catalog", owner.Token, "")
	getRecorder = httptest.NewRecorder()
	secure.ServeHTTP(getRecorder, get)
	if getRecorder.Code != http.StatusOK || bytes.Contains(getRecorder.Body.Bytes(), []byte(secret)) {
		t.Fatalf("credential leaked: status=%d body=%s", getRecorder.Code, getRecorder.Body.Bytes())
	}
	assertNoSensitiveJSONKeys(t, getRecorder.Body.Bytes())
}

func TestCatalogMutationIsStrictReplaySafeAndRejectsStaleState(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	pairDevice(t, devices, auth.ScopeOwner, "Owner")
	operator := pairFromOwner(t, devices, auth.ScopeOperator, "Operator")
	secure := NewSecure(legacy, devices, staticCapabilities(), WithCatalog(newAPICatalogStore(t)))
	body := `{"schema_version":1,"state_version":1,"idempotency_key":"create-group-0002","label":"Work"}`

	for attempt := 0; attempt < 2; attempt++ {
		recorder := httptest.NewRecorder()
		secure.ServeHTTP(recorder, catalogRequest(http.MethodPost, "/v1/connections/groups", operator.Token, body))
		if recorder.Code != http.StatusOK || !bytes.Contains(recorder.Body.Bytes(), []byte(`"state_version":2`)) {
			t.Fatalf("attempt=%d status=%d body=%s", attempt, recorder.Code, recorder.Body.Bytes())
		}
	}

	stale := httptest.NewRecorder()
	secure.ServeHTTP(stale, catalogRequest(http.MethodPost, "/v1/connections/groups", operator.Token,
		`{"schema_version":1,"state_version":1,"idempotency_key":"create-group-0003","label":"Backup"}`))
	if stale.Code != http.StatusConflict || !bytes.Contains(stale.Body.Bytes(), []byte(`"result":"rejected"`)) ||
		!bytes.Contains(stale.Body.Bytes(), []byte(`"error":"stale_state"`)) {
		t.Fatalf("stale status=%d body=%s", stale.Code, stale.Body.Bytes())
	}

	unknown := httptest.NewRecorder()
	secure.ServeHTTP(unknown, catalogRequest(http.MethodPost, "/v1/connections/groups", operator.Token,
		`{"schema_version":1,"state_version":2,"idempotency_key":"create-group-0004","label":"Bad","payload":{}}`))
	if unknown.Code != http.StatusBadRequest {
		t.Fatalf("unknown status=%d body=%s", unknown.Code, unknown.Body.Bytes())
	}
}

func TestCatalogSourceRequestHasOneMiBBound(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	pairDevice(t, devices, auth.ScopeOwner, "Owner")
	operator := pairFromOwner(t, devices, auth.ScopeOperator, "Operator")
	secure := NewSecure(legacy, devices, staticCapabilities(), WithCatalog(newAPICatalogStore(t)))
	body := `{"schema_version":1,"state_version":1,"idempotency_key":"create-source-0005","group_id":"primary","kind":"config","label":"Large","adapter_id":"catalog","source":"` +
		strings.Repeat("a", catalog.MaxSourceSecretBytes) + `"}`
	recorder := httptest.NewRecorder()
	secure.ServeHTTP(recorder, catalogRequest(http.MethodPost, "/v1/connections/sources", operator.Token, body))
	if recorder.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.Bytes())
	}
}

func TestCatalogDeleteUsesTypedPathAndStateVersion(t *testing.T) {
	legacy, _, _ := newTestServer(t)
	devices := newSecureDeviceStore(t)
	pairDevice(t, devices, auth.ScopeOwner, "Owner")
	operator := pairFromOwner(t, devices, auth.ScopeOperator, "Operator")
	store := newAPICatalogStore(t)
	document, err := store.CreateSource(context.Background(), 1, "seed-source-0001", catalog.SourceDraft{
		GroupID: "primary", Kind: catalog.SourceShareLink, Label: "Seed", AdapterID: "catalog",
	}, []byte("trojan://credential@vpn.example:443"))
	if err != nil {
		t.Fatal(err)
	}
	secure := NewSecure(legacy, devices, staticCapabilities(), WithCatalog(store))
	body, _ := json.Marshal(map[string]any{
		"schema_version": 1, "state_version": document.StateVersion, "idempotency_key": "delete-source-0001",
	})
	recorder := httptest.NewRecorder()
	secure.ServeHTTP(recorder, catalogRequest(http.MethodDelete, "/v1/connections/sources/"+document.Sources[0].ID, operator.Token, string(body)))
	if recorder.Code != http.StatusOK || bytes.Contains(recorder.Body.Bytes(), []byte(`"id":"`+document.Sources[0].ID+`"`)) {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.Bytes())
	}
}

func newAPICatalogStore(t *testing.T) *catalog.Store {
	t.Helper()
	directory := t.TempDir()
	store, err := catalog.NewStore(catalog.Paths{
		Catalog: filepath.Join(directory, "catalog.json"), Secrets: filepath.Join(directory, "catalog-secrets.json"),
	}, nil)
	if err != nil {
		t.Fatal(err)
	}
	return store
}

func catalogRequest(method, path, token, body string) *http.Request {
	request := httptest.NewRequest(method, path, strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+token)
	if body != "" {
		request.Header.Set("Content-Type", "application/json")
	}
	return request
}
