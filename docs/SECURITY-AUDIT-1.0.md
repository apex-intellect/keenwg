# KeenWG 1.0 security and license audit

Audit date: 2026-08-09. Scope: Android app, ARM64 companion, legacy migration controller, installer/uninstaller, local adapters, imports/exports, operation/recovery storage and release tooling.

## Threat surfaces and controls

| Surface | Principal risk | Enforced control |
|---|---|---|
| Secure companion listener | Remote mutation or MITM inside LAN | Private IPv4/CGNAT bind only, TLS identity pin, per-device hashed bearer token, viewer/operator/owner scopes, bounded bodies, no-store |
| Legacy listener | Cleartext credential interception | Private IPv4 only, long token, migration-only capability, explicit disable after secure pairing |
| sing-box / AWG Manager adapters | Accidental exposure to another host | Loopback literal and explicit port required; wildcard, LAN, userinfo, query and fragment rejected |
| Android RCI client | Cleartext admin access on an untrusted network | Intended only for owner LAN/WireGuard; credentials encrypted by Android Keystore; no WAN support; secure companion preferred for new modules |
| Android credential storage | Phone backup or filesystem disclosure | Cloud/device-transfer backup disabled; AES-GCM keys remain in Android Keystore; secrets separated from public profile metadata |
| Router credential storage | Root-readable config or backup theft | Canonical `/opt` paths, regular-file/symlink checks, umask 077 and mode 0600/0700; pairing/device tokens stored as hashes |
| VLESS/WireGuard/JSON import | Parser crash, secret echo or resource exhaustion | Strict formats, bounded bytes/nodes/fields, stable error codes, fuzz tests and no raw input in API errors |
| Support/config export | Secret disclosure to another app | Explicit action, sanitized/bounded report, non-exported FileProvider, dedicated cache paths and temporary read grants |
| Encrypted backup | Offline disclosure, stale review or foreign overwrite | scrypt + AES-256-GCM, per-entry SHA-256, 4 MiB/64-entry limits, owner scope, exact plan ID, preview before apply, foreign-resource skip |
| SSH installer/update | Host impersonation or partial install | Host-key pin before password, fixed command vocabulary, signed/hash-pinned bundle input, staging, self-check, atomic switch and rollback |
| Operation records | Secret retention or replay | Stable sanitized codes, bounded history, idempotency keys and reviewed state versions |
| Recovery and cleanup | Automatic destructive action or lost rollback | Root-only snapshots, reverse verified rollback, uncertain retention; restore/revoke/cleanup/update/uninstall are always explicit |
| Android sharing provider | Broad filesystem read | Provider not exported; only `confs/`, `support/` and encrypted `backup/` cache subtrees are mapped |

## Executed gates

- Go race tests and `go vet` pass across every package.
- `govulncheck v1.6.0` initially found GO-2026-5856 in Go 1.26.4 `crypto/tls`. The release toolchain was moved to the fixed Go 1.26.5; the repeated scan reports zero called vulnerabilities. The build script rejects any other Go version.
- Subscription, domain-policy and configuration fuzz targets ran for ten seconds each with no crash or invariant failure. Normal `go test` executes their seed corpus in CI.
- Installer and companion installer tests run in isolated fake roots and cover rollback, unsafe paths, symlinks, permissions and start/check failures.
- Android unit tests, lint, locale parity, visible-string scan and FileProvider policy pass.
- Auth tests cover anonymous rejection and viewer/operator/owner boundaries, including owner-only backup/device operations.
- Secret scans reject private-key files, private-key PEM headers and subscription URLs in tracked content and release inputs.
- Resolved Go/Android dependencies are emitted to CycloneDX; direct license families are recorded in `THIRD-PARTY-NOTICES.md`, and pull requests reject high-severity dependency changes plus GPL-3.0/AGPL-3.0 additions.

## Release findings

| ID | Severity | Finding | Resolution / gate |
|---|---|---|---|
| KSEC-001 | High | Go 1.26.4 contains a reachable `crypto/tls` vulnerability | Resolved: build and scan use Go 1.26.5 |
| KSEC-002 | Medium | Android permits cleartext traffic because user-configurable KeenOS RCI is HTTP | Accepted local-network boundary; documented, no WAN support, secure companion used for new operations |
| KSEC-003 | High | A debug or unsigned APK has no trustworthy update identity | Resolved for owner releases: one permanent certificate is pinned in `docs/release-signing-cert.sha256`, all signed artifacts are verified against it, and the private vault remains outside Git and release archives |
| KSEC-004 | Medium | Architecture-only compatibility claims could misrepresent real router behavior | Resolved for the named NC-3812 firmware: all seven lifecycle stages passed with sanitized read-back evidence; other combinations remain experimental |

No unresolved source-level vulnerability is known after the fixed-toolchain scan. The stable support claim is limited to the exact model, firmware and engine combination in the generated compatibility matrix.

The final 1.0 stable artifact audit verified the debug, unsigned and owner-signed APK containers, the embedded companion digest, the ARM64 archive path set, 124-component SBOM, versionName 1.0.0/versionCode 11, model-specific 7/7 evidence and release certificate SHA-256 `5f5379508b3df4b60974fc857353961ea3e70ae9f67d66ac116fab189a4cb76a`.

Public reporting instructions are in `SECURITY.md`, privacy behavior in `PRIVACY.md`, licensing in `LICENSE`, `NOTICE`, `THIRD-PARTY-NOTICES.md` and the release SBOM.
