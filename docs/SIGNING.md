# Android release signing

KeenWG release APKs use one owner-controlled signing identity. The private key and its password are never stored in this repository.

Pinned certificate SHA-256:

```text
5f5379508b3df4b60974fc857353961ea3e70ae9f67d66ac116fab189a4cb76a
```

`scripts/verify-signing.ps1` verifies an APK against `docs/release-signing-cert.sha256`. A different signer fails closed. The release pipeline may either provide the four `KEENWG_KEYSTORE_*` environment variables during the Gradle build or sign the verified unsigned APK externally and run the same verifier afterward.

The owner signing vault is backed up separately from public release files. It must never be committed, attached to an issue, copied into an APK or included in a release archive.

Debug and release signatures are different Android update identities. Before replacing a debug installation with a release installation, export an encrypted KeenWG backup; Android will otherwise require uninstalling the debug package first.
