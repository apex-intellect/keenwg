# Privacy

KeenWG is a local-first router administration tool. It has no analytics, advertising SDK, crash-upload service, account system or maintainer-operated backend.

The Android app stores router profiles, encrypted credentials and generated WireGuard configurations on the phone. The companion stores bounded local controller state on the router. Network requests are made only to addresses and subscription endpoints configured by the owner. Android's share sheet sends an exported file only after the user explicitly chooses a destination.

The sanitized support report excludes credentials, URLs, UUIDs, peer keys, hostnames, MAC addresses and full IP addresses. Review both TXT and JSON before sharing. Third-party apps selected in the share sheet apply their own privacy policies.

Uninstalling the Android app removes its private storage according to Android behaviour. Removing companion data requires the documented router uninstall procedure.
