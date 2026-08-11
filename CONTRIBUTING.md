# Contributing to KeenWG

Contributions are welcome for router compatibility, adapters, translations, accessibility, tests and documentation.

1. Discuss large behaviour or protocol changes in an issue first.
2. Never commit real credentials, subscription links, router backups or signing files.
3. Add a failing test before a behaviour change and keep every network mutation manual, reviewed and recoverable.
4. Before requesting release review, run `scripts/verify-release.ps1`. For smaller changes, run Go tests/vet in both `xkeen-control` and `collector`, plus `./gradlew :app:testDebugUnitTest :app:lintDebug` (use `gradlew.bat` on Windows).
5. Keep user-visible text in Android resources and update both English and Russian values.

By submitting a contribution, you agree that it is licensed under Apache-2.0 and that you have the right to provide it.
