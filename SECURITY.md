# Security policy

## Supported versions

Security fixes are provided for the latest stable KeenWG release. Upgrade older APKs and Companion archives before reporting an issue.

## Reporting a vulnerability

Use GitHub Private Vulnerability Reporting for this repository. If it is unavailable, open a public issue containing only a request for a private contact channel.

Never attach credentials, subscription URLs, VLESS links, WireGuard private keys, SSH passwords, pairing secrets, device tokens, router backups, hostnames, MAC addresses or full public IP addresses. Include the KeenWG version, Android version, router model/firmware and the in-app sanitized JSON/TXT report. Maintainers aim to acknowledge a complete report within seven days; no bounty or fixed disclosure deadline is promised.

## Security boundaries

- Companion must listen on one private IPv4 address over HTTPS. WAN, public and wildcard listeners are unsupported.
- Android accepts only the exact certificate pin learned over the separately verified SSH channel.
- Each phone has an independent revocable token and scope. A shared XKeen controller token does not exist in 2.0.
- Every mutation requires an authorized scope, reviewed state version and read-back or an explicit uncertain result.
- Subscription material and routing secrets stay on the router; support reports are redacted.
- RCI may use cleartext HTTP only inside a trusted LAN or WireGuard tunnel. The optional Collector is router-local: Android never connects to it directly, and Companion accepts only a literal private/loopback IPv4 listener from its root-owned configuration.
- Debug and unsigned APKs are not public signed releases.

The detailed trust model is documented in [docs/SECURITY-MODEL.md](docs/SECURITY-MODEL.md).
