# KeenWG 1.0.0 stable

KeenWG 1.0.0 is the first stable, audited and owner-signed release. Stability is backed by a sanitized seven-stage lifecycle record on a named router and exact firmware, not architecture detection alone.

## Highlights

- complete reviewed WireGuard access lifecycle: expiry, allowed networks, DNS, rotation lineage, revoke, optional history and single-reveal client configuration;
- owner-only encrypted backup and restore using scrypt and AES-256-GCM, per-entry SHA-256, strict size limits, preview-only plan, exact plan confirmation, foreign-resource preservation and verified rollback;
- tested state compatibility from 0.6 through 1.0 with downgrade and corrupt-backup rejection;
- route explanation, independent connection adapters, catalog/import, scenarios, diagnostics and safe recovery from 0.8/0.9;
- complete threat model, fuzz targets, Go vulnerability scanning, dependency/license policy and CycloneDX SBOM;
- signed APK with a pinned permanent update identity and a reproducible ARM64 companion archive.

## Physical compatibility

Netcraze Hopper SE (NC-3812) with KeeneticOS 5.01.C.1.0-0, ARM64, Entware, XKeen 2.0 and Xray 26.3.27 passed install, legacy migration, reviewed route apply and restore, service restart, transactional update, injected rollback and uninstall/reinstall with read-back verification.

Other model/firmware combinations remain experimental until their own seven-stage record passes. Standalone sing-box, AWG Manager, MIPS/MIPSel, WAN/wildcard listeners and automatic country switching are not supported by this release.

## Security toolchain

The ARM64 companion is built with Go 1.26.5. Go 1.26.4 is rejected because `govulncheck` found reachable GO-2026-5856 in `crypto/tls`; the repeated 1.26.5 scan reports zero called vulnerabilities.

## Distribution status

- Signed stable release APK: verified against the pinned permanent owner identity and suitable for clean installation and future same-key updates.
- Debug APK: suitable for owner testing only and signed with a different identity.
- Unsigned release APK: provided for reproducibility and content inspection only.
- F-Droid/IzzyOnDroid submission: not claimed; the remaining public-repository and independent-rebuild gates are listed in `STORE-READINESS-1.0.md`.

## Verified artifact hashes

```text
0c12c42c4cfb4fadeaeaeca06a39649fab97715eb17dca26215bd8bd1fdea2a3  KeenWG-1.0.0-debug.apk
6efbffd731925f43a5e043198becf0570b002b45bfea6f52c57e23997b428e29  KeenWG-1.0.0-release.apk
79f13b06df94d12d62108ba7e8581b87e3993630dfe69f6d54a4a869117ae1dd  KeenWG-1.0.0-release-unsigned.apk
d3323b344789ff0996b45eee67dca3fc035923c3ba93caac510848390a103b51  keenwg-companion-arm64-1.0.0.tar.gz
e02a660fba275671de66f32aeeb8be656b2e6c48172fc8258b90a6cf32a14f83  keenwg-1.0.0.cdx.json
```

All APK variants contain versionName `1.0.0`, versionCode `11`, minimum SDK 26 and the exact companion archive above. The signed release verifies against certificate SHA-256 `5f5379508b3df4b60974fc857353961ea3e70ae9f67d66ac116fab189a4cb76a`; the inspection build intentionally remains unsigned.
