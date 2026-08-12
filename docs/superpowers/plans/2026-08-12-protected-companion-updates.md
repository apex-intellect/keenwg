# Protected Companion Updates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow a healthy paired owner phone to install a newer official Companion release without asking for the router password again, with publisher-signature verification, health checks, and automatic rollback.

**Architecture:** Official Companion archives receive an Ed25519-signed canonical manifest. The running Companion accepts a bounded binary update envelope only from an owner token, verifies publisher signature, architecture, version policy, archive hash, and embedded bundle checksums, then hands the staged archive to a small separately installed updater. The updater responds asynchronously, uses the existing transactional installer, records a sanitized result, and preserves the previous release on any failed health check.

**Tech Stack:** Go 1.26 standard library (`crypto/ed25519`, `archive/tar`, `compress/gzip`, `os/exec`), Companion pinned HTTPS API, Kotlin/OkHttp streaming request bodies, Jetpack Compose, PowerShell release scripts, Go and JUnit tests.

## Global Constraints

- Owner bearer authentication alone is insufficient to authorize arbitrary code; every archive must verify against a tracked publisher public key.
- The publisher private key never enters Git history, APK assets, Companion archives, logs, support reports, or test fixtures.
- Only Linux arm64 bundles are accepted.
- Downgrades are rejected. Same-version repair is allowed only when the current binary hash differs from the signed manifest.
- The endpoint never accepts shell commands, paths, URLs, or installer arguments from the client.
- Upload size is capped at the existing 16 MiB Companion asset limit plus a 16 KiB manifest.
- Update status contains version, phase, result, and safe error code only.
- Existing transactional install and rollback behavior remains the single release-switch implementation.
- The current public release lacks this endpoint; existing installations require one final explicitly explained credential-based upgrade. Every subsequent official update uses the protected channel.

---

### Task 1: Define and test the signed update manifest

**Files:**
- Create: `xkeen-control/internal/selfupdate/manifest.go`
- Create: `xkeen-control/internal/selfupdate/manifest_test.go`
- Create: `xkeen-control/cmd/keenwg-sign-update/main.go`
- Create: `xkeen-control/cmd/keenwg-sign-update/main_test.go`
- Create: `xkeen-control/internal/selfupdate/trusted-public-key.txt`
- Modify: `scripts/build-companion-bundle.ps1`
- Modify: `scripts/stage-companion-asset.ps1`
- Modify: `app/src/main/java/ru/anisimov/keenwg/data/installer/CompanionAsset.kt`
- Modify: `app/src/test/java/ru/anisimov/keenwg/data/installer/CompanionAssetTest.kt`

**Interfaces:**
- Produces: `selfupdate.Manifest`, `CanonicalBytes()`, and `Verify(publicKey, archiveHash, archiveSize)`.
- Produces: APK manifest fields `key_id` and `signature` in addition to existing hashes.
- Consumes: an external Ed25519 private-key seed only in the signing command.

- [ ] **Step 1: Write failing manifest tests**

```go
func TestManifestVerifiesCanonicalSignatureAndArchive(t *testing.T) {
    public, private, _ := ed25519.GenerateKey(rand.Reader)
    archive := []byte("reviewed archive")
    sum := sha256.Sum256(archive)
    manifest := Manifest{SchemaVersion: 1, Version: "2.2.0", Architecture: "arm64", ArchiveSHA256: hex.EncodeToString(sum[:]), ArchiveSize: int64(len(archive)), BinarySHA256: strings.Repeat("a", 64), KeyID: "release-2026"}
    manifest.Signature = base64.RawStdEncoding.EncodeToString(ed25519.Sign(private, manifest.CanonicalBytes()))
    if err := manifest.Verify(public, sum, int64(len(archive))); err != nil { t.Fatal(err) }
    manifest.Version = "2.2.1"
    if err := manifest.Verify(public, sum, int64(len(archive))); err == nil { t.Fatal("tampered manifest accepted") }
}
```

Add rejection tests for unknown fields, duplicate JSON keys, wrong key ID, malformed signature, wrong architecture, non-semver version, wrong hash/size, and canonicalization differences.

- [ ] **Step 2: Run and verify RED**

```powershell
& $go -C xkeen-control test ./internal/selfupdate ./cmd/keenwg-sign-update -count=1
```

- [ ] **Step 3: Implement the exact manifest**

```go
type Manifest struct {
    SchemaVersion int    `json:"schema_version"`
    Version       string `json:"version"`
    Architecture  string `json:"architecture"`
    ArchiveSHA256 string `json:"archive_sha256"`
    ArchiveSize   int64  `json:"archive_size"`
    BinarySHA256  string `json:"binary_sha256"`
    KeyID         string `json:"key_id"`
    Signature     string `json:"signature"`
}
```

`CanonicalBytes` encodes the six signed values before `Signature` as newline-delimited UTF-8 with a fixed `keenwg-update-v1\n` domain prefix. Strict JSON decoding rejects unknown fields and extra content.

- [ ] **Step 4: Implement the signing command**

`keenwg-sign-update` accepts signing mode (`-manifest`, `-archive`, and `-private-key`) and generation mode (`-generate-key`, `-private-key`, `-public-key`, and `-key-id`). The private-key file contains a raw-base64 32-byte Ed25519 seed. Signing verifies the unsigned fields against the archive, signs canonical bytes, atomically writes the signed manifest, zeros the decoded seed, and never prints key material. Generation refuses to overwrite either key file.

- [ ] **Step 5: Extend Android asset verification and staging**

Add `keyId` and `signature` to `CompanionAssetManifest`. `CompanionAssetVerifier` validates their shape but does not duplicate publisher verification; Companion is the trust boundary. Update `stage-companion-asset.ps1` to require an already signed manifest, verify its archive hash/size, and copy those exact fields into the APK manifest.

- [ ] **Step 6: Run manifest and Android asset tests**

```powershell
& $go -C xkeen-control test ./internal/selfupdate ./cmd/keenwg-sign-update -count=1
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.data.installer.CompanionAssetTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add xkeen-control/internal/selfupdate xkeen-control/cmd/keenwg-sign-update scripts/stage-companion-asset.ps1 app/src/main/java/ru/anisimov/keenwg/data/installer app/src/test/java/ru/anisimov/keenwg/data/installer
git commit -m "feat: sign official Companion updates"
```

### Task 2: Build the bounded staging and updater state machine

**Files:**
- Create: `xkeen-control/internal/selfupdate/stager.go`
- Create: `xkeen-control/internal/selfupdate/stager_test.go`
- Create: `xkeen-control/cmd/keenwg-updater/main.go`
- Create: `xkeen-control/cmd/keenwg-updater/main_test.go`
- Modify: `xkeen-control/packaging/install-companion.sh`
- Modify: `xkeen-control/packaging/install-companion_test.sh`
- Modify: `xkeen-control/packaging/S96keenwg-companion`

**Interfaces:**
- Produces: `Stager.Stage(io.Reader) (AcceptedUpdate, error)` and updater status file schema 1.
- Produces: fixed updater invocation `keenwg-updater -request /opt/etc/keenwg/update/pending.json`.
- Consumes: signed manifest, archive stream, current version/hash, and tracked publisher key.

- [ ] **Step 1: Write failing stager tests**

Test valid staging; oversized manifest/archive; truncated stream; signature/hash mismatch; downgrade; same-version same-hash rejection; same-version different-hash repair; unsafe tar paths; symlinks; duplicate members; missing `install-companion.sh`, `VERSION`, `SHA256SUMS`, updater, or binary; and cleanup after every rejection.

- [ ] **Step 2: Run and verify RED**

```powershell
& $go -C xkeen-control test ./internal/selfupdate ./cmd/keenwg-updater -count=1
```

- [ ] **Step 3: Implement the binary envelope and stager**

Envelope format:

```text
4-byte unsigned big-endian manifest length
manifest JSON bytes (1..16384)
archive bytes (exact signed archive_size, max 16777216)
EOF
```

Stream the archive to `/opt/etc/keenwg/update/.upload-` followed by a server-generated 32-character lowercase hexadecimal nonce while hashing. Reject symlinks and pre-existing unsafe update directories. After signature and bundle validation, atomically rename it to `pending-<operation-id>.tar.gz`, where the operation ID is another server-generated 32-character lowercase hexadecimal value, and write `pending.json` mode 0600 containing only server-generated paths, expected version/hashes, and operation ID.

- [ ] **Step 4: Implement the updater process**

The separately installed updater accepts only the fixed pending request directory. It validates file ownership/mode, re-verifies publisher signature and archive hash, creates the installer bootstrap request itself, invokes the archive's reviewed `install-companion.sh` with fixed arguments, and writes phase/result via atomic status files. It never reads values supplied as shell fragments. The existing installer remains responsible for stop, release switch, health verification, rollback, and cleanup.

- [ ] **Step 5: Package updater transactionally**

Install `keenwg-updater` into each release directory, include it in `SHA256SUMS`, and ensure installation/rollback switches binary, updater, init script, and installer as one release. Extend shell tests to prove failed candidate health restores the previous release and its updater.

- [ ] **Step 6: Run updater and packaging tests**

```powershell
& $go -C xkeen-control test ./internal/selfupdate ./cmd/keenwg-updater -count=1
sh xkeen-control/packaging/install-companion_test.sh
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add xkeen-control/internal/selfupdate xkeen-control/cmd/keenwg-updater xkeen-control/packaging
git commit -m "feat: stage Companion updates transactionally"
```

### Task 3: Expose owner-only update and status endpoints

**Files:**
- Create: `xkeen-control/internal/api/selfupdate.go`
- Create: `xkeen-control/internal/api/selfupdate_test.go`
- Modify: `xkeen-control/internal/api/secure.go`
- Modify: `xkeen-control/internal/app/service.go`
- Modify: `xkeen-control/internal/app/service_test.go`

**Interfaces:**
- Produces: `GET /v1/system/update` and `POST /v1/system/update`.
- Produces: update status `{schema_version,current_version,supported,phase,result,target_version,error}`.
- Consumes: owner authentication and `selfupdate.Stager`.

- [ ] **Step 1: Write failing API authorization and streaming tests**

Test owner GET/POST; viewer/operator POST → 403; missing or wrong content type → 400; content length over envelope limit → 413; malformed/tampered envelope → 400; busy → 409; staging I/O failure → 503; success → 202; and all response bodies exclude archive bytes, signatures, paths, tokens, and request content.

- [ ] **Step 2: Run and verify RED**

```powershell
& $go -C xkeen-control test ./internal/api -run SelfUpdate -count=1
```

- [ ] **Step 3: Implement owner-only endpoints**

```go
type SelfUpdater interface {
    Status(context.Context) (selfupdate.Status, error)
    Stage(context.Context, io.Reader) (selfupdate.AcceptedUpdate, error)
    Launch(selfupdate.AcceptedUpdate) error
}
```

GET requires viewer scope only to report current version/support, but omits operation details for non-owners. POST requires owner scope and exact media type `application/vnd.apex-intellect.keenwg-update.v1`. Use `http.MaxBytesReader`; on successful stage return 202 and launch only after the response has been committed. A single lock rejects parallel updates.

- [ ] **Step 4: Wire service shutdown-safe launch**

Construct the stager with the compiled-in publisher key and current binary hash. Launch the updater as a detached fixed executable with a fixed request path. The updater stops/restarts the service; the handler never tries to replace its own process. On startup, Companion reads the last sanitized status and removes abandoned unaccepted uploads.

- [ ] **Step 5: Run API/app tests and vet**

```powershell
& $go -C xkeen-control test ./internal/api ./internal/app ./internal/selfupdate -count=1
& $go -C xkeen-control vet ./...
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add xkeen-control/internal/api xkeen-control/internal/app xkeen-control/internal/selfupdate
git commit -m "feat: expose protected Companion updates"
```

### Task 4: Add the Android protected updater client

**Files:**
- Create: `app/src/main/java/ru/anisimov/keenwg/data/update/CompanionUpdateClient.kt`
- Create: `app/src/main/java/ru/anisimov/keenwg/data/update/CompanionUpdateModels.kt`
- Create: `app/src/test/java/ru/anisimov/keenwg/data/update/CompanionUpdateClientTest.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/data/ServiceLocator.kt`

**Interfaces:**
- Produces: `CompanionUpdateGateway.status(endpoint): CompanionUpdateStatus`.
- Produces: `CompanionUpdateGateway.install(endpoint, asset): CompanionUpdateAccepted`.
- Consumes: `VerifiedCompanionAsset`, exact signed APK manifest, pinned endpoint, and owner token.

- [ ] **Step 1: Write failing streaming-client tests**

Test GET path/authorization; POST content type; exact big-endian manifest prefix; archive bytes streamed without base64; status schema validation; 401/403/409/413/5xx mapping; and no archive/signature/body content in exceptions.

- [ ] **Step 2: Run and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.data.update.CompanionUpdateClientTest"
```

- [ ] **Step 3: Implement the gateway**

```kotlin
interface CompanionUpdateGateway {
    suspend fun status(endpoint: CompanionEndpoint): CompanionUpdateStatus
    suspend fun install(endpoint: CompanionEndpoint, asset: VerifiedCompanionAsset): CompanionUpdateAccepted
}
```

Use a custom OkHttp `RequestBody` that writes the 4-byte manifest length, exact UTF-8 signed manifest, then archive bytes. Keep existing pinning and bearer authentication. Zero the archive after upload completion/failure. Validate all response fields strictly.

- [ ] **Step 4: Wire dependency injection**

Construct the gateway in `ServiceLocator` using the shared pinned transport/asset verifier dependencies, without exposing it to screens directly.

- [ ] **Step 5: Run client tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.data.update.*"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/ru/anisimov/keenwg/data/update app/src/main/java/ru/anisimov/keenwg/data/ServiceLocator.kt app/src/test/java/ru/anisimov/keenwg/data/update
git commit -m "feat: add protected Companion updater client"
```

### Task 5: Present updates without exposing implementation jargon

**Files:**
- Create: `app/src/main/java/ru/anisimov/keenwg/ui/update/CompanionUpdateViewModel.kt`
- Create: `app/src/main/java/ru/anisimov/keenwg/ui/update/CompanionUpdateScreen.kt`
- Create: `app/src/main/java/ru/anisimov/keenwg/ui/update/CompanionUpdatePresentation.kt`
- Create: `app/src/test/java/ru/anisimov/keenwg/ui/update/CompanionUpdateViewModelTest.kt`
- Create: `app/src/test/java/ru/anisimov/keenwg/ui/update/CompanionUpdatePresentationTest.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/about/AboutScreen.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/KeenWgNav.kt`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: About-row `Компонент на роутере`, `UpdateRoute`, explicit update progress/recovery states.
- Consumes: gateway from Task 4 and bundled asset version.

- [ ] **Step 1: Write failing presentation tests**

Assert these states:

```text
Актуальная версия
Доступно обновление 2.2.0
Проверяем файл обновления…
Передаём обновление на роутер…
Роутер устанавливает обновление…
Проверяем подключение после обновления…
Обновление установлено
Не удалось подтвердить обновление — прежняя версия восстановлена
```

The primary action is `Обновить компонент`, never `Обновить XKeen` or `Обновить Companion`.

- [ ] **Step 2: Run and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.ui.update.*"
```

- [ ] **Step 3: Implement update state flow**

Load bundled asset metadata and router status. Offer update only for a newer signed version or allowed repair. After POST 202, poll health/status with bounded exponential intervals for at most 120 seconds. Disable duplicate taps. On reconnect, require the expected version before declaring success. Render safe rollback/uncertain guidance from status codes.

- [ ] **Step 4: Handle the one-time transition explicitly**

If current Companion reports `supported=false`, show:

```text
Для этого роутера нужен последний вход по паролю
Текущая версия ещё не умеет безопасно обновляться через приложение. После этого обновления пароль больше не понадобится.
Обновить и больше не спрашивать пароль
```

This action opens the existing credential-based setup/update flow. Do not describe it as a normal login and do not show SSH vocabulary. Once a supported version is installed, the row always uses protected updates.

- [ ] **Step 5: Run ViewModel, setup, and navigation tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "ru.anisimov.keenwg.ui.update.*" --tests "ru.anisimov.keenwg.ui.setup.*" --tests "ru.anisimov.keenwg.ui.navigation.*"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/ru/anisimov/keenwg/ui/update app/src/main/java/ru/anisimov/keenwg/ui/about app/src/main/java/ru/anisimov/keenwg/ui/KeenWgNav.kt app/src/main/res/values*/strings.xml app/src/test/java/ru/anisimov/keenwg/ui
git commit -m "ui: add passwordless router component updates"
```

### Task 6: Provision release trust and perform end-to-end verification

**Files:**
- Create: `.github/workflows/release.yml`
- Modify: `scripts/verify-release.ps1`
- Modify: `docs/SECURITY-MODEL.md`
- Modify: `docs/COMPANION-SETUP.md`

**Interfaces:**
- Produces: one persistent official publisher public key and signed official assets.
- Consumes: GitHub Actions secret `KEENWG_UPDATE_SIGNING_SEED_B64` containing the corresponding 32-byte seed.

- [ ] **Step 1: Generate the official key outside the repository**

Run the reviewed signing tool in generation mode with explicit output outside the project, for example:

```powershell
New-Item -ItemType Directory -Force 'C:\Users\goldb\.keenwg-release' | Out-Null
& $go -C xkeen-control run ./cmd/keenwg-sign-update -generate-key -private-key 'C:\Users\goldb\.keenwg-release\update-signing-seed.b64' -public-key 'xkeen-control\internal\selfupdate\trusted-public-key.txt' -key-id 'release-2026'
```

Restrict the private file ACL to the current Windows account and verify `git status --ignored` cannot see it. Stop before setting the GitHub secret unless the repository owner explicitly authorizes that external change.

- [ ] **Step 2: Embed and verify the public key**

`selfupdate` embeds exactly `internal/selfupdate/trusted-public-key.txt` with `//go:embed`; tests compare the compiled key ID/key bytes with that file. `verify-release.ps1` fails if the file is absent, malformed, changed without a new key ID, or if an asset signature does not verify.

- [ ] **Step 3: Configure release signing after owner approval**

Set `KEENWG_UPDATE_SIGNING_SEED_B64` as an Apex Intellect organization/repository Actions secret through the authenticated GitHub CLI. The workflow writes it to an ephemeral runner file, signs after building, deletes it before artifact upload, and uploads only public manifest/archive.

- [ ] **Step 4: Run complete local verification**

```powershell
& $go -C xkeen-control test ./... -count=1
& $go -C xkeen-control vet ./...
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\scripts\verify-release.ps1
git diff --check
```

Expected: all commands exit 0; secret scans do not find the private seed, subscription URL, token, or password.

- [ ] **Step 5: Perform router acceptance without changing user configuration**

First install the transition release using the already approved credential flow. Then send a signed same-version repair fixture or next patch release through `/v1/system/update`. Verify: 202 accepted, service reconnects, expected hash/version becomes active, existing XKeen/ASC/Xray/WireGuard files are unchanged, and a deliberately unhealthy candidate restores the prior release in the isolated packaging test environment rather than on the production router.

- [ ] **Step 6: Commit public trust and documentation**

```powershell
git add xkeen-control/internal/selfupdate/trusted-public-key.txt .github/workflows/release.yml scripts/verify-release.ps1 docs
git commit -m "build: establish official update trust"
```
