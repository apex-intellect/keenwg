#!/bin/sh
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TMP=$(mktemp -d)
PROCESSES=
cleanup() {
    for pid in $PROCESSES; do kill "$pid" 2>/dev/null || :; done
    for file in "$TMP"/*/opt/var/run/keenwg-companion.pid "$TMP"/*/cron-pid; do
        [ -f "$file" ] || continue
        pid=$(sed -n '1p' "$file")
        case "$pid" in ''|0|*[!0-9]*) continue;; esac
        kill "$pid" 2>/dev/null || :
    done
    rm -rf "$TMP"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
fail() {
    echo "$*" >&2
    if [ -n "${R:-}" ] && [ -f "$R/opt/var/log/keenwg-companion.log" ]; then tail -n 12 "$R/opt/var/log/keenwg-companion.log" >&2; fi
    exit 1
}
wait_file() {
    attempt=0
    while [ ! -f "$1" ]; do
        attempt=$((attempt+1)); [ "$attempt" -lt 200 ] || fail "timed out: $1"
        sleep 0.02
    done
}
count_starts() { if [ -f "$R/events" ]; then awk '$1 == "start" {n++} END {print n+0}' "$R/events"; else echo 0; fi; }
wait_starts() {
    attempt=0
    while [ "$(count_starts)" -lt "$1" ]; do
        attempt=$((attempt+1)); [ "$attempt" -lt 200 ] || fail 'service did not start'
        sleep 0.02
    done
}
control() {
    if [ "${KEENWG_TEST_BUSYBOX:-0}" = 1 ]; then
        KEENWG_DESTDIR="$R" busybox sh "$INIT" "$@"
    else
        KEENWG_DESTDIR="$R" sh "$INIT" "$@"
    fi
}
launch() {
    if [ "${KEENWG_TEST_BUSYBOX:-0}" = 1 ]; then
        KEENWG_DESTDIR="$R" busybox sh "$INIT" "$@" &
    else
        KEENWG_DESTDIR="$R" sh "$INIT" "$@" &
    fi
    CONTROL_PID=$!
    PROCESSES="$PROCESSES $CONTROL_PID"
}

MANAGED='* * * * * /opt/etc/init.d/S96keenwg-companion watchdog >/dev/null 2>&1 # keenwg-companion-watchdog'
FOREIGN='*/7 * * * * /opt/bin/foreign # keep'
LOOKALIKE='* * * * * /opt/bin/not-keenwg # keenwg-companion-watchdog'

make_root() {
    R=$TMP/$1
    INIT=$R/opt/etc/init.d/S96keenwg-companion
    W=$R/tmp/keenwg-companion-watchdog
    CRON=$R/opt/var/spool/cron/crontabs/root
    mkdir -p "$R/opt/lib/keenwg-companion/current" "$R/opt/etc/keenwg/update" "$R/opt/etc/init.d" "$R/opt/var/run" "$R/opt/bin" "${CRON%/*}" "$R/proc"
    cp "$HERE/S96keenwg-companion" "$INIT"
    printf '%s\n' '{"schema_version":2,"secure_listen_address":"192.168.1.1:18779"}' >"$R/opt/etc/keenwg/companion.json"
    printf '%s\n' "$FOREIGN" "$LOOKALIKE" >"$CRON"
    cp "$CRON" "$R/original-cron"
    printf '%s\n' '/dev/sda1 /opt ext4 rw,relatime 0 0' >"$R/proc/mounts"
    cat >"$R/opt/lib/keenwg-companion/current/keenwg-companion" <<'EOF'
#!/bin/sh
set -eu
R=$KEENWG_DESTDIR
READY=$R/opt/var/run/keenwg-companion.ready
echo "start $$" >>"$R/events"
echo 'fake stderr diagnostic' >&2
[ -f "$R/no-ready" ] || : >"$READY"
finish() {
    : >"$R/stopping"
    while [ -f "$R/hold-stop" ]; do sleep 0.02; done
    rm -f "$READY"
    exit 0
}
trap finish TERM INT
while :; do sleep 0.02; done
EOF
    printf '%s\n' '#!/bin/sh' 'exit 0' >"$R/opt/lib/keenwg-companion/current/keenwg-updater"
    cat >"$R/opt/bin/crontab" <<'EOF'
#!/bin/sh
set -eu
R=$KEENWG_DESTDIR
TABLE=$R/opt/var/spool/cron/crontabs/root
if [ "$1" = -l ]; then
    if [ -f "$R/fail-cron-read" ]; then echo 'partial untrustworthy output'; exit 1; fi
    if [ -f "$R/hold-cron" ]; then
        echo "$$" >"$R/cron-pid"
        : >"$R/cron-entered"
        while [ -f "$R/hold-cron" ]; do sleep 0.02; done
    fi
    [ -f "$TABLE" ] || exit 1
    cat "$TABLE"
    if [ -f "$R/edit-cron" ]; then
        echo '1 2 3 4 5 /opt/bin/new-foreign-job' >>"$TABLE"
        rm -f "$R/edit-cron"
    fi
else
    cp "$1" "$TABLE"
    echo installed >>"$R/cron-writes"
fi
EOF
    chmod 755 "$INIT" "$R/opt/bin/crontab" "$R/opt/lib/keenwg-companion/current/keenwg-companion" "$R/opt/lib/keenwg-companion/current/keenwg-updater"
}

# Healthy service, stderr capture, exact cron registration and duplicate safety.
make_root healthy
control start
wait_file "$R/opt/var/run/keenwg-companion.ready"
grep -q 'fake stderr diagnostic' "$R/opt/var/log/keenwg-companion.log" || fail 'stderr not captured'
control start
[ "$(grep -Fxc "$MANAGED" "$CRON")" = 1 ] || fail 'duplicate managed cron'
grep -Fxq "$FOREIGN" "$CRON" && grep -Fxq "$LOOKALIKE" "$CRON" || fail 'foreign cron changed'
echo 2 >"$W/failures"
control watchdog
[ ! -e "$W/failures" ] && [ "$(count_starts)" = 1 ] || fail 'healthy service restarted'

# Three failures, not one or two; subsequent restarts respect five-minute cooldown.
rm -f "$R/opt/var/run/keenwg-companion.ready"
control watchdog; control watchdog
[ "$(count_starts)" = 1 ] || fail 'watchdog restarted too early'
control watchdog
wait_file "$R/opt/var/run/keenwg-companion.ready"
[ "$(count_starts)" = 2 ] || fail 'third failure did not restart'
touch "$R/no-ready"
rm -f "$R/opt/var/run/keenwg-companion.ready"
control watchdog; control watchdog; control watchdog
[ "$(count_starts)" = 2 ] || fail 'cooldown ignored'
# BusyBox ash standalone applets bypass PATH-based fake date commands.
# Advance only the test's recorded restart time, without changing any clock.
previous_restart=$(sed -n '1p' "$W/last_restart")
printf '%s\n' "$((previous_restart - 300))" >"$W/last_restart"
control watchdog
wait_starts 3
[ "$(count_starts)" = 3 ] || fail 'restart did not resume after cooldown'
control stop
control watchdog
[ "$(count_starts)" = 3 ] && [ -f "$W/disabled" ] || fail 'manual stop was reversed'

# Manual stop owns the same queue as watchdog/restart/start.
make_root concurrent
control start; wait_file "$R/opt/var/run/keenwg-companion.ready"
touch "$R/hold-stop"
launch stop; STOP_PID=$CONTROL_PID
wait_file "$R/stopping"
control watchdog; control watchdog
launch start; START_PID=$CONTROL_PID
sleep 0.1
[ "$(count_starts)" = 1 ] || fail 'start or watchdog bypassed stop lock'
rm -f "$R/hold-stop"
wait "$STOP_PID"; wait "$START_PID"
wait_file "$R/opt/var/run/keenwg-companion.ready"
[ "$(count_starts)" = 2 ] && [ ! -e "$W/disabled" ] || fail 'queued manual start did not finish'
rm -f "$R/stopping"
touch "$R/hold-stop"
launch restart; RESTART_PID=$CONTROL_PID
wait_file "$R/stopping"
control watchdog
[ "$(count_starts)" = 2 ] || fail 'watchdog bypassed restart lock'
rm -f "$R/hold-stop"
wait "$RESTART_PID"
wait_starts 3
[ "$(count_starts)" = 3 ] || fail 'manual restart duplicated or failed'
control stop

# SIGKILL cannot leave a permanent lock; stale PID and legacy mkdir are harmless.
make_root killed
touch "$R/hold-cron"
launch start; OWNER=$CONTROL_PID
wait_file "$R/cron-entered"
kill -KILL "$OWNER"
wait "$OWNER" 2>/dev/null || :
rm -f "$R/hold-cron"
mkdir -p "$W/lock" "$W/control/2147483647-1" "$W/control/$$-1"
control start
wait_file "$R/opt/var/run/keenwg-companion.ready"
[ "$(count_starts)" = 1 ] || fail 'dead owner or PID reuse blocked recovery'
control stop

# Updates and missing/read-only Entware must never trigger recovery actions.
make_root skips
touch "$R/opt/etc/keenwg/update/pending.json"
control watchdog
[ ! -e "$W" ] || fail 'watchdog touched state during update'
rm -f "$R/opt/etc/keenwg/update/pending.json"
ln -s absent-update "$R/opt/etc/keenwg/update/pending.json"
control watchdog
[ ! -e "$W" ] || fail 'watchdog ignored unknown dangling update marker'
rm -f "$R/opt/etc/keenwg/update/pending.json"
printf '%s\n' '/dev/sda1 /opt ext4 ro,relatime 0 0' >"$R/proc/mounts"
control watchdog
[ ! -e "$W" ] || fail 'watchdog touched read-only opt'
mv "$R/opt" "$R/opt-offline"
KEENWG_DESTDIR="$R" sh "$HERE/S96keenwg-companion" watchdog
[ ! -e "$W" ] && [ ! -e "$R/opt" ] || fail 'watchdog recreated missing opt'

# Existing crontab read failures are not interpreted as an empty schedule.
make_root cronfailure
cp "$CRON" "$R/expected-cron"
touch "$R/fail-cron-read"
control start
cmp -s "$CRON" "$R/expected-cron" && [ ! -e "$R/cron-writes" ] || fail 'failed cron read destroyed jobs'
control stop
rm -f "$R/fail-cron-read"
touch "$R/edit-cron"
control start
grep -q new-foreign-job "$CRON" && [ ! -e "$R/cron-writes" ] || fail 'concurrent cron edit overwritten'
control stop

# A fresh absent root table is allowed only in the known accessible spool.
make_root freshcron
rm -f "$CRON"
control start
grep -Fxq "$MANAGED" "$CRON" || fail 'fresh cron not registered'
control stop

# Uninstall removes only the exact managed line and preserves user state.
make_root uninstall
control start; wait_file "$R/opt/var/run/keenwg-companion.ready"
cp "$CRON" "$R/expected-cron"
touch "$R/fail-cron-read"
if KEENWG_DESTDIR="$R" sh "$HERE/uninstall-companion.sh" >"$R/uninstall-error" 2>&1; then fail 'uninstall ignored cron read failure'; fi
cmp -s "$CRON" "$R/expected-cron" && [ -f "$INIT" ] || fail 'failed uninstall removed runtime/jobs'
rm -f "$R/fail-cron-read"
KEENWG_DESTDIR="$R" sh "$HERE/uninstall-companion.sh"
! grep -Fxq "$MANAGED" "$CRON" || fail 'managed cron remains'
grep -Fxq "$FOREIGN" "$CRON" && grep -Fxq "$LOOKALIKE" "$CRON" || fail 'uninstall removed foreign cron'
cmp -s "$CRON" "$R/original-cron" || fail 'foreign cron bytes changed on uninstall'
[ -f "$R/opt/etc/keenwg/companion.json" ] && [ ! -e "$INIT" ] || fail 'uninstall state/runtime mismatch'

# Exercise the real endpoint parser rather than DESTDIR's READY health shortcut.
make_root address
sed '/^case "${1:-}" in/,$d' "$INIT" >"$TMP/functions.sh"
address() { KEENWG_DESTDIR="$R" sh -c '. "$1"; health_address' sh "$TMP/functions.sh"; }
[ "$(address)" = 192.168.1.1:18779 ] || fail 'minified config health address failed'
printf '{\n  "secure_listen_address": "10.8.0.1:18779",\n  "schema_version": 2\n}\n' >"$R/opt/etc/keenwg/companion.json"
[ "$(address)" = 10.8.0.1:18779 ] || fail 'pretty config health address failed'
printf '%s\n' '{"secure_listen_address":"[::1]:18779"}' >"$R/opt/etc/keenwg/companion.json"
[ "$(address)" = '[::1]:18779' ] || fail 'IPv6 health address failed'
printf '%s\n' '{"secure_listen_address":"user@example.test/path"}' >"$R/opt/etc/keenwg/companion.json"
if address >/dev/null; then fail 'unsafe health endpoint accepted'; fi

# A pending update can appear during the probe, before recovery is attempted.
make_root update-during-probe
mkdir -p "$W"
echo 2 >"$W/failures"
KEENWG_DESTDIR="$R" sh -c '. "$1"; health() { : >"$ROOT/opt/etc/keenwg/update/pending.json"; return 1; }; watchdog' sh "$TMP/functions.sh"
[ "$(count_starts)" = 0 ] && [ ! -e "$W/last_restart" ] || fail 'watchdog restarted during a newly started update'

# Log rotation keeps the active path and a single bounded backup.
make_root rotation
control start; wait_file "$R/opt/var/run/keenwg-companion.ready"
dd if=/dev/zero of="$R/opt/var/log/keenwg-companion.log" bs=262145 count=1 2>/dev/null
control watchdog
[ "$(wc -c <"$R/opt/var/log/keenwg-companion.log.1")" = 262145 ] || fail 'oversized log not rotated'
[ "$(wc -c <"$R/opt/var/log/keenwg-companion.log")" -le 262144 ] || fail 'active log remains oversized'
control stop

echo 'watchdog tests passed'
