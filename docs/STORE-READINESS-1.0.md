# KeenWG 1.0 store readiness

No F-Droid or IzzyOnDroid submission is claimed for the stable 1.0.0 release.

## Ready

- Apache-2.0 project license, notices, privacy policy and vulnerability contact;
- application ID, semantic version/version code and source-buildable Gradle project;
- no analytics, advertising, proprietary SDK or maintainer cloud service;
- resolved CycloneDX dependency inventory and direct license review;
- unsigned release APK, deterministic ARM64 companion bundle and documented build commands.
- owner-signed APK and a repository-pinned signing certificate digest; the private identity remains outside source control.
- a named ARM64 router/firmware combination with a sanitized seven-stage physical lifecycle record.

## Unmet gates

1. The project has no configured public source-code remote or immutable public `v1.0.0` tag from which a store build recipe can fetch the exact source.
2. The unsigned APK has not yet been reproduced independently in a clean F-Droid build environment and compared byte-for-byte with the signed upstream payload.
3. Store listing assets and localized descriptions have not been finalized or reviewed by the owner.

F-Droid metadata is intentionally not fabricated while these values are unknown. After a public source URL and tag exist, generate `<application_id>.yml`, run `fdroid readmeta`, `fdroid lint` and a local `fdroid build`, then decide whether F-Droid signing or reproducible upstream signing is used.
