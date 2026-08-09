# Third-party notices

The exact resolved dependency coordinates and versions are published in the CycloneDX SBOM accompanying each KeenWG build. This summary covers direct dependencies and the license families inherited by their ordinary transitive dependencies.

| Component family | Use | License |
|---|---|---|
| AndroidX and Jetpack Compose | Android UI, lifecycle, navigation, DataStore and platform integration | Apache License 2.0 |
| Kotlin standard library, coroutines and serialization | Android runtime and strict JSON | Apache License 2.0 |
| OkHttp and Okio | Bounded HTTP/HTTPS clients | Apache License 2.0 |
| WireGuard Android tunnel library | WireGuard configuration parsing | Apache License 2.0 |
| ZXing Core | QR encoding and decoding | Apache License 2.0 |
| mwiede JSch | Pinned-host-key SSH installer transport | BSD 3-Clause License |
| Go `x/crypto`, `x/net`, `x/sys`, `x/text` | Cryptography, networking and platform support | BSD 3-Clause License |
| Manrope and JetBrains Mono fonts | Application typography | SIL Open Font License 1.1 |

KeenWG does not bundle XKeen, Xray, sing-box, AWG Manager or Keenetic firmware. Their names describe optional interoperability only.

The repository rejects newly introduced GPL-3.0 and AGPL-3.0 dependencies in pull-request dependency review. A dependency must still be reviewed for compatibility even when its license is not on that deny list.
