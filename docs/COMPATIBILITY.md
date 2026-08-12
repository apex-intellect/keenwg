# KeenWG 2.0 compatibility matrix

Support is model-specific. `Supported` requires a sanitized physical record with the complete install, pairing, mutation, restart, update, rollback and reinstall lifecycle. Architecture detection and source tests alone are not physical evidence.

| Model / evidence | KeeneticOS | Architecture | Entware | Engines | Status |
|---|---|---|---|---|---|
| Netcraze Hopper SE (NC-3812) / `netcraze-nc3812-5-01c1` | 5.01.C.1.0-0 | arm64 | present | XKeen 2.0; Xray 26.3.27 | supported |

Experimental until a matching physical record passes:

- other Keenetic/Netcraze ARM64 models and firmware builds;
- standalone sing-box and AWG Manager adapters;
- alternative local network layouts.

Not supported:

- MIPS/MIPSel Companion builds;
- public, WAN or wildcard Companion listeners;
- automatic subscription refresh, route change or country switching.

Companion uses the local KeeneticOS `ndmq` interface for home-device inventory, static DHCP reservations and the WireGuard peer lifecycle. RCI is only a legacy fallback for unpaired profiles. Collector remains an independent, optional history source: missing history must never block current WireGuard inventory or management.

The Android setup probes Entware, optional XKeen and free `/opt` space before any upload. Entware and sufficient `/opt` space are required only when the protected component must be installed or updated; an already current component can pair without XKeen. The installer provisions the Entware `ndmq` package only when it is missing and before replacing the active Companion. Missing prerequisites are non-mutating. KeenWG does not format, partition, mount or erase storage and does not auto-install Entware or XKeen.

Evidence excludes credentials, subscription URLs, connection keys, full IP addresses, MAC addresses and hostnames. Run `scripts/router-evidence/verify-evidence.ps1` before proposing a new supported record.
