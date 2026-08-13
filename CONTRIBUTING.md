# Contributing to KeenWG

Contributions are welcome for router compatibility, adapters, translations, accessibility, tests and documentation.

1. Discuss large behaviour or protocol changes in an issue first.
2. Never commit real credentials, subscription links, router backups or signing files.
3. Add a failing test before a behaviour change and keep every network mutation manual, reviewed and recoverable.
4. Before requesting release review, run `scripts/verify-release.ps1`. For smaller changes, run Go tests/vet in both `xkeen-control` and `collector`, plus `./gradlew :app:testDebugUnitTest :app:lintDebug` (use `gradlew.bat` on Windows).
5. Keep every app-authored user-visible string in Android resources. Add the English default in `values/strings.xml` and the matching Russian translation in `values-ru/strings.xml`; the build rejects missing keys and Cyrillic copy in the default locale.
6. Keep user/router-owned labels verbatim. Do not translate server names, device names, hostnames, protocol values, or diagnostic identifiers.
7. Modified APK distributions must follow [TRADEMARKS.md](TRADEMARKS.md). Exact mirrors of signed official releases are welcome.

By submitting a contribution, you agree that it is licensed under Apache-2.0 and that you have the right to provide it.
