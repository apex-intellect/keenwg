# Privacy

KeenWG is local-first. It has no analytics, advertising SDK, crash-upload service, account system or maintainer-operated backend.

The Android app stores router profiles, encrypted credentials and generated WireGuard configurations in its private storage. Companion stores bounded operational state, certificate identity and hashed device credentials on the router. The optional Collector stores bounded local WireGuard history. Network requests go only to the router and to connection sources explicitly configured by the owner.

Subscription URLs, VLESS URIs, UUIDs, routing keys and raw Xray configuration are processed by Companion and are not returned through the Android control API. Android’s share sheet receives an exported file only after the user explicitly chooses to share it.

The sanitized support report excludes credentials, URLs, UUIDs, peer keys, hostnames, MAC addresses and full IP addresses. Review both TXT and JSON before sharing; the selected third-party app applies its own privacy policy.

Uninstalling the Android app removes its private storage according to Android behaviour. Companion uninstall preserves identity and user state by default so an accidental reinstall does not destroy pairing or routing data; explicit data removal is a separate administrative action.
