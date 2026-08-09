# KeenWG 0.9.0 public beta

## Highlights

- explains the effective route for a domain/IP without changing it;
- adds optional reviewed scenarios for Russian zones, Okko and ЕМИАС;
- coordinates multi-module changes with bounded recovery records, deterministic order, verified reverse rollback and write blocking after uncertain results;
- never rolls back a pending scenario automatically: the phone shows the exact plan ID and requires explicit confirmation;
- exports a user-reviewable JSON/TXT support report with separate observation/inference timestamps and no credentials, URLs, UUIDs, peer keys, hostnames, MACs or full IPs;
- adds pinned release signing configuration through external environment variables and a certificate identity verifier;
- adds Apache-2.0 project metadata, security/privacy policy, contribution templates and a CycloneDX SBOM.
- moves direct Compose UI copy to matched English/Russian resources with parity and source-scan release gates.

## Compatibility

The verified baseline is Android 8.0+, ARM64 Keenetic/NetCraze with Entware, XKeen 2.x/Xray and KeenOS WireGuard RCI. sing-box and AWG Manager adapters remain beta pending physical-router evidence. MIPS, WAN listeners and automatic route switching are unsupported.

## Signing status

The repository can build an externally signed APK when the owner supplies the permanent keystore and pinned certificate digest. In the absence of that authority, `app-release-unsigned.apk` is an unsigned beta artifact and must not be represented as an official signed release.

## Artifact hashes

```text
3869182b5be82ba8e3314ddd3f58083bbdb7be6c1b5e21d0338799819dd48ae4  app-debug.apk
93915582319493fec67948b343e62f872ef3203dfab5bd151aeb7f129e65bef4  app-release-unsigned.apk
cb3965d8460bda7158a1e29a394f17c9c08ce4cd19f3d39c2788c7edace77c20  keenwg-companion-arm64-0.9.0.tar.gz
73524dd9700840f081a013dcc2e3a0ac09582594272e100ca261aa5ef5e827e9  keenwg-0.9.0.cdx.json
```
