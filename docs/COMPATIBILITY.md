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

RCI and Collector availability is independent from Companion. Missing history must not block WireGuard management; missing RCI must not block Companion-backed connection and routing screens.

Evidence excludes credentials, subscription URLs, connection keys, full IP addresses, MAC addresses and hostnames. Run `scripts/router-evidence/verify-evidence.ps1` before proposing a new supported record.
