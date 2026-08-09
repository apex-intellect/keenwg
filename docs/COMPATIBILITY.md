# KeenWG 1.0 compatibility matrix

Support is model-specific. `Supported` requires a sanitized physical record with all seven lifecycle stages passing. Source tests or architecture detection alone are not physical evidence.

| Model / evidence | KeeneticOS | Architecture | Entware | Engines | Physical lifecycle | Status |
|---|---|---|---|---|---|---|
| Netcraze Hopper SE (NC-3812) / `netcraze-nc3812-5-01c1` | 5.01.C.1.0-0 | arm64 | present | xkeen 2.0; xray 26.3.27 | 7/7 | supported |

Always experimental until a matching physical record passes: standalone sing-box, AWG Manager, MIPS/MIPSel, and any model/firmware combination absent above.

Unsupported in every release: public/WAN companion listeners and automatic route or country switching.

Evidence excludes credentials, subscription URLs, connection keys, full IP addresses, MAC addresses, and hostnames. Run `scripts/router-evidence/verify-evidence.ps1` before submitting a record.
