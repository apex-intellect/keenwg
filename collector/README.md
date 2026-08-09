# KeenWG router collector

KeenWG Collector is a read-only Entware daemon for aarch64 Keenetic/NetCraze routers. It polls local `ndmq`, stores bounded observed WireGuard history in SQLite, and exposes only health, metadata, and authenticated peer-history reads on the configured WireGuard IPv4 address.

## Build and install

Install the official Go 1.26.5 SDK without modifying the system installation, set `KEENWG_GO_ROOT` to it, and run from `collector`:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build.ps1
```

Release builds require a clean `collector/` Git tree. The build runs Go tests/vet and the shell syntax/lifecycle suite, builds the Linux/arm64 binary twice, verifies its embedded Go platform metadata, and creates the deterministic archive twice. The bundle includes `VERSION`, `SHA256SUMS`, and `BUILDINFO` (version, Git commit, and binary hash).

Copy `dist/keenwg-collector-0.3.0-linux-arm64.tar.gz` to the router, then over SSH:

```sh
mkdir -p /tmp/keenwg-install
tar -xzf /tmp/keenwg-collector-0.3.0-linux-arm64.tar.gz -C /tmp/keenwg-install
sh /tmp/keenwg-install/install.sh
```

The installer verifies the bundle `VERSION` and `SHA256SUMS` before constructing release paths. On a live router it then requires aarch64 and provisions `ndmq` with `opkg` only when the command is missing; both checks happen before the candidate runs `-check`. Each binary is published once under the content-addressed path `/opt/keenwg/releases/<version>-<sha256>`; an existing release is never overwritten. After the atomic `current` switch, installation succeeds only when the tracked PID/start-time identity is alive, `/proc/<pid>/exe` hashes to the bundle binary, no second KeenWG collector process exists, and health reports the bundle version. A restart, process-proof, or health failure restores the previous target together with its init/NDM hook files, then restarts and verifies the old release. The installer also creates a random 32-byte bearer token and writes configuration mode 0600. Existing config and database files survive updates.

Read the token once over the trusted SSH session and store it in the Android encrypted collector-token setting:

```sh
sed -n 's/.*"token"[ ]*:[ ]*"\([^"]*\)".*/\1/p' /opt/etc/keenwg/config.json
```

Use collector URL `http://10.8.0.1:18777` unless `listen_address` was deliberately changed to another literal WireGuard IPv4 address. Never expose this listener on a wildcard, WAN, or public-LAN address.

Health is intentionally unauthenticated and contains no peer or path data:

```sh
wget -qO- http://10.8.0.1:18777/v1/health
```

## History semantics

- A source failure marks the last current snapshot stale; it does not create an offline sample.
- Observed/online duration is accrued only between increasing samples no more than 150 seconds apart. Gaps are reported as missing coverage.
- Router RX is client upload; router TX is client download.
- Counter decreases or impossible integer deltas create one reset generation and add no traffic for that transition.
- Raw, five-minute, and hourly history default to 7, 90, and 400 days. The default database cap is 96 MiB; exceeding it pauses history writes while current health remains available.
- Aggregate queries report effective bucket-aligned `from`/`to` bounds. `coverage_ratio` is observed seconds divided by that effective interval.

## Operations

To back up SQLite, stop writes before copying the database and its WAL state:

```sh
/opt/etc/init.d/S95keenwg stop
cp -a /opt/var/lib/keenwg/history.db* /opt/backup/
/opt/etc/init.d/S95keenwg start
```

For an update, extract the new bundle and run its `install.sh`; config, token, and database survive. To remove the service but preserve them:

```sh
sh /tmp/keenwg-install/uninstall.sh
```

To permanently remove the collector-owned release, config, and database paths:

```sh
sh /tmp/keenwg-install/uninstall.sh --purge
```

Install and uninstall share `/opt/var/lock/keenwg-lifecycle.lock`. Every acquisition is serialized by a sibling, boot-scoped mutex; both owner records contain PID, process start time, and boot ID. Concurrent lifecycle operations therefore fail closed, while a main lock left by a terminated process or previous boot can be recovered safely. A symlink or malformed lock/mutex is never followed or replaced.

The purge guard accepts only the exact KeenWG paths under `/opt`; it does not remove unrelated Entware data. Before deleting a release, live uninstall also performs a bounded `/proc` scan for collector executables. It aborts if the init script is missing or non-executable while one is running, or if any collector remains after stop; unrelated processes are never signalled. The init script keeps a PID/start-time identity sidecar and retains both files when a same-path process has a mismatched identity and is still alive. NDM hooks signal only a matching collector identity and ask the init script to start the daemon when tracking is missing or stale.
