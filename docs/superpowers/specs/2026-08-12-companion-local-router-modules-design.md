# Companion Local Router Modules Design

## Goal

After the initial router setup, KeenWG must list home devices, manage their static IPv4 reservations, and expose the router's existing WireGuard accesses through the already paired Companion token. The user must not enter or save a second KeenOS web-interface password, and these modules must work anywhere the Companion HTTPS address is reachable.

## Current problem

The Android `NetworkViewModel` and `PeerRepository` load home devices and WireGuard peers through the legacy KeenOS RCI client using `ServerSettings.rciLogin` and `rciPassword`. Initial setup authenticates to Entware over SSH, deliberately erases that password, and stores only the Companion identity, certificate pin, and device token. As a result, Companion-backed domains and exclusions work while the Devices tab fails with a misleading KeenOS credentials error and the entire Access tab disappears.

On the verified router, `Wireguard0` contains six peers, but `/opt/sbin/asc` is absent. Companion incorrectly treats ASC as the prerequisite for `access.wireguard`, even though the Android WireGuard lifecycle generates keys locally and never uses ASC. Android then overwrites Companion's declaration with direct-RCI readiness, hiding the real peers when RCI credentials are absent.

The router already exposes the required information locally through `ndmq`:

- `show ip hotspot` provides registered and currently connected hosts;
- `show ip dhcp bindings` provides DHCP leases;
- `show running-config` provides static DHCP reservations.
- `show interface` and `show running-config` provide WireGuard interfaces, runtime peer state, and configured peer parameters.

## Chosen architecture

Companion becomes the transport for the home-device inventory, static reservations, and WireGuard access lifecycle.

1. A focused Go router-local package executes `ndmq` with fixed commands and parses bounded XML into typed home-device and WireGuard snapshots.
2. The secure Companion server exposes distinct `/v1/network/devices` and `/v1/access/wireguard` resources. `/v1/network/devices` is deliberately separate from `/v1/devices`, which continues to mean phones trusted by Companion.
3. Android loads and mutates both modules through pinned-TLS Companion clients using the active profile's device token.
4. The Devices and Access tabs no longer read KeenOS RCI credentials. Direct RCI remains only as a compatibility fallback for profiles that have not paired with Companion.

No SSH password, KeenOS password, WireGuard private key, raw `ndmq` document, MAC address, peer public key, hostname, or endpoint is added to reports or logs.

## Companion contract

### Inventory

`GET /v1/network/devices` requires an authenticated Companion device with viewer, operator, or owner scope.

The response is a strict schema-versioned document:

```json
{
  "schema_version": 1,
  "state_version": 42,
  "devices": [
    {
      "id": "mac-sha256-prefix",
      "mac": "70:d8:c2:71:b2:09",
      "name": "server",
      "hostname": "srv-home",
      "ip": "192.168.1.66",
      "reserved_ip": "192.168.1.66",
      "online": true,
      "static_reservation": true,
      "interface_name": "Home",
      "rssi": -54
    }
  ]
}
```

`devices` is always an array, including when empty. `state_version` is derived from the canonical current reservation state and is used for optimistic concurrency. Device ordering is deterministic: online first, then case-insensitive display name, then MAC address.

`ndmq` execution has a short timeout, an output-size ceiling, an item-count ceiling, fixed argument arrays, and sanitized errors. Missing `ndmq`, unsupported KeenOS XML, timeout, and execution failure are distinct stable error codes; command output is never returned to the phone.

### Reservation review and apply

Static-IP changes use two requests so the user sees the exact plan before a router mutation:

- `POST /v1/network/devices/{id}/reservation/review`
- `POST /v1/network/devices/{id}/reservation/apply`

The review request contains schema version, observed `state_version`, and either a validated IPv4 address or `null` to remove the reservation. Companion checks that the device still exists, the address is inside the device's home IPv4 subnet, and it is not the network, broadcast, or router address. It returns a short-lived opaque `plan_id` and a bounded structured before/after plan that Android renders in the current language.

Apply requires the same target, observed version, `plan_id`, and a UUID idempotency key. Only operator and owner scopes may apply. Companion:

1. verifies the reviewed plan and unchanged state;
2. runs the fixed `ip dhcp host …` or `no ip dhcp host …` command through `ndmq`;
3. rereads the running configuration and verifies the target;
4. persists the router configuration;
5. rereads and returns the committed inventory.

On a failure before verification, Companion restores the previous reservation, saves, and verifies the rollback. The result is one of `committed`, `rolled_back`, `rejected`, or `uncertain`. An uncertain result blocks further reservation writes until a fresh inventory proves a stable state. Requests with stale `state_version` or an expired/mismatched plan are rejected without mutation.

## WireGuard access contract

### Inventory

`GET /v1/access/wireguard` requires an authenticated Companion device with viewer, operator, or owner scope. The strict response contains `schema_version`, a canonical `state_version`, and all supported WireGuard interfaces. Each interface contains its ID, public key, addresses, listen port, MTU, and peers. Each peer contains its public key, label, allowed IPv4 address, enabled and online state, current byte counters, and normalized handshake state. Historical points remain an independent optional Collector feature.

Private keys and pre-shared keys are never read from the router or returned by Companion. Arrays are always non-null, item ordering is deterministic, duplicate interface or peer identities are rejected, and all interface IDs, public keys, addresses, counters, and labels are bounded and strictly validated.

Android displays the Access destination whenever this authenticated inventory is readable. One interface is selected automatically; if several exist, the user can choose an interface and the choice is remembered per router profile. The verified router's six `Wireguard0` peers therefore appear without RCI credentials or ASC.

### Endpoint and client configuration

Creation and key rotation continue to generate the X25519 key pair on the phone. Only the new public key is sent to Companion. The private key and complete client configuration remain in Android's encrypted single-reveal flow and never cross the network after generation.

Android obtains the router interface public key, addresses, listen port, and MTU from the Companion inventory. It resolves the client endpoint in this order:

1. a valid endpoint already saved in the router profile;
2. a validated KeenDNS or other public hostname and listen port reported by the router-local metadata provider;
3. an explicit endpoint entered once in a focused WireGuard dialog.

Failure to discover an endpoint does not hide existing peers or block rename, enable, disable, or revoke. It blocks only create and rotate, with an explanation and endpoint action. The endpoint is non-secret profile metadata; no router password is requested.

### Peer review and apply

WireGuard mutations use the same review/apply discipline as static reservations:

- `POST /v1/access/wireguard/peers/review`
- `POST /v1/access/wireguard/peers/apply`

The review request identifies the interface and one action: `create`, `rename`, `set_enabled`, `rotate`, or `revoke`. It includes the observed `state_version`, target peer identity where applicable, and only the bounded fields required by that action. Create and rotate include the phone-generated public key, never its private key. Review returns a short-lived opaque `plan_id` and a bounded structured before/after plan without mutation; Android renders the plan in the current language.

Apply requires the reviewed plan, unchanged state, and a UUID idempotency key. Only operator and owner scopes may apply. Companion executes fixed `ndmq` command templates, rereads the configured and runtime state, saves the router configuration, rereads again, and returns the committed inventory. Create also verifies the assigned address is unique and inside the interface subnet.

On failure, Companion restores the previous peer snapshot and verifies it. Rotation is a cutover: the new peer is staged and verified before the old peer is removed; rollback removes the staged peer and restores the old peer exactly. Results are `committed`, `rolled_back`, `rejected`, or `uncertain`. An uncertain result blocks further WireGuard writes until explicit recovery rereads a stable router state. Replayed idempotency keys return the original terminal result without a second mutation.

## Capability and installation behavior

A new capability `network.home_devices` reports read or write access independently from `system.devices` (trusted phones). `access.wireguard` is redefined as the Companion-backed WireGuard peer lifecycle and no longer depends on ASC. Detection verifies that `ndmq` exists and can return the supported schemas required by each module. A readable module is advertised as read-only when write support cannot be established; a missing unrelated module never suppresses it.

The installer provisions the Entware `ndmq` package only when it is absent, before starting the candidate Companion. Existing installations, WireGuard peers, XKeen state, and routing state remain unchanged. A failed provision or self-check aborts the Companion update and preserves the running previous version.

## Android behavior

For a paired profile, the Devices segment and Access destination use only the active Companion endpoint. While loading, they show the existing progress state. Devices display online/offline state and static reservations. Access displays existing peers, their current state, and the established create/detail lifecycle. Editing keeps the current bottom-sheet interaction but inserts a concise review step before every router mutation.

The bottom navigation distinguishes direction and purpose:

- Connections contains outbound VPN choices such as XKeen/VLESS, sing-box, and AWG Manager;
- Access contains inbound WireGuard client accesses for phones and computers.

Android must not hide Access merely because create/rotate metadata, ASC, Collector, or write access is unavailable. It shows a readable peer inventory and disables only the unsupported actions.

Errors are actionable and do not mention a wrong KeenOS password:

- protected access missing: offer the router setup action;
- Companion update required or capability unavailable: offer `Update Companion`;
- router network data unavailable: show retry and a sanitized reason;
- stale plan: refresh the corresponding list and ask the user to review again;
- uncertain mutation: block more changes and offer explicit state recheck.
- WireGuard endpoint missing: keep the peer list usable and offer endpoint setup only from create or rotate.

An empty successful home-device inventory says that no home devices were reported. An empty successful WireGuard inventory says that no accesses were found on the selected interface. Neither empty state is shown together with an error card.

## Compatibility

The Android client may temporarily fall back to legacy RCI for home devices and WireGuard only for profiles without Companion, preserving old users before they complete setup. Once a valid Companion endpoint advertises the corresponding capability, Android must not fall back on Companion errors because doing so would silently reintroduce the second-password path and could mutate through a different state source.

`CapabilityRegistry` preserves authenticated Companion declarations and adds direct capabilities only when Companion does not declare that module. It must never overwrite a working Companion `access.wireguard` capability with local RCI readiness.

The existing `/v1/devices` trusted-phone API, profile encryption, Companion identity, XKeen configuration, routing rules, and existing WireGuard peer configuration remain unchanged during installation and upgrade. ASC is treated only as its own optional component and never as proof of WireGuard availability.

## Testing and release gates

Tests are written before production changes and cover:

- bounded `ndmq` execution, strict XML parsing, stable ordering, and null-to-empty normalization;
- safe command construction, address validation, stale reviews, idempotency, verification, rollback, and uncertain outcomes;
- discovery of real WireGuard interfaces without ASC, strict peer parsing, multiple-interface selection, and current-state normalization;
- create, rename, enable, disable, rotate, and revoke review/apply transactions, including rotation cutover and rollback;
- proof that private keys and complete client configurations remain phone-local and single-reveal;
- scope authorization and the distinction between trusted phones and home devices;
- capability detection with missing, read-only, and writable `ndmq`;
- installer provisioning and rollback behavior;
- pinned-TLS Android decoding, load/error/empty states, review/apply flow, and no RCI call when Companion advertises either capability;
- regression that one completed setup unlocks both Devices and Access without KeenOS RCI credentials;
- regression that Companion inventory exposes the verified router's conceptual `Wireguard0`/six-peer shape while absence of ASC does not hide it.

Before a local 2.1.2 candidate is offered, the complete Go race/vet/security suite, Android unit tests, lint, resource checks, debug and release builds, embedded-asset hash verification, SBOM generation, secret scan, APK signature verification, and release-certificate comparison must pass. Nothing is pushed or published without explicit user approval.
