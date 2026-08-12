# Companion Home Devices Design

## Goal

After the initial router setup, KeenWG must list home devices and manage their static IPv4 reservations through the already paired Companion token. The user must not enter or save a second KeenOS web-interface password, and the feature must work anywhere the Companion HTTPS address is reachable.

## Current problem

The Android `NetworkViewModel` loads devices through the legacy KeenOS RCI client using `ServerSettings.rciLogin` and `rciPassword`. Initial setup authenticates to Entware over SSH, deliberately erases that password, and stores only the Companion identity, certificate pin, and device token. As a result, Companion-backed domains and exclusions work while the Devices tab fails with a misleading KeenOS credentials error.

The router already exposes the required information locally through `ndmq`:

- `show ip hotspot` provides registered and currently connected hosts;
- `show ip dhcp bindings` provides DHCP leases;
- `show running-config` provides static DHCP reservations.

## Chosen architecture

Companion becomes the transport for the home-device inventory and static reservations.

1. A focused Go package executes `ndmq` with fixed commands and parses its bounded XML output into a versioned JSON device model.
2. The secure Companion server exposes a distinct `/v1/network/devices` resource. This name is deliberately separate from `/v1/devices`, which continues to mean phones trusted by Companion.
3. Android loads and mutates devices through a pinned-TLS `CompanionNetworkDeviceClient` using the active profile's device token.
4. The Devices tab no longer reads KeenOS RCI credentials. Existing RCI settings remain temporarily available to unrelated legacy WireGuard operations but are not used by this module.

No SSH password, KeenOS password, raw `ndmq` document, or MAC-address-bearing diagnostic output is added to reports or logs.

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

The review request contains schema version, observed `state_version`, and either a validated IPv4 address or `null` to remove the reservation. Companion checks that the device still exists, the address is inside the device's home IPv4 subnet, and it is not the network, broadcast, or router address. It returns a short-lived opaque `plan_id` and a human-readable before/after plan.

Apply requires the same target, observed version, `plan_id`, and a UUID idempotency key. Only operator and owner scopes may apply. Companion:

1. verifies the reviewed plan and unchanged state;
2. runs the fixed `ip dhcp host …` or `no ip dhcp host …` command through `ndmq`;
3. rereads the running configuration and verifies the target;
4. persists the router configuration;
5. rereads and returns the committed inventory.

On a failure before verification, Companion restores the previous reservation, saves, and verifies the rollback. The result is one of `committed`, `rolled_back`, `rejected`, or `uncertain`. An uncertain result blocks further reservation writes until a fresh inventory proves a stable state. Requests with stale `state_version` or an expired/mismatched plan are rejected without mutation.

## Capability and installation behavior

A new capability `network.home_devices` reports read or write access independently from `system.devices` (trusted phones). Detection verifies that `ndmq` exists and can return supported hotspot, lease, and running-config schemas. If read works but mutation support cannot be proven, the capability is read-only.

The installer provisions the Entware `ndmq` package only when it is absent, before starting the candidate Companion. Existing installations and state remain unchanged. A failed provision or self-check aborts the Companion update and preserves the running previous version.

## Android behavior

The Devices segment uses only the active Companion endpoint. While loading, it shows the existing progress state. On success it displays online/offline state and static reservations exactly as today. Editing keeps the current bottom-sheet interaction but inserts a concise review step before apply.

Errors are actionable and do not mention a wrong KeenOS password:

- protected access missing: offer the router setup action;
- Companion update required or capability unavailable: offer `Update Companion`;
- router network data unavailable: show retry and a sanitized reason;
- stale plan: refresh the device list and ask the user to review again;
- uncertain mutation: block more changes and offer explicit state recheck.

An empty successful inventory says that no home devices were reported; it is never shown together with an error card.

## Compatibility

The Android client may temporarily fall back to legacy RCI only for profiles without Companion, preserving old users before they complete setup. Once a valid Companion endpoint advertises `network.home_devices`, Android must not fall back on Companion errors because doing so would silently reintroduce the second-password path and could mutate through a different state source.

The existing `/v1/devices` trusted-phone API, profile encryption, Companion identity, XKeen configuration, routing rules, and WireGuard behavior are unchanged.

## Testing and release gates

Tests are written before production changes and cover:

- bounded `ndmq` execution, strict XML parsing, stable ordering, and null-to-empty normalization;
- safe command construction, address validation, stale reviews, idempotency, verification, rollback, and uncertain outcomes;
- scope authorization and the distinction between trusted phones and home devices;
- capability detection with missing, read-only, and writable `ndmq`;
- installer provisioning and rollback behavior;
- pinned-TLS Android decoding, load/error/empty states, review/apply flow, and no RCI call when Companion advertises the capability;
- regression that one completed setup unlocks the Devices tab without KeenOS RCI credentials.

Before a local 2.1.2 candidate is offered, the complete Go race/vet/security suite, Android unit tests, lint, resource checks, debug and release builds, embedded-asset hash verification, SBOM generation, secret scan, APK signature verification, and release-certificate comparison must pass. Nothing is pushed or published without explicit user approval.
