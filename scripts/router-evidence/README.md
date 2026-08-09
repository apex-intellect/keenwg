# Physical ARM64 evidence

KeenWG support claims are tied to a named router, exact KeeneticOS version, Entware state, engine versions and seven lifecycle stages. A source test, emulator, architecture probe or historical installation cannot produce `supported` status.

## Secure workflow

1. Configure SSH public-key authentication for a temporary test router account or run the commands in an interactive terminal. Never pass a password to `ssh`, `scp`, a script parameter or an environment variable.
2. Copy `collect-inventory.sh` to the router and run it. It reads only product/model/version presence and deliberately excludes configuration, addresses, interfaces, hostnames, keys and tokens.
3. Create a record conforming to `docs/evidence/schema.json` and enter only the sanitized inventory values. Existing records in `docs/evidence/records/` are examples, not templates for unsupported hardware.
4. Exercise each stage with the release bundles and the Android reviewed operation:
   - `install`: clean companion install and HTTPS self-check;
   - `migration`: upgrade a preserved 0.6/0.7/0.8/0.9 state to 1.0 without changing the active route;
   - `route_apply`: explicitly review and apply a test route, then verify read-back;
   - `restart`: restart the router/service and verify the same active route;
   - `update`: install the next bundle through its transactional installer;
   - `rollback`: inject a safe test failure or run the controller rollback self-test and verify the previous state;
   - `uninstall`: use the bundled uninstaller, verify preserved identity/configuration, then reinstall the intended release.
5. Immediately record each result with `record-stage.ps1`. `pass` is valid only after read-back verification, not because the command exited zero.
6. Run `verify-evidence.ps1`, review the record, and set `support_status` to `supported` only after all seven stages pass on ARM64 with Entware.
7. Regenerate `docs/COMPATIBILITY.md` with `generate-matrix.ps1`.

Example:

```powershell
.\scripts\router-evidence\record-stage.ps1 -Stage restart -Status pass -Code route_stable_after_restart -EvidenceFile .\docs\evidence\records\model-firmware.json
.\scripts\router-evidence\generate-matrix.ps1
```

The scripts intentionally do not automate destructive lifecycle actions over password SSH. Installation, route mutation, rollback and uninstall remain explicit and observable operations.
