# Security policy

## Supported versions

Security fixes are provided for the latest published KeenWG beta and stable release. Older APKs and companion archives should be upgraded before reporting a reproducible issue.

## Reporting a vulnerability

Do not open a public issue containing credentials, subscription URLs, VLESS links, WireGuard private keys, pairing secrets, device tokens, router backups, hostnames, MAC addresses or full public IP addresses. Use GitHub Private Vulnerability Reporting for this repository. If it is unavailable, open a public issue containing only a request for a private contact channel.

Include the KeenWG version, Android version, router model/firmware and the in-app sanitized JSON/TXT report. Maintainers will acknowledge a complete report within seven days. No bounty or fixed disclosure deadline is promised.

## Security boundaries

- Companion listeners must remain on private IPv4 addresses; WAN exposure is unsupported.
- Every mutation requires an operator/owner device token and reviewed state version.
- SSH passwords, subscription material and private keys must never be attached to reports.
- A debug or unsigned APK is not a public signed release.
