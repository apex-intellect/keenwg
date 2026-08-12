# Subscription Configuration and Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an owner securely add or replace the XKeen subscription URL from the phone, show whether it is configured without exposing it, and return specific refresh outcomes instead of `Операция не выполнена`.

**Architecture:** Companion stores the URL in a dedicated owner-only atomic file and injects a runtime URL provider into the existing transaction engine. A separate owner-protected configuration endpoint returns only `{configured}` and accepts a new HTTPS URL; the catalog schema remains unchanged for backward compatibility. Android calls this endpoint only for the XKeen source, keeps the editor screenshot-protected, and never persists or logs the raw URL.

**Tech Stack:** Go 1.26 standard library, Companion HTTPS API, Kotlin coroutines, kotlinx.serialization, OkHttp, Jetpack Compose, JUnit 4, Go testing.

## Global Constraints

- The subscription URL is secret and is never returned by Companion.
- The URL is accepted only over the pinned HTTPS owner channel and is never stored on the phone.
- The persisted secret file is mode `0600`, bounded, strictly decoded, and atomically replaced.
- Existing catalog schema version 1 and persisted catalog IDs remain unchanged.
- Updating a subscription never switches the active VPN server.
- Existing `subscription_url` configuration is migrated once without losing the current cache.
- All mutation errors have stable machine codes and plain-language Android mappings.

---

### Task 1: Add an atomic subscription URL store

**Files:**
- Create: `xkeen-control/internal/subscriptionconfig/store.go`
- Create: `xkeen-control/internal/subscriptionconfig/store_test.go`
- Create: `xkeen-control/internal/app/subscription_migration.go`
- Create: `xkeen-control/internal/app/subscription_migration_test.go`
- Modify: `xkeen-control/internal/config/config.go`
- Modify: `xkeen-control/cmd/keenwg-companion/main.go`

**Interfaces:**
- Produces: `subscriptionconfig.Store` with `Current() (string, bool)`, `Configured() bool`, and `Replace(string) error`.
- Produces: exported `config.ValidateSubscriptionURL(string) error` and `app.MigrateSubscriptionConfiguration(configPath, root, cfg)`.
- Consumes: one dedicated path and optional legacy URL at construction.

- [ ] **Step 1: Write failing store tests**

```go
func TestStoreMigratesLegacyURLAndNeverReturnsItFromStatus(t *testing.T) {
    path := filepath.Join(t.TempDir(), "subscription-source.json")
    store, err := New(path, "https://vpn.example.test/sub/private")
    if err != nil { t.Fatal(err) }
    if !store.Configured() { t.Fatal("configured=false") }
    if got, ok := store.Current(); !ok || got != "https://vpn.example.test/sub/private" { t.Fatalf("current=%q %t", got, ok) }
    info, err := os.Stat(path)
    if err != nil || info.Mode().Perm() != 0o600 { t.Fatalf("mode=%v err=%v", info.Mode(), err) }
}

func TestStoreRejectsInvalidURLAndPreservesPreviousValue(t *testing.T) {
    path := filepath.Join(t.TempDir(), "subscription-source.json")
    store, _ := New(path, "https://vpn.example.test/sub/one")
    if err := store.Replace("http://vpn.example.test/sub/two"); err == nil { t.Fatal("invalid URL accepted") }
    if got, _ := store.Current(); got != "https://vpn.example.test/sub/one" { t.Fatalf("current=%q", got) }
}
```

- [ ] **Step 2: Run and verify RED**

```powershell
$go='C:\Users\goldb\AppData\Local\Temp\keenwg-go1.26.5\sdk-complete\go\bin\go.exe'
& $go -C xkeen-control test ./internal/subscriptionconfig -count=1
```

Expected: FAIL because the package does not exist.

- [ ] **Step 3: Implement the strict store**

Use this public surface:

```go
var ErrInvalid = errors.New("invalid_subscription_configuration")
var ErrStorage = errors.New("subscription_configuration_storage")

type Store struct {
    mu sync.RWMutex
    path string
    current string
}

func New(path, legacyURL string) (*Store, error)
func (s *Store) Current() (string, bool)
func (s *Store) Configured() bool
func (s *Store) Replace(raw string) error
```

Persist exactly the fields `schema_version` and `subscription_url` (for example `{"schema_version":1,"subscription_url":"https://vpn.example.test/sub/private"}`) using a same-directory temporary file, `Chmod(0600)`, `Sync`, `Rename`, and parent-directory `Sync`. Reject symlinks, unknown fields, extra JSON, files above 16 KiB, and non-HTTPS URLs. Export the existing URL validator from `config` so configuration parsing and replacement share one policy.

Add an idempotent startup migration before `RunCompanion`: write the legacy URL to the dedicated store first, then atomically replace `companion.json` with `subscription_url` cleared. If either write fails, startup fails without discarding the legacy value. The installer already backs up `companion.json`; therefore rollback restores the pre-migration file, and a retry is safe even if the dedicated store was created. Tests must cover failure before store commit, failure before config rename, retry after a partial attempt, and a no-op once migrated.

- [ ] **Step 4: Run store and config tests**

```powershell
& $go -C xkeen-control test ./internal/subscriptionconfig ./internal/config -count=1
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add xkeen-control/internal/subscriptionconfig xkeen-control/internal/config xkeen-control/internal/app/subscription_migration.go xkeen-control/internal/app/subscription_migration_test.go xkeen-control/cmd/keenwg-companion/main.go
git commit -m "feat: store subscription URL securely"
```

### Task 2: Inject the runtime URL provider into XKeen refresh

**Files:**
- Modify: `xkeen-control/internal/transaction/engine.go`
- Modify: `xkeen-control/internal/transaction/engine_test.go`
- Modify: `xkeen-control/internal/adapter/xkeen.go`
- Modify: `xkeen-control/internal/adapter/xkeen_test.go`
- Modify: `xkeen-control/internal/app/service.go`
- Modify: `xkeen-control/internal/app/service_test.go`

**Interfaces:**
- Produces: `transaction.SubscriptionURLProvider` and `NewWithSubscriptionURLProvider(cfg config.Config, fetcher Fetcher, parser Parser, store Store, system System, clock func() time.Time, urls SubscriptionURLProvider) *Engine`.
- Produces: stable result code `subscription_not_configured` before any download or router mutation.
- Consumes: `subscriptionconfig.Store.Current`.

- [ ] **Step 1: Write the failing engine test**

```go
func TestRefreshWithoutConfiguredURLFailsWithoutMutation(t *testing.T) {
    deps := newEngineDeps(t)
    provider := staticURLProvider{}
    engine := NewWithSubscriptionURLProvider(deps.cfg, deps.fetcher, subscription.Parse, deps.store, deps.system, deps.clock, provider)
    op, job, err := engine.PrepareRefresh("11111111-1111-4111-8111-111111111111", deps.stateVersion)
    if err != nil { t.Fatal(err) }
    job(context.Background())
    op, _, _ = deps.store.FindOperation(op.IdempotencyKey)
    if op.Result != model.ResultFailedNoChange || op.ErrorCode != "subscription_not_configured" { t.Fatalf("operation=%+v", op) }
    if len(deps.system.events) != 0 { t.Fatalf("router mutated: %v", deps.system.events) }
}
```

- [ ] **Step 2: Run and verify RED**

```powershell
& $go -C xkeen-control test ./internal/transaction ./internal/adapter -count=1
```

Expected: FAIL because the provider constructor does not exist.

- [ ] **Step 3: Implement provider-aware refresh**

```go
type SubscriptionURLProvider interface { Current() (string, bool) }

func NewWithSubscriptionURLProvider(
    cfg config.Config, fetcher Fetcher, parser Parser, store Store,
    system System, clock func() time.Time, urls SubscriptionURLProvider,
) *Engine
```

Keep the existing `New(cfg config.Config, fetcher Fetcher, parser Parser, store Store, system System, clock func() time.Time) *Engine` as a compatibility constructor backed by `cfg.SubscriptionURL`. In `runRefresh`, read the URL immediately before fetching. If absent, finish with `ResultFailedNoChange` and `subscription_not_configured`. Never cache the secret in an operation or report.

- [ ] **Step 4: Wire the store in Companion**

Derive the file path as `filepath.Join(filepath.Dir(runtimeConfig.SubscriptionCache), "subscription-source.json")`. Construct it with the legacy `runtimeConfig.SubscriptionURL`, inject it into the engine, and add the file to the backup resource list as `subscription-source`. Root the derived path under test roots before opening it.

- [ ] **Step 5: Run transaction, adapter, app, and backup tests**

```powershell
& $go -C xkeen-control test ./internal/transaction ./internal/adapter ./internal/app ./internal/backup -count=1
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add xkeen-control/internal/transaction xkeen-control/internal/adapter xkeen-control/internal/app
git commit -m "feat: refresh XKeen from runtime subscription"
```

### Task 3: Add an owner-only configuration API that never returns the URL

**Files:**
- Create: `xkeen-control/internal/api/subscription_configuration.go`
- Create: `xkeen-control/internal/api/subscription_configuration_test.go`
- Modify: `xkeen-control/internal/api/secure.go`
- Modify: `xkeen-control/internal/app/service.go`
- Modify: `xkeen-control/internal/support/report.go`
- Modify: `xkeen-control/internal/support/report_test.go`

**Interfaces:**
- Produces: `GET /v1/connections/sources/xkeen-subscription/configuration` → `{"schema_version":1,"configured":bool}`.
- Produces: `PUT /v1/connections/sources/xkeen-subscription/configuration` with `{"schema_version":1,"subscription_url":"https://..."}`.
- Consumes: viewer authentication for GET and owner authentication for PUT.

- [ ] **Step 1: Write failing API tests**

```go
func TestSubscriptionConfigurationNeverReturnsSecret(t *testing.T) {
    service := &fakeSubscriptionConfiguration{configured: true}
    server, owner, viewer := secureFixture(t, WithSubscriptionConfiguration(service))
    get := catalogRequest(http.MethodGet, "/v1/connections/sources/xkeen-subscription/configuration", viewer.Token, "")
    response := httptest.NewRecorder()
    server.ServeHTTP(response, get)
    if response.Code != http.StatusOK || strings.Contains(response.Body.String(), "subscription_url") { t.Fatalf("response=%d %s", response.Code, response.Body.String()) }

    put := catalogRequest(http.MethodPut, "/v1/connections/sources/xkeen-subscription/configuration", owner.Token, `{"schema_version":1,"subscription_url":"https://vpn.example.test/sub/private"}`)
    updated := httptest.NewRecorder()
    server.ServeHTTP(updated, put)
    if updated.Code != http.StatusOK || service.replaced != "https://vpn.example.test/sub/private" { t.Fatalf("response=%d replaced=%q", updated.Code, service.replaced) }
}
```

Add tests for operator/viewer PUT → 403, unknown field → 400, non-XKeen source → 404, oversize body → 413, invalid URL → 400, storage failure → 503, and response/body not containing the submitted URL.

- [ ] **Step 2: Run and verify RED**

```powershell
& $go -C xkeen-control test ./internal/api -run SubscriptionConfiguration -count=1
```

- [ ] **Step 3: Implement the service contract and route**

```go
type SubscriptionConfiguration interface {
    Configured() bool
    Replace(string) error
}

func WithSubscriptionConfiguration(value SubscriptionConfiguration) SecureOption
```

Authenticate GET with viewer scope and PUT with owner scope. Accept only source ID `xkeen-subscription`. Use `decodeSecureJSONLimit` with a 16 KiB limit. Return only schema version and configured boolean. Map invalid values to `invalid_subscription_url` and persistence failures to `subscription_configuration_unavailable`.

- [ ] **Step 4: Add secrecy assertions**

Extend support/report and API tests so neither the stored URL nor `subscription_url` appears in support JSON, review text, device listing, capabilities, catalog, or configuration GET.

- [ ] **Step 5: Run API and security tests**

```powershell
& $go -C xkeen-control test ./internal/api ./internal/support ./internal/app -count=1
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add xkeen-control/internal/api xkeen-control/internal/app xkeen-control/internal/support
git commit -m "feat: configure subscription over protected API"
```

### Task 4: Add a strict Android source-configuration client

**Files:**
- Create: `app/src/main/java/ru/anisimov/keenwg/data/catalog/SourceConfigurationClient.kt`
- Create: `app/src/test/java/ru/anisimov/keenwg/data/catalog/SourceConfigurationClientTest.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/data/ServiceLocator.kt`

**Interfaces:**
- Produces: `SourceConfigurationGateway.status(profile, token, sourceId): SourceConfigurationStatus`.
- Produces: `SourceConfigurationGateway.replace(profile, token, sourceId, subscriptionUrl): SourceConfigurationStatus`.
- Consumes: `CompanionHttpTransport`, `RouterProfile`, and an owner token.

- [ ] **Step 1: Write failing client tests**

```kotlin
@Test fun `status returns only configured boolean and uses pinned owner path`() = runTest {
    val fixture = tlsServer(MockResponse().setBody("""{"schema_version":1,"configured":false}"""))
    val status = SourceConfigurationClient().status(fixture.profile, "owner-token", "xkeen-subscription")
    assertFalse(status.configured)
    assertEquals("/v1/connections/sources/xkeen-subscription/configuration", fixture.server.takeRequest().path)
}

@Test fun `replace zeroes caller bytes and never leaks URL in errors`() = runTest {
    val secret = "https://vpn.example.test/sub/private".toByteArray()
    val fixture = tlsServer(MockResponse().setResponseCode(503))
    assertFailsWith<CatalogException> { SourceConfigurationClient().replace(fixture.profile, "owner-token", "xkeen-subscription", secret) }
    assertTrue(secret.all { it == 0.toByte() })
}
```

- [ ] **Step 2: Run and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.data.catalog.SourceConfigurationClientTest"
```

- [ ] **Step 3: Implement the strict client**

```kotlin
data class SourceConfigurationStatus(val configured: Boolean)

interface SourceConfigurationGateway {
    suspend fun status(profile: RouterProfile, token: String, sourceId: String): SourceConfigurationStatus
    suspend fun replace(profile: RouterProfile, token: String, sourceId: String, subscriptionUrl: ByteArray): SourceConfigurationStatus
}
```

Validate the source ID, HTTPS URL, response schema, and exact fields. Use the shared pinned transport, a 16 KiB response limit, owner bearer token, and `finally { subscriptionUrl.fill(0) }`. Never include response bodies or submitted URLs in exceptions.

- [ ] **Step 4: Wire dependency injection**

Add `sourceConfigurationGateway` to `ServiceLocator` and construct it with the same `CompanionHttpTransport` as other protected clients.

- [ ] **Step 5: Run client tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.data.catalog.SourceConfigurationClientTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/ru/anisimov/keenwg/data app/src/test/java/ru/anisimov/keenwg/data/catalog
git commit -m "feat: add protected subscription client"
```

### Task 5: Integrate missing-link and refresh recovery into the VPN screen

**Files:**
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/connections/ConnectionsState.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/connections/ConnectionsViewModel.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/connections/ConnectionsScreen.kt`
- Modify: `app/src/test/java/ru/anisimov/keenwg/ui/connections/ConnectionsViewModelTest.kt`
- Modify: `app/src/test/java/ru/anisimov/keenwg/ui/connections/ConnectionsPresentationTest.kt`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: per-source configuration state and a screenshot-protected URL editor.
- Consumes: `SourceConfigurationGateway` from Task 4 and `subscription_not_configured` from Task 2.

- [ ] **Step 1: Write failing ViewModel tests**

```kotlin
@Test fun `unconfigured XKeen source asks for link instead of refreshing`() = runTest {
    val vm = fixture(sourceConfigured = false).viewModel
    advanceUntilIdle()
    assertEquals(false, vm.state.value.sourceConfiguration["xkeen-subscription"]?.configured)
    vm.refreshSource("xkeen-subscription")
    assertEquals("xkeen-subscription", vm.state.value.editingSubscriptionSourceId)
}

@Test fun `saving link refreshes catalog and clears secret bytes`() = runTest {
    val fixture = fixture(sourceConfigured = false)
    val secret = "https://vpn.example.test/sub/private".toByteArray()
    fixture.viewModel.saveSubscriptionLink(secret)
    advanceUntilIdle()
    assertTrue(secret.all { it == 0.toByte() })
    assertEquals(1, fixture.catalog.refreshCalls)
}
```

- [ ] **Step 2: Run and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.ui.connections.ConnectionsViewModelTest"
```

- [ ] **Step 3: Implement state and behavior**

Track `sourceConfiguration: Map<String, SourceConfigurationStatus>`, `editingSubscriptionSourceId`, and `sourceAction: Map<String, SourceActionState>`. Load configuration only for `xkeen-subscription`. If refresh returns `subscription_not_configured`, open the editor rather than showing a generic error. After a successful replace, call refresh once and render `SubscriptionUpdated(count)`.

- [ ] **Step 4: Add the secure editor**

Use a modal dialog with a visible label `Ссылка подписки`, helper text explaining where to copy it, actions `Сохранить ссылку` and `Отмена`, and `WindowManager.LayoutParams.FLAG_SECURE` while open. Convert text to a byte array only at submission, clear the Compose text immediately, and rely on the gateway to zero the byte array.

Render unconfigured source copy exactly:

```text
Ссылка подписки не указана
Сохранённые серверы доступны, но KeenWG не сможет получить свежий список.
Добавить ссылку
```

- [ ] **Step 5: Run connections tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.ui.connections.*"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/ru/anisimov/keenwg/ui/connections app/src/main/res/values*/strings.xml app/src/test/java/ru/anisimov/keenwg/ui/connections
git commit -m "ui: add secure subscription setup"
```

### Task 6: Package and verify the updated Companion

**Files:**
- Modify: `app/src/main/assets/companion/keenwg-companion-arm64.tgz`
- Modify: `app/src/main/assets/companion/manifest.json`
- Modify: `docs/SECURITY-MODEL.md`
- Modify: `docs/COMPANION-SETUP.md`

**Interfaces:**
- Produces: APK-embedded Companion archive containing the configuration API and runtime URL store.
- Consumes: release packaging and asset staging scripts already in the repository.

- [ ] **Step 1: Run full Go verification**

```powershell
& $go -C xkeen-control test ./... -count=1
& $go -C xkeen-control vet ./...
```

Expected: PASS.

- [ ] **Step 2: Build the arm64 Companion archive**

Run the reviewed build script for Linux arm64:

```powershell
$version='2.2.0'
.\scripts\build-companion-bundle.ps1 -Version $version -GoExecutable $go
```

Inspect `dist\keenwg-companion-arm64-2.2.0.tar.gz` and verify the bundled `VERSION` is `2.2.0` and its binary checksum matches `SHA256SUMS`.

- [ ] **Step 3: Stage the exact archive**

```powershell
.\scripts\stage-companion-asset.ps1 -Archive (Resolve-Path '.\dist\keenwg-companion-arm64-2.2.0.tar.gz')
```

Expected: manifest SHA-256, size, binary SHA-256, and archive bytes agree.

- [ ] **Step 4: Update security documentation**

Document that the source URL is owner-only, stored mode 0600, never returned, excluded from support output, and changed only over certificate-pinned HTTPS.

- [ ] **Step 5: Run Android and release verification**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\scripts\verify-release.ps1
git diff --check
```

Expected: all commands exit 0 and secret scan reports no subscription URL.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/assets/companion docs xkeen-control
git commit -m "build: ship protected subscription refresh"
```
