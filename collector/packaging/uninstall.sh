#!/bin/sh
set -eu

ROOT=${KEENWG_DESTDIR:-}
PURGE=false
LIVE=false
if [ -z "$ROOT" ] || [ "${KEENWG_TEST_LIVE:-0}" = 1 ]; then LIVE=true; fi
PROC_ROOT=${KEENWG_PROC_ROOT:-/proc}
COLLECTOR_PREFIX=${KEENWG_COLLECTOR_PREFIX:-/opt/keenwg}
PROC_SCAN_LIMIT=${KEENWG_PROC_SCAN_LIMIT:-32768}
LOCK_DIR=$ROOT/opt/var/lock
LOCK=$LOCK_DIR/keenwg-lifecycle.lock
LOCK_OWNER=$LOCK/owner
LOCK_MUTEX_PREFIX=$LOCK_DIR/.keenwg-lifecycle-mutex-
LOCK_PROC_ROOT=${KEENWG_LOCK_PROC_ROOT:-/proc}
LOCK_BOOT_ID_FILE=${KEENWG_LOCK_BOOT_ID_FILE:-/proc/sys/kernel/random/boot_id}
LOCK_SELF_PID=${KEENWG_LOCK_SELF_PID:-$$}
LOCK_ALIVE_CMD=${KEENWG_LOCK_ALIVE:-}
lock_held=false
lock_candidate=
lock_quarantine=
lock_mutex_held=false
fail() { echo "$*" >&2; exit 1; }
case "$#:${1:-}" in 0:) ;; 1:--purge) PURGE=true ;; *) echo "usage: $0 [--purge]" >&2; exit 2;; esac
case "$ROOT" in
    ''|/*) ;;
    *) echo "KEENWG_DESTDIR must be empty or absolute" >&2; exit 2 ;;
esac
[ "$ROOT" != / ] || { echo "KEENWG_DESTDIR=/ is forbidden" >&2; exit 2; }
case "$ROOT" in *'/../'*|*/..|*'/./'*|*/.) echo "KEENWG_DESTDIR must be canonical" >&2; exit 2;; esac
if [ -n "$ROOT" ]; then
    [ -d "$ROOT" ] || { echo "KEENWG_DESTDIR must already exist" >&2; exit 2; }
    resolved_root=$(CDPATH= cd -- "$ROOT" && pwd -P)
    [ "$resolved_root" = "$ROOT" ] || { echo "KEENWG_DESTDIR must be canonical" >&2; exit 2; }
fi
case "$PROC_SCAN_LIMIT" in ''|*[!0-9]*) echo "invalid process scan limit" >&2; exit 2;; esac
[ "$PROC_SCAN_LIMIT" -ge 1 ] && [ "$PROC_SCAN_LIMIT" -le 131072 ] || { echo "invalid process scan limit" >&2; exit 2; }
case "$COLLECTOR_PREFIX" in /*) ;; *) echo "collector process prefix must be absolute" >&2; exit 2;; esac
case "$COLLECTOR_PREFIX" in *'/../'*|*/..|*'/./'*|*/.|*/) echo "collector process prefix must be canonical" >&2; exit 2;; esac

assert_safe_target() {
    target=$1
    case "$target" in
        "$ROOT/opt/keenwg"|"$ROOT/opt/etc/keenwg"|"$ROOT/opt/var/lib/keenwg"|"$ROOT/opt/etc/init.d/S95keenwg"|"$ROOT/opt/etc/ndm/ifcreated.d/95-keenwg"|"$ROOT/opt/etc/ndm/ifdestroyed.d/95-keenwg"|"$ROOT/opt/etc/ndm/ifipchanged.d/95-keenwg") ;;
        *) echo "refusing unsafe uninstall target: $target" >&2; exit 1 ;;
    esac
    relative=${target#"$ROOT"/}; current=$ROOT
    while [ -n "$relative" ]; do
        component=${relative%%/*}; [ "$component" = "$relative" ] && relative= || relative=${relative#*/}; current=$current/$component
        [ -L "$current" ] && { echo "refusing symlink uninstall target: $current" >&2; exit 1; }
    done
    return 0
}

assert_safe_lock_path() {
    target=$1
    case "$target" in
        "$ROOT/opt"|"$ROOT/opt/var"|"$LOCK_DIR"|"$LOCK"|"$LOCK_OWNER"|"$LOCK_MUTEX_PREFIX") ;;
        *) fail "refusing unsafe lifecycle lock path: $target" ;;
    esac
    relative=${target#"$ROOT"/}; current=$ROOT
    while [ -n "$relative" ]; do
        component=${relative%%/*}; [ "$component" = "$relative" ] && relative= || relative=${relative#*/}; current=$current/$component
        case "$component" in ''|.|..) fail "refusing unsafe lifecycle lock component";; esac
        [ -L "$current" ] && fail "refusing lifecycle lock symlink: $current"
    done
    return 0
}

read_lock_start_time() {
    lock_stat_line=$(cat "$LOCK_PROC_ROOT/$1/stat" 2>/dev/null || true)
    lock_stat_tail=${lock_stat_line##*) }
    [ -n "$lock_stat_line" ] && [ "$lock_stat_tail" != "$lock_stat_line" ] || return 1
    set -- $lock_stat_tail
    [ "$#" -ge 20 ] || return 1
    shift 19
    case "$1" in ''|*[!0-9]*) return 1;; esac
    lock_observed_start=$1
}

lock_process_alive() {
    if [ -n "$LOCK_ALIVE_CMD" ]; then "$LOCK_ALIVE_CMD" "$1"; else kill -0 "$1" 2>/dev/null; fi
}

read_lock_boot_id() {
    [ -f "$LOCK_BOOT_ID_FILE" ] && [ ! -L "$LOCK_BOOT_ID_FILE" ] || return 1
    [ "$(awk 'END { print NR }' "$LOCK_BOOT_ID_FILE")" = 1 ] || return 1
    lock_boot=$(sed -n '1p' "$LOCK_BOOT_ID_FILE")
    [ -n "$lock_boot" ] && [ "${#lock_boot}" -le 128 ] || return 1
    case "$lock_boot" in *[!A-Za-z0-9._-]*) return 1;; esac
}

prepare_lock_identity() {
    case "$LOCK_SELF_PID" in ''|*[!0-9]*) fail "invalid lifecycle lock PID";; esac
    read_lock_start_time "$LOCK_SELF_PID" || fail "cannot read lifecycle lock process identity"
    lock_self_start=$lock_observed_start
    read_lock_boot_id || fail "cannot read lifecycle lock boot identity"
    lock_self_boot=$lock_boot
    LOCK_MUTEX=$LOCK_MUTEX_PREFIX$lock_self_boot
}

inspect_lock_owner() {
    lock_owner_file=$1
    [ -f "$lock_owner_file" ] && [ ! -L "$lock_owner_file" ] || { lock_state=unsafe; return; }
    lock_extra=
    lock_trailing=
    {
        IFS=' ' read -r lock_pid lock_start lock_owner_boot lock_extra || { lock_state=unsafe; return; }
        if IFS= read -r lock_trailing || [ -n "$lock_trailing" ]; then lock_state=unsafe; return; fi
    } <"$lock_owner_file"
    [ -z "$lock_extra" ] || { lock_state=unsafe; return; }
    case "$lock_pid" in ''|*[!0-9]*) lock_state=unsafe; return;; esac
    case "$lock_start" in ''|*[!0-9]*) lock_state=unsafe; return;; esac
    [ -n "$lock_owner_boot" ] && [ "${#lock_owner_boot}" -le 128 ] || { lock_state=unsafe; return; }
    case "$lock_owner_boot" in *[!A-Za-z0-9._-]*) lock_state=unsafe; return;; esac
    if [ "$lock_owner_boot" != "$lock_self_boot" ]; then lock_state=stale; return; fi
    if ! read_lock_start_time "$lock_pid"; then
        if lock_process_alive "$lock_pid"; then lock_state=unsafe; else lock_state=stale; fi
        return
    fi
    if [ "$lock_observed_start" != "$lock_start" ]; then lock_state=stale; return; fi
    if lock_process_alive "$lock_pid"; then lock_state=active; else lock_state=stale; fi
}

inspect_existing_lock() {
    [ -d "$LOCK" ] && [ ! -L "$LOCK" ] || { lock_state=unsafe; return; }
    inspect_lock_owner "$LOCK_OWNER"
}

publish_lock_candidate() {
    lock_candidate=$(mktemp -d "$LOCK_DIR/.keenwg-candidate.XXXXXX") || return 1
    chmod 700 "$lock_candidate" || { rm -rf "$lock_candidate"; lock_candidate=; return 1; }
    if [ "${KEENWG_TEST_LOCK_OWNER_WRITE_FAIL:-0}" = 1 ]; then rm -rf "$lock_candidate"; lock_candidate=; return 1; fi
    printf '%s %s %s\n' "$LOCK_SELF_PID" "$lock_self_start" "$lock_self_boot" >"$lock_candidate/owner" || { rm -rf "$lock_candidate"; lock_candidate=; return 1; }
    chmod 600 "$lock_candidate/owner" || { rm -rf "$lock_candidate"; lock_candidate=; return 1; }
    if [ -e "$LOCK" ] || [ -L "$LOCK" ]; then rm -rf "$lock_candidate"; lock_candidate=; return 1; fi
    if mv -T "$lock_candidate" "$LOCK" 2>/dev/null; then
        lock_candidate=
        lock_held=true
        return 0
    fi
    rm -rf "$lock_candidate"
    lock_candidate=
    return 1
}

quarantine_lock() {
    quarantine_kind=$1
    case "$quarantine_kind" in stale|release) ;; *) return 1;; esac
    lock_quarantine=$(mktemp -d "$LOCK_DIR/.keenwg-$quarantine_kind.XXXXXX") || return 1
    chmod 700 "$lock_quarantine" || { rm -rf "$lock_quarantine"; lock_quarantine=; return 1; }
    if mv -T "$LOCK" "$lock_quarantine/lock" 2>/dev/null; then
        return 0
    fi
    rmdir "$lock_quarantine" 2>/dev/null || true
    lock_quarantine=
    return 1
}

drop_lock_mutex() {
    [ "$lock_mutex_held" = true ] || return 0
    [ -d "$LOCK_MUTEX" ] && [ ! -L "$LOCK_MUTEX" ] || return 1
    rm -rf "$LOCK_MUTEX" || return 1
    lock_mutex_held=false
}

acquire_lock_mutex() {
    lock_state=unsafe
    if [ -e "$LOCK_MUTEX" ] || [ -L "$LOCK_MUTEX" ]; then
        [ -d "$LOCK_MUTEX" ] && [ ! -L "$LOCK_MUTEX" ] || { lock_state=unsafe; return 1; }
        inspect_lock_owner "$LOCK_MUTEX/owner"
        return 1
    fi
    mutex_created=false
    trap '' HUP INT TERM
    if mkdir "$LOCK_MUTEX" 2>/dev/null; then
        lock_mutex_held=true
        mutex_created=true
    fi
    trap 'exit 1' HUP INT TERM
    if [ "$mutex_created" = false ]; then
        if [ -d "$LOCK_MUTEX" ] && [ ! -L "$LOCK_MUTEX" ]; then inspect_lock_owner "$LOCK_MUTEX/owner"; else lock_state=unsafe; fi
        return 1
    fi
    chmod 700 "$LOCK_MUTEX" || { drop_lock_mutex || true; return 1; }
    if [ "${KEENWG_TEST_LOCK_MUTEX_OWNER_WRITE_FAIL:-0}" = 1 ]; then drop_lock_mutex || true; return 1; fi
    printf '%s %s %s\n' "$LOCK_SELF_PID" "$lock_self_start" "$lock_self_boot" >"$LOCK_MUTEX/owner" || { drop_lock_mutex || true; return 1; }
    chmod 600 "$LOCK_MUTEX/owner" || { drop_lock_mutex || true; return 1; }
    if [ -n "${KEENWG_TEST_LOCK_MUTEX_HOOK:-}" ]; then
        "$KEENWG_TEST_LOCK_MUTEX_HOOK" || { drop_lock_mutex || true; return 1; }
    fi
    return 0
}

acquire_lifecycle_lock() {
    prepare_lock_identity
    if ! acquire_lock_mutex; then
        case "$lock_state" in
            active) fail "another lifecycle lock acquisition is active" ;;
            stale) fail "stale lifecycle acquisition mutex requires a reboot" ;;
            *) fail "lifecycle acquisition mutex is unsafe" ;;
        esac
    fi
    if [ -e "$LOCK" ] || [ -L "$LOCK" ]; then
        inspect_existing_lock
        case "$lock_state" in
            active) drop_lock_mutex || true; fail "another KeenWG lifecycle operation is active" ;;
            unsafe) drop_lock_mutex || true; fail "lifecycle lock is unsafe" ;;
            stale)
                if ! quarantine_lock stale; then drop_lock_mutex || true; fail "could not quarantine stale lifecycle lock"; fi
                rm -rf "$lock_quarantine" || { drop_lock_mutex || true; fail "could not remove stale lifecycle lock"; }
                lock_quarantine=
                ;;
        esac
    fi
    [ ! -e "$LOCK" ] && [ ! -L "$LOCK" ] || { drop_lock_mutex || true; fail "lifecycle lock appeared during acquisition"; }
    if ! publish_lock_candidate; then drop_lock_mutex || true; fail "could not publish lifecycle lock"; fi
    drop_lock_mutex || fail "could not release lifecycle acquisition mutex"
}

release_lifecycle_lock() {
    [ "$lock_held" = true ] || return 0
    [ -d "$LOCK" ] && [ ! -L "$LOCK" ] && [ -f "$LOCK_OWNER" ] && [ ! -L "$LOCK_OWNER" ] || return 1
    [ "$(cat "$LOCK_OWNER" 2>/dev/null || true)" = "$LOCK_SELF_PID $lock_self_start $lock_self_boot" ] || return 1
    quarantine_lock release || return 1
    lock_held=false
    rm -rf "$lock_quarantine" || return 1
    lock_quarantine=
}

lock_cleanup() {
    if [ -n "$lock_candidate" ] && [ -d "$lock_candidate" ] && [ ! -L "$lock_candidate" ]; then rm -rf "$lock_candidate"; fi
    if [ "$lock_held" = true ]; then release_lifecycle_lock >/dev/null 2>&1 || true; fi
    if [ "$lock_mutex_held" = true ]; then drop_lock_mutex >/dev/null 2>&1 || true; fi
}

for lock_path in "$ROOT/opt" "$ROOT/opt/var" "$LOCK_DIR" "$LOCK" "$LOCK_OWNER" "$LOCK_MUTEX_PREFIX"; do assert_safe_lock_path "$lock_path"; done
mkdir -p "$LOCK_DIR"
trap lock_cleanup EXIT
trap 'exit 1' HUP INT TERM
acquire_lifecycle_lock

is_collector_executable() {
    candidate=$1
    case "$candidate" in *' (deleted)') candidate=${candidate%' (deleted)'};; esac
    case "$candidate" in
        "$COLLECTOR_PREFIX/current/keenwg-collector") return 0 ;;
        "$COLLECTOR_PREFIX/releases/"*/keenwg-collector)
            release_id=${candidate#"$COLLECTOR_PREFIX/releases/"}
            release_id=${release_id%/keenwg-collector}
            case "$release_id" in ''|.|..|*/*|*[!A-Za-z0-9._-]*) return 1;; esac
            return 0
            ;;
        *) return 1 ;;
    esac
}

collector_process_is_live() {
    scanned=0
    for proc_dir in "$PROC_ROOT"/[0-9]*; do
        [ -d "$proc_dir" ] || continue
        scanned=$((scanned + 1))
        [ "$scanned" -le "$PROC_SCAN_LIMIT" ] || return 2
        proc_pid=${proc_dir##*/}
        case "$proc_pid" in ''|*[!0-9]*) continue;; esac
        proc_exe=$(readlink "$proc_dir/exe" 2>/dev/null || true)
        [ -n "$proc_exe" ] || continue
        if is_collector_executable "$proc_exe"; then return 0; fi
    done
    return 1
}

require_no_live_collector() {
    if collector_process_is_live; then
        echo "collector executable is still running; uninstall aborted" >&2
        return 1
    else
        scan_status=$?
    fi
    [ "$scan_status" = 1 ] || { echo "collector process scan exceeded its safety bound" >&2; return 1; }
}

INIT=$ROOT/opt/etc/init.d/S95keenwg
assert_safe_target "$INIT"
if $LIVE; then
    if collector_process_is_live; then
        [ -f "$INIT" ] && [ -x "$INIT" ] || { echo "collector is running but its init script is unavailable; uninstall aborted" >&2; exit 1; }
    else
        scan_status=$?
        [ "$scan_status" = 1 ] || { echo "collector process scan exceeded its safety bound" >&2; exit 1; }
    fi
    if [ -f "$INIT" ] && [ -x "$INIT" ]; then
        "$INIT" stop || { echo "collector stop failed; uninstall aborted" >&2; exit 1; }
    fi
    require_no_live_collector || exit 1
fi
rm -f "$INIT"
for hook_dir in ifcreated.d ifdestroyed.d ifipchanged.d; do
    hook=$ROOT/opt/etc/ndm/$hook_dir/95-keenwg
    assert_safe_target "$hook"
    rm -f "$hook"
done
assert_safe_target "$ROOT/opt/keenwg"
rm -rf "$ROOT/opt/keenwg"

guarded_purge() {
    target=$1
    case "$target" in
        "$ROOT/opt/etc/keenwg"|"$ROOT/opt/var/lib/keenwg") assert_safe_target "$target"; rm -rf "$target" ;;
        *) echo "refusing unsafe purge target: $target" >&2; exit 1 ;;
    esac
}

if $PURGE; then
    guarded_purge "$ROOT/opt/etc/keenwg"
    guarded_purge "$ROOT/opt/var/lib/keenwg"
fi
echo "KeenWG collector uninstalled (config and database preserved: $([ "$PURGE" = true ] && echo no || echo yes))"
