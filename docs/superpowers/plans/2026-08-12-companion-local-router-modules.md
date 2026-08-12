# Companion Local Router Modules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing home-device inventory, static DHCP reservations, and all WireGuard peers available through the paired Companion token without KeenOS RCI credentials or ASC, then ship the verified 2.1.2 release.

**Architecture:** A bounded Go `routerlocal` package owns all `ndmq` execution, strict XML/config parsing, snapshots, review/apply plans, idempotency, verification, and rollback. The secure Companion API exposes separate home-device and WireGuard resources. Android uses pinned TLS clients and an adaptive repository that prefers Companion for paired profiles while retaining RCI only for unpaired legacy profiles.

**Tech Stack:** Go 1.26, `os/exec`, `encoding/xml`, `net/netip`, Android Kotlin, coroutines/Flow, kotlinx.serialization, OkHttp pinned TLS, Jetpack Compose, JUnit 4, shell/PowerShell release scripts.

## Global Constraints

- Never store or transmit the one-time SSH password after setup.
- Never read, log, report, or return router private keys or pre-shared keys.
- Client WireGuard private keys are generated on Android and remain in encrypted single-reveal storage.
- `/v1/devices` remains the trusted-phone API; home devices use `/v1/network/devices`.
- Paired profiles never silently fall back from Companion to RCI after a Companion error.
- Existing XKeen, Xray, routing rules, static reservations, WireGuard peers, Companion identity, and trusted-phone tokens survive update unchanged.
- Every mutation is reviewed, state-version checked, idempotent, verified after save, and either committed, verified rolled back, rejected without mutation, or marked uncertain.
- `ndmq` input is selected from fixed command builders, output is time/size/item bounded, and stderr/raw output never crosses the API.
- Android 8.0+ and ARM64 Keenetic/Netcraze with Entware remain the supported baseline.
- Nothing is pushed or released until all release gates and APK certificate verification pass.

---

### Task 1: Bounded router-local snapshots

**Files:**
- Create: `xkeen-control/internal/routerlocal/runner.go`
- Create: `xkeen-control/internal/routerlocal/model.go`
- Create: `xkeen-control/internal/routerlocal/parser.go`
- Create: `xkeen-control/internal/routerlocal/runner_test.go`
- Create: `xkeen-control/internal/routerlocal/parser_test.go`
- Create: `xkeen-control/internal/routerlocal/testdata/home.xml`
- Create: `xkeen-control/internal/routerlocal/testdata/leases.xml`
- Create: `xkeen-control/internal/routerlocal/testdata/running.xml`
- Create: `xkeen-control/internal/routerlocal/testdata/wireguard.xml`

**Interfaces:**
- Produces: `type Runner interface { Run(context.Context, Command) ([]byte, error) }`
- Produces: `type Command`, constructed only by `QueryHotspot`, `QueryLeases`, `QueryRunningConfig`, `QueryWireGuard(interfaceID)`, `Mutate(string)`, and `SaveConfiguration()` after validation.
- Produces: `ParseHomeDevices(hotspot, leases, running []byte) ([]HomeDevice, error)`.
- Produces: `DiscoverWireGuardInterfaces(running []byte) ([]string, error)` and `ParseWireGuardInterface(runtime, running []byte, interfaceID string) (WireGuardInterface, error)`.

- [ ] **Step 1: Write failing runner and parser tests**

  Add literal XML fixtures and tests proving: the live-shaped home fixture produces five devices with deterministic online-first ordering; the WireGuard fixture plus running config produces `Wireguard0` with six configured peers; missing required fields, duplicate identities, malformed roots, more than 1024 items, oversize output, timeout, nonzero exit, unsafe interface IDs, unsafe mutation arguments, and raw stderr are rejected with stable sanitized errors.

- [ ] **Step 2: Run the focused Go tests and verify RED**

  Run: `go -C xkeen-control test ./internal/routerlocal -count=1`

  Expected: FAIL because `internal/routerlocal` production types and functions do not exist.

- [ ] **Step 3: Implement minimal bounded execution and strict parsing**

  `ExecRunner.Run` must execute `ndmq -p <exact command> -x` under a two-second context, cap stdout at 1 MiB and stderr at 4 KiB, kill on oversize/timeout, and return only sentinel errors. XML decoding must require a single `<response>` root, validate bounded canonical MAC/IP/interface/public-key data, merge hotspot/lease/reservation rows, and merge runtime WireGuard state with configured peer lines.

- [ ] **Step 4: Run focused tests and verify GREEN**

  Run: `go -C xkeen-control test ./internal/routerlocal -count=1`

  Expected: PASS with no race, panic, or raw command output in failures.

### Task 2: Transactional review/apply service

**Files:**
- Create: `xkeen-control/internal/routerlocal/service.go`
- Create: `xkeen-control/internal/routerlocal/service_test.go`

**Interfaces:**
- Consumes: Task 1 `Runner`, home-device and WireGuard snapshot parsers.
- Produces: `SnapshotHome(context.Context) (HomeDocument, error)` and `SnapshotWireGuard(context.Context) (WireGuardDocument, error)`.
- Produces: `ReviewReservation`, `ApplyReservation`, `ReviewPeer`, and `ApplyPeer` with typed request/result models and terminal results `committed`, `rolled_back`, `rejected`, `uncertain`.

- [ ] **Step 1: Write failing service tests**

  Use a stateful fake runner that applies real command strings to an in-memory router state. Assert literal before/after outcomes for reservation add/remove and WireGuard create/rename/enable/disable/rotate/revoke; assert review performs zero mutations; stale state and expired/mismatched plans perform zero mutations; replayed UUID idempotency keys perform exactly one mutation; save/verification failures restore the old state; failed rollback returns `uncertain`; rotation stages the new key before removing the old key.

- [ ] **Step 2: Run the service tests and verify RED**

  Run: `go -C xkeen-control test ./internal/routerlocal -run 'TestService' -count=1`

  Expected: FAIL because the transaction service is absent.

- [ ] **Step 3: Implement the service**

  Compute state versions from canonical SHA-256 snapshots. Store bounded five-minute review plans and terminal idempotency results in mutex-protected maps with eviction. Validate IPv4 subnet/reserved addresses, peer names, interface IDs, public keys, keepalive, unique allow IPs, action-specific fields, and exact reviewed payload matching. Execute fixed command sequences, reread before save and after save, and run non-cancellable rollback logic through the service context when commit verification fails.

- [ ] **Step 4: Run focused service and race tests**

  Run: `go -C xkeen-control test -race ./internal/routerlocal -count=1`

  Expected: PASS with all terminal paths asserted.

### Task 3: Secure Companion API and capability discovery

**Files:**
- Create: `xkeen-control/internal/api/routerlocal.go`
- Create: `xkeen-control/internal/api/routerlocal_test.go`
- Modify: `xkeen-control/internal/api/secure.go`
- Modify: `xkeen-control/internal/app/service.go`
- Modify: `xkeen-control/internal/capability/model.go`
- Modify: `xkeen-control/internal/capability/detector.go`
- Modify: `xkeen-control/internal/capability/detector_test.go`
- Modify: `xkeen-control/internal/capability/testdata/xkeen-only.json`

**Interfaces:**
- Consumes: Task 2 router-local service.
- Produces: authenticated `GET /v1/network/devices`, reservation `/review` and `/apply`, `GET /v1/access/wireguard`, and peer `/review` and `/apply`.
- Produces: capability `network.home_devices` and Companion-backed `access.wireguard`, independent of ASC/XKeen/Collector.

- [ ] **Step 1: Write failing API and capability tests**

  Test real `SecureServer.ServeHTTP` behavior: viewer may read and review; only operator/owner may apply; missing token is 401; viewer apply is 403; malformed/unknown/oversize bodies are rejected; service sentinel errors map to stable bounded codes; `/v1/devices` still lists trusted phones; `/v1/network/devices` lists LAN devices. Test a detector fixture containing `ndmq` but no ASC and assert both local-router capabilities are available while unrelated modules remain independent.

- [ ] **Step 2: Run API/capability tests and verify RED**

  Run: `go -C xkeen-control test ./internal/api ./internal/capability ./internal/app -count=1`

  Expected: FAIL because routes/options/capabilities are missing and ASC still controls WireGuard.

- [ ] **Step 3: Implement routes, authorization, wiring, and detection**

  Add `WithRouterLocal` to `SecureServer`, exact route dispatch, strict request decoding, bounded response normalization, and service error mapping. Construct one `routerlocal.Service` in `RunCompanion`, attach it independently of XKeen, and advertise read/write availability when `/opt/bin/ndmq` is executable. Remove ASC as the `access.wireguard` condition without deleting ASC discovery metadata used elsewhere.

- [ ] **Step 4: Run focused Go tests and race tests**

  Run: `go -C xkeen-control test -race ./internal/api ./internal/capability ./internal/app ./internal/routerlocal -count=1`

  Expected: PASS.

### Task 4: Installer dependency and rollback contract

**Files:**
- Modify: `xkeen-control/packaging/install-companion.sh`
- Modify: `xkeen-control/packaging/install-companion_test.sh`
- Modify: `xkeen-control/README.md`

**Interfaces:**
- Consumes: Companion binary now requires `ndmq` for local-router capabilities.
- Produces: live install provisions `ndmq` only when missing before candidate self-check; fixture-root tests remain hermetic.

- [ ] **Step 1: Add failing executable installer tests**

  Extend the shell harness with a fake `opkg` and assert: missing live `ndmq` invokes exactly `opkg install ndmq`; preinstalled `ndmq` never invokes opkg; provisioning failure prevents the candidate switch; update rollback preserves the previous Companion, identity, trusted devices, WireGuard/XKeen files, and current symlink.

- [ ] **Step 2: Run installer tests and verify RED**

  Run: `bash xkeen-control/packaging/install-companion_test.sh`

  Expected: FAIL because the installer does not provision `ndmq`.

- [ ] **Step 3: Implement conditional provisioning**

  In live mode only, require root/aarch64, call `opkg install ndmq` only when `command -v ndmq` fails, then require the executable before stopping the old Companion. Keep fixture-root mode dependency-free and preserve existing rollback traps.

- [ ] **Step 4: Run installer tests and Go self-check tests**

  Run: `bash xkeen-control/packaging/install-companion_test.sh`

  Expected: PASS.

### Task 5: Android pinned-TLS clients

**Files:**
- Create: `app/src/main/java/ru/anisimov/keenwg/data/network/CompanionHomeDeviceClient.kt`
- Create: `app/src/main/java/ru/anisimov/keenwg/data/wireguard/CompanionWireGuardClient.kt`
- Create: `app/src/test/java/ru/anisimov/keenwg/data/network/CompanionHomeDeviceClientTest.kt`
- Create: `app/src/test/java/ru/anisimov/keenwg/data/wireguard/CompanionWireGuardClientTest.kt`

**Interfaces:**
- Consumes: Task 3 JSON contracts and existing `CompanionHttpTransport`.
- Produces: strict clients with `load`, `review`, and `apply` methods; home client maps API devices to existing `NetworkDevice`; WireGuard client exposes typed interface/peer snapshots.

- [ ] **Step 1: Write failing real HTTPS client tests**

  Use `TestCompanionServer` with pinned certificates and complete literal response bodies. Assert successful decoding including empty arrays, exact request paths/bodies, wrong certificate failure, 401/403/409/413/503 mapping, unknown fields/schema rejection, duplicate IDs/public keys rejection, oversized body rejection, and terminal-result validation.

- [ ] **Step 2: Run client tests and verify RED**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*CompanionHomeDeviceClientTest' --tests '*CompanionWireGuardClientTest'`

  Expected: FAIL because the clients do not exist.

- [ ] **Step 3: Implement strict clients**

  Reuse the shared pinned transport, kotlinx serialization with `ignoreUnknownKeys=false`, 1 MiB response bounds, UUID idempotency keys, strict schema/action/result validation, and sanitized domain-specific exceptions. Never include tokens, MACs, public keys, endpoint values, or raw response bodies in exception text.

- [ ] **Step 4: Run focused client tests**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*CompanionHomeDeviceClientTest' --tests '*CompanionWireGuardClientTest'`

  Expected: PASS.

### Task 6: Android adaptive devices and WireGuard repositories

**Files:**
- Create: `app/src/main/java/ru/anisimov/keenwg/data/AdaptivePeerRepository.kt`
- Create: `app/src/main/java/ru/anisimov/keenwg/data/wireguard/CompanionPeerRepository.kt`
- Create: `app/src/test/java/ru/anisimov/keenwg/data/AdaptivePeerRepositoryTest.kt`
- Create: `app/src/test/java/ru/anisimov/keenwg/data/wireguard/CompanionPeerRepositoryTest.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/data/PeerRepository.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/data/ServiceLocator.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/network/NetworkViewModel.kt`
- Modify: `app/src/test/java/ru/anisimov/keenwg/ui/network/NetworkViewModelTest.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/data/capability/CapabilityRegistry.kt`
- Modify: `app/src/test/java/ru/anisimov/keenwg/data/capability/CapabilityRegistryTest.kt`

**Interfaces:**
- Consumes: Task 5 clients, existing RCI `PeerRepository`/`NetworkGateway`, `PeerConfStore`, lineage, access-policy store, key/config builders.
- Produces: `PeerRepositoryGateway` implemented by legacy, Companion, and adaptive repositories; adaptive selection prefers Companion for every paired profile and never falls back after a Companion failure.

- [ ] **Step 1: Write failing repository/view-model/capability tests**

  Assert: a paired profile with blank RCI credentials loads six Companion peers and zero RCI calls; an unpaired profile uses RCI; Companion failure remains visible and does not call RCI; local X25519 generation sends only the public key; returned client config contains the phone private key but API requests do not; create/rotate stage and single-reveal configs correctly; policy/lineage finalization works; home devices and reservation edits use Companion when paired; Companion `access.wireguard` is not overwritten by blank local RCI readiness.

- [ ] **Step 2: Run focused Android tests and verify RED**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*AdaptivePeerRepositoryTest' --tests '*CompanionPeerRepositoryTest' --tests '*NetworkViewModelTest' --tests '*CapabilityRegistryTest'`

  Expected: FAIL on missing adaptive/Companion behavior and the current capability overwrite.

- [ ] **Step 3: Implement repositories and selection**

  Extract the public peer lifecycle interface without changing legacy behavior. Implement Companion mapping, key generation, allocation, config verification, encrypted staging, review/apply calls, cache updates, policy storage, and rotation lineage. Select transport from the active profile atomically. Update `NetworkViewModel` to choose Companion home devices for paired profiles and retain RCI only when no Companion endpoint exists. Change `CapabilityRegistry` to use `putIfAbsent` semantics for authenticated Companion modules.

- [ ] **Step 4: Run focused and existing peer tests**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*AdaptivePeerRepositoryTest' --tests '*CompanionPeerRepositoryTest' --tests '*NetworkViewModelTest' --tests '*CapabilityRegistryTest' --tests '*PeerRepositoryTest' --tests '*PeerListViewModelTest' --tests '*PeerDetailViewModelTest' --tests '*AddPeerViewModelTest'`

  Expected: PASS.

### Task 7: User-facing states, localization, and navigation regression

**Files:**
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/peers/PeerListScreen.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/peers/PeerListViewModel.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/detail/PeerDetailViewModel.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/add/AddPeerViewModel.kt`
- Modify: `app/src/main/java/ru/anisimov/keenwg/ui/navigation/TopLevelDestination.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ru/strings.xml`
- Modify: `app/src/test/java/ru/anisimov/keenwg/ui/navigation/TopLevelDestinationTest.kt`
- Modify: relevant peer view-model tests under `app/src/test/java/ru/anisimov/keenwg/ui/`

**Interfaces:**
- Consumes: Task 6 adaptive repository and capabilities.
- Produces: Access is visible for readable Companion WireGuard, shows existing peers in read-only mode when writes are unavailable, and gives update/retry/endpoint actions without mentioning RCI passwords.

- [ ] **Step 1: Write failing state/navigation tests**

  Assert: read-only `access.wireguard` keeps Access visible; six loaded peers render as content rather than empty/error; Companion update-required and retry errors are distinct; missing endpoint disables only create/rotate; empty success and error are mutually exclusive; all new RU strings have EN counterparts and no literal technical credential advice leaks into screens.

- [ ] **Step 2: Run focused UI/state tests and verify RED**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests '*TopLevelDestinationTest' --tests '*PeerListViewModelTest' --tests '*PeerDetailViewModelTest' --tests '*AddPeerViewModelTest'`

  Expected: FAIL on current hidden/error behavior.

- [ ] **Step 3: Implement minimal UI/state changes and resources**

  Preserve Material 3 layout. Add capability-aware read-only/action flags, actionable localized messages, retry/update setup actions, and endpoint-only prompts. Do not redesign unrelated screens.

- [ ] **Step 4: Run focused UI tests and resource verification**

  Run: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug`

  Expected: PASS.

### Task 8: Package, verify, document, commit, and publish 2.1.2

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/assets/companion/manifest.json`
- Modify: `README.md`
- Modify: `docs/COMPANION-SETUP.md`
- Modify: `docs/COMPATIBILITY.md`
- Modify: `docs/SECURITY-MODEL.md`
- Modify: `docs/RELEASE-NOTES-2.1.2.md`
- Generate: `dist/KeenWG-2.1.2.apk`, `dist/KeenWG-2.1.2.zip`, `dist/keenwg-2.1.2.cdx.json`

**Interfaces:**
- Consumes: all preceding tasks and existing release scripts/signing vault.
- Produces: one reviewed source commit after the two design commits, pushed to `origin/main` or merged fast-forward according to repository state, plus GitHub release `v2.1.2` with signed APK/ZIP and release notes.

- [ ] **Step 1: Build and stage the ARM64 Companion**

  Run the repository Companion build script for version `2.1.2`, then `scripts/stage-companion-asset.ps1` against the new archive. Verify the manifest version, archive name, size, and SHA-256 match the staged asset.

- [ ] **Step 2: Update release documentation and metadata**

  Record the root causes (nullable catalog arrays, RCI-only home devices, ASC/RCI-hidden WireGuard), the one-time Companion update step, module independence, preservation guarantees, and no-second-password behavior. Keep `versionCode=23`, `versionName=2.1.2` unless the existing candidate metadata differs.

- [ ] **Step 3: Run the complete release verification**

  Run `scripts/verify-release.ps1` and `scripts/security-audit.ps1` with the repository's pinned Go 1.26.5 toolchain, then rerun Android unit tests, lint, resource verification, debug build, and release build fresh. Expected: all commands exit 0.

- [ ] **Step 4: Sign and verify artifacts**

  Sign the release APK with the permanent owner key outside Git. Verify package `ru.anisimov.keenwg`, version code 23, version 2.1.2, APK v2 signature, and signer SHA-256 exactly `5f5379508b3df4b60974fc857353961ea3e70ae9f67d66ac116fab189a4cb76a`. Create the mobile-download ZIP only from that signed APK and compute SHA-256 for both.

- [ ] **Step 5: Review scope and commit intentionally**

  Run `git status -sb`, `git diff --check`, `git diff --stat`, secret scan, and inspect every changed/untracked path. Stage only source, tests, docs, manifest, SBOM, and intended release artifacts; exclude signing secrets, router credentials, temporary probes, build outputs, and stale pre-localized candidates. Commit tersely as `KeenWG 2.1.2`.

- [ ] **Step 6: Push and publish**

  Confirm `gh auth status`, synchronize with `origin/main` without rewriting public history, push the reviewed commit(s), create/push annotated tag `v2.1.2`, and create the GitHub release with `dist/KeenWG-2.1.2.apk`, `dist/KeenWG-2.1.2.zip`, SBOM, and release notes. Verify the public release asset names, sizes, hashes, tag target, and repository status after publication.

