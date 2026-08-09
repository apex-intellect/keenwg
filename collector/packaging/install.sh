#!/bin/sh
set -eu
umask 077

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=${KEENWG_DESTDIR:-}
BINARY_SOURCE=$HERE/keenwg-collector
SUMS=$HERE/SHA256SUMS
VERSION_FILE=$HERE/VERSION
LIVE=false
if [ -z "$ROOT" ] || [ "${KEENWG_TEST_LIVE:-0}" = 1 ]; then LIVE=true; fi
SERVICE_PID_FILE=${KEENWG_PID_FILE:-/tmp/keenwg.pid}
SERVICE_IDENTITY_FILE=${KEENWG_IDENTITY_FILE:-$SERVICE_PID_FILE.start}
PROC_ROOT=${KEENWG_PROC_ROOT:-/proc}
PROCESS_ALIVE_CMD=${KEENWG_PROCESS_ALIVE:-}
COLLECTOR_PREFIX=${KEENWG_COLLECTOR_PREFIX:-/opt/keenwg}
PROC_SCAN_LIMIT=${KEENWG_PROC_SCAN_LIMIT:-32768}

fail() {
    echo "$*" >&2
    exit 1
}

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
case "$PROC_SCAN_LIMIT" in ''|*[!0-9]*) fail "invalid process scan limit";; esac
[ "$PROC_SCAN_LIMIT" -ge 1 ] && [ "$PROC_SCAN_LIMIT" -le 131072 ] || fail "invalid process scan limit"
case "$COLLECTOR_PREFIX" in /*) ;; *) fail "collector process prefix must be absolute";; esac
case "$COLLECTOR_PREFIX" in *'/../'*|*/..|*'/./'*|*/.|*/) fail "collector process prefix must be canonical";; esac

validate_version() {
    value=$1
    [ -n "$value" ] && [ "${#value}" -le 64 ] || fail "invalid KeenWG version"
    case "$value" in
        .|..|[!A-Za-z0-9]*|*[!A-Za-z0-9._-]*) fail "invalid KeenWG version: $value" ;;
    esac
}

[ -f "$VERSION_FILE" ] && [ ! -L "$VERSION_FILE" ] || fail "missing or unsafe VERSION manifest"
[ "$(awk 'END { print NR }' "$VERSION_FILE")" = 1 ] || fail "VERSION manifest must contain exactly one line"
bundle_version=$(sed -n '1p' "$VERSION_FILE")
validate_version "$bundle_version"
if [ "${KEENWG_VERSION+x}" = x ]; then
    validate_version "$KEENWG_VERSION"
    [ "$KEENWG_VERSION" = "$bundle_version" ] || fail "KEENWG_VERSION does not match bundle VERSION"
fi
VERSION=$bundle_version

assert_safe_path() {
    target=$1
    case "$target" in "$ROOT/opt"|"$ROOT/opt/"*) ;; *) fail "unsafe install path: $target";; esac
    relative=${target#"$ROOT"/}
    current=$ROOT
    while [ -n "$relative" ]; do
        component=${relative%%/*}
        [ "$component" = "$relative" ] && relative= || relative=${relative#*/}
        case "$component" in ''|.|..) fail "refusing unsafe install path component: $component";; esac
        current=$current/$component
        [ -L "$current" ] && fail "refusing symlink install path: $current"
    done
    return 0
}

assert_regular_or_absent() {
    target=$1
    if [ -e "$target" ] || [ -L "$target" ]; then
        [ -f "$target" ] && [ ! -L "$target" ] || fail "refusing non-regular install leaf: $target"
    fi
    return 0
}

for source_file in "$BINARY_SOURCE" "$SUMS" "$HERE/config.example.json" "$HERE/S95keenwg" "$HERE/95-keenwg-signal"; do
    [ -f "$source_file" ] && [ ! -L "$source_file" ] || fail "missing or unsafe bundle file: $source_file"
done
expected=$(awk '$2=="keenwg-collector" || $2=="*keenwg-collector" {print $1; exit}' "$SUMS")
[ -n "$expected" ] || fail "keenwg-collector checksum missing"
actual=$(sha256sum "$BINARY_SOURCE" | awk '{print $1}')
[ "$actual" = "$expected" ] || fail "keenwg-collector checksum mismatch"

RELEASE_ID=$VERSION-$actual
RELEASES=$ROOT/opt/keenwg/releases
RELEASE=$RELEASES/$RELEASE_ID
RELEASE_BINARY=$RELEASE/keenwg-collector
RELEASE_VERSION=$RELEASE/VERSION
CURRENT=$ROOT/opt/keenwg/current
CONFIG_DIR=$ROOT/opt/etc/keenwg
CONFIG=$CONFIG_DIR/config.json
DATA_DIR=$ROOT/opt/var/lib/keenwg
INIT=$ROOT/opt/etc/init.d/S95keenwg
LOCK_DIR=$ROOT/opt/var/lock
LOCK=$LOCK_DIR/keenwg-lifecycle.lock
LOCK_OWNER=$LOCK/owner
LOCK_MUTEX_PREFIX=$LOCK_DIR/.keenwg-lifecycle-mutex-
LOCK_PROC_ROOT=${KEENWG_LOCK_PROC_ROOT:-/proc}
LOCK_BOOT_ID_FILE=${KEENWG_LOCK_BOOT_ID_FILE:-/proc/sys/kernel/random/boot_id}
LOCK_SELF_PID=${KEENWG_LOCK_SELF_PID:-$$}
LOCK_ALIVE_CMD=${KEENWG_LOCK_ALIVE:-}

for lock_path in "$ROOT/opt" "$ROOT/opt/var" "$LOCK_DIR" "$LOCK" "$LOCK_OWNER" "$LOCK_MUTEX_PREFIX"; do
    assert_safe_path "$lock_path"
done
mkdir -p "$LOCK_DIR"

lock_held=false
lock_candidate=
lock_quarantine=
lock_mutex_held=false

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
    if [ -n "$LOCK_ALIVE_CMD" ]; then
        "$LOCK_ALIVE_CMD" "$1"
    else
        kill -0 "$1" 2>/dev/null
    fi
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

prelock_cleanup() {
    if [ -n "$lock_candidate" ] && [ -d "$lock_candidate" ] && [ ! -L "$lock_candidate" ]; then rm -rf "$lock_candidate"; fi
    if [ "$lock_held" = true ]; then release_lifecycle_lock >/dev/null 2>&1 || true; fi
    if [ "$lock_mutex_held" = true ]; then drop_lock_mutex >/dev/null 2>&1 || true; fi
}

trap prelock_cleanup EXIT
trap 'exit 1' HUP INT TERM
acquire_lifecycle_lock
tmp_release=
tmp_current=
tmp_current_dir=
managed_snapshot_active=false
managed_snapshot_dir=
current_switched=false
rollback_required=false
cleanup() {
    trap '' HUP INT TERM
    if [ "$rollback_required" = true ]; then
        restore_previous >/dev/null 2>&1 || true
    elif [ "$managed_snapshot_active" = true ]; then
        restore_managed_files >/dev/null 2>&1 || true
    fi
    if [ -n "$tmp_current" ] && [ -L "$tmp_current" ]; then rm -f "$tmp_current"; fi
    if [ -n "$tmp_current_dir" ] && [ -d "$tmp_current_dir" ] && [ ! -L "$tmp_current_dir" ]; then rm -rf "$tmp_current_dir"; fi
    if [ -n "$tmp_release" ] && [ -d "$tmp_release" ] && [ ! -L "$tmp_release" ]; then rm -rf "$tmp_release"; fi
    if [ -n "$lock_candidate" ] && [ -d "$lock_candidate" ] && [ ! -L "$lock_candidate" ]; then rm -rf "$lock_candidate"; fi
    if [ "$lock_held" = true ]; then release_lifecycle_lock >/dev/null 2>&1 || true; fi
    if [ "$lock_mutex_held" = true ]; then drop_lock_mutex >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
if [ -n "${KEENWG_TEST_LOCK_ACQUIRED_HOOK:-}" ]; then
    "$KEENWG_TEST_LOCK_ACQUIRED_HOOK"
fi

if $LIVE; then
    [ "$(uname -m)" = aarch64 ] || fail "KeenWG collector requires aarch64"
    if ! command -v ndmq >/dev/null 2>&1; then
        opkg install ndmq
    fi
    command -v ndmq >/dev/null 2>&1 || fail "ndmq is unavailable after package provisioning"
fi

old_target=
old_version=
old_hash=
if [ -e "$CURRENT" ] || [ -L "$CURRENT" ]; then
    [ -L "$CURRENT" ] || fail "current must be a relative release symlink"
    old_target=$(readlink "$CURRENT")
    case "$old_target" in
        releases/*)
            old_release_id=${old_target#releases/}
            case "$old_release_id" in ''|.|..|*/*|*[!A-Za-z0-9._-]*) fail "current has unsafe release target";; esac
            ;;
        *) fail "current must point inside releases" ;;
    esac
    old_release=$ROOT/opt/keenwg/$old_target
    assert_safe_path "$old_release"
    assert_safe_path "$old_release/keenwg-collector"
    assert_safe_path "$old_release/VERSION"
    [ -d "$old_release" ] && [ ! -L "$old_release" ] || fail "current release directory is missing or unsafe"
    [ -f "$old_release/keenwg-collector" ] && [ ! -L "$old_release/keenwg-collector" ] && [ -x "$old_release/keenwg-collector" ] || fail "current release binary is missing or unsafe"
    old_hash=$(sha256sum "$old_release/keenwg-collector" | awk '{print $1}')
    if [ -e "$old_release/VERSION" ] || [ -L "$old_release/VERSION" ]; then
        [ -f "$old_release/VERSION" ] && [ ! -L "$old_release/VERSION" ] || fail "current release version manifest is unsafe"
        [ "$(awk 'END { print NR }' "$old_release/VERSION")" = 1 ] || fail "current release version manifest is invalid"
        old_version=$(sed -n '1p' "$old_release/VERSION")
    else
        old_version_output=$("$old_release/keenwg-collector" -version 2>/dev/null) || fail "legacy current release cannot report its version"
        old_version=$(printf '%s\n' "$old_version_output" | awk '$1=="keenwg-collector" { print $2; exit }')
        [ -n "$old_version" ] || fail "legacy current release reported an invalid version"
    fi
    validate_version "$old_version"
fi

for safe_path in \
    "$ROOT/opt" "$ROOT/opt/keenwg" "$RELEASES" "$RELEASE" "$RELEASE_BINARY" "$RELEASE_VERSION" \
    "$ROOT/opt/var" "$LOCK_DIR" "$LOCK" "$LOCK_OWNER" \
    "$ROOT/opt/etc" "$CONFIG_DIR" "$CONFIG" "$DATA_DIR" "$ROOT/opt/etc/init.d" "$INIT" "$ROOT/opt/etc/ndm" \
    "$ROOT/opt/etc/ndm/ifcreated.d" "$ROOT/opt/etc/ndm/ifcreated.d/95-keenwg" \
    "$ROOT/opt/etc/ndm/ifdestroyed.d" "$ROOT/opt/etc/ndm/ifdestroyed.d/95-keenwg" \
    "$ROOT/opt/etc/ndm/ifipchanged.d" "$ROOT/opt/etc/ndm/ifipchanged.d/95-keenwg"
do
    assert_safe_path "$safe_path"
done
assert_regular_or_absent "$CONFIG"
assert_regular_or_absent "$INIT"
for hook_dir in ifcreated.d ifdestroyed.d ifipchanged.d; do
    assert_regular_or_absent "$ROOT/opt/etc/ndm/$hook_dir/95-keenwg"
done

if [ -e "$RELEASE" ] || [ -L "$RELEASE" ]; then
    [ -d "$RELEASE" ] && [ ! -L "$RELEASE" ] || fail "immutable release path is unsafe"
    [ -f "$RELEASE_BINARY" ] && [ ! -L "$RELEASE_BINARY" ] || fail "immutable release binary is missing or unsafe"
    [ -f "$RELEASE_VERSION" ] && [ ! -L "$RELEASE_VERSION" ] || fail "immutable release version manifest is missing or unsafe"
    release_hash=$(sha256sum "$RELEASE_BINARY" | awk '{print $1}')
    [ "$release_hash" = "$actual" ] && [ -x "$RELEASE_BINARY" ] || fail "immutable release content does not match bundle"
    [ "$(awk 'END { print NR }' "$RELEASE_VERSION")" = 1 ] && [ "$(sed -n '1p' "$RELEASE_VERSION")" = "$VERSION" ] || fail "immutable release version does not match bundle"
fi

mkdir -p "$ROOT/opt/keenwg" "$RELEASES" "$CONFIG_DIR" "$DATA_DIR" "$ROOT/opt/etc/init.d"
for hook_dir in ifcreated.d ifdestroyed.d ifipchanged.d; do
    mkdir -p "$ROOT/opt/etc/ndm/$hook_dir"
done
chmod 700 "$CONFIG_DIR" "$DATA_DIR"

if [ ! -e "$CONFIG" ]; then
    token=$(head -c 32 /dev/urandom | base64 | tr -d '\r\n')
    tmp_config=$CONFIG_DIR/.config.$$
    [ ! -e "$tmp_config" ] && [ ! -L "$tmp_config" ] || fail "temporary config path already exists"
    sed "s|REPLACE_WITH_BASE64_ENCODED_32_BYTE_TOKEN|$token|" "$HERE/config.example.json" >"$tmp_config"
    chmod 600 "$tmp_config"
    mv -fT "$tmp_config" "$CONFIG"
fi
chmod 600 "$CONFIG"

if [ ! -e "$RELEASE" ]; then
    tmp_release=$(mktemp -d "$RELEASES/.release.XXXXXX") || fail "could not stage immutable release"
    assert_safe_path "$tmp_release"
    cp "$BINARY_SOURCE" "$tmp_release/keenwg-collector"
    chmod 755 "$tmp_release/keenwg-collector"
    printf '%s\n' "$VERSION" >"$tmp_release/VERSION"
    chmod 444 "$tmp_release/VERSION"
    staged_hash=$(sha256sum "$tmp_release/keenwg-collector" | awk '{print $1}')
    [ "$staged_hash" = "$actual" ] || fail "staged collector checksum mismatch"
    mv -T "$tmp_release" "$RELEASE" || fail "could not publish immutable release"
    tmp_release=
fi

if ! "$RELEASE_BINARY" -check -config "$CONFIG"; then
    fail "collector self-check failed; current release was not changed"
fi

install_managed_file() {
    source_file=$1
    destination=$2
    mode=$3
    destination_dir=${destination%/*}
    destination_name=${destination##*/}
    temp_file=$(mktemp "$destination_dir/.$destination_name.XXXXXX") || return 1
    cp "$source_file" "$temp_file" || { rm -f "$temp_file"; return 1; }
    chmod "$mode" "$temp_file" || { rm -f "$temp_file"; return 1; }
    mv -fT "$temp_file" "$destination" || { rm -f "$temp_file"; return 1; }
}

snapshot_managed_file() {
    snapshot_destination=$1
    snapshot_key=$2
    snapshot_backup=$managed_snapshot_dir/$snapshot_key.file
    snapshot_absent=$managed_snapshot_dir/$snapshot_key.absent
    [ ! -e "$snapshot_backup" ] && [ ! -L "$snapshot_backup" ] || return 1
    [ ! -e "$snapshot_absent" ] && [ ! -L "$snapshot_absent" ] || return 1
    if [ -e "$snapshot_destination" ] || [ -L "$snapshot_destination" ]; then
        [ -f "$snapshot_destination" ] && [ ! -L "$snapshot_destination" ] || return 1
        cp "$snapshot_destination" "$snapshot_backup" || return 1
        chmod 600 "$snapshot_backup" || return 1
    else
        : >"$snapshot_absent" || return 1
        chmod 600 "$snapshot_absent" || return 1
    fi
}

restore_managed_file() {
    restore_destination=$1
    restore_key=$2
    restore_backup=$managed_snapshot_dir/$restore_key.file
    restore_absent=$managed_snapshot_dir/$restore_key.absent
    if [ -f "$restore_backup" ] && [ ! -L "$restore_backup" ] && [ ! -e "$restore_absent" ] && [ ! -L "$restore_absent" ]; then
        restore_dir=${restore_destination%/*}
        restore_name=${restore_destination##*/}
        restore_temp=$(mktemp "$restore_dir/.$restore_name.restore.XXXXXX") || return 1
        cp "$restore_backup" "$restore_temp" || { rm -f "$restore_temp"; return 1; }
        if [ "${KEENWG_TEST_RESTORE_MODE_FAIL_ONCE_KEY:-}" = "$restore_key" ] && [ -n "${KEENWG_TEST_RESTORE_MODE_STATE:-}" ] && [ ! -e "$KEENWG_TEST_RESTORE_MODE_STATE" ]; then
            : >"$KEENWG_TEST_RESTORE_MODE_STATE" || { rm -f "$restore_temp"; return 1; }
            rm -f "$restore_temp"
            return 1
        fi
        chmod 755 "$restore_temp" || { rm -f "$restore_temp"; return 1; }
        mv -fT "$restore_temp" "$restore_destination" || { rm -f "$restore_temp"; return 1; }
        [ -f "$restore_destination" ] && [ ! -L "$restore_destination" ] && [ -x "$restore_destination" ] || return 1
        restore_expected=$(sha256sum "$restore_backup" | awk '{print $1}') || return 1
        restore_actual=$(sha256sum "$restore_destination" | awk '{print $1}') || return 1
        [ "$restore_actual" = "$restore_expected" ] || return 1
    elif [ -f "$restore_absent" ] && [ ! -L "$restore_absent" ] && [ ! -e "$restore_backup" ] && [ ! -L "$restore_backup" ]; then
        rm -f "$restore_destination" || return 1
        [ ! -e "$restore_destination" ] && [ ! -L "$restore_destination" ] || return 1
    elif [ ! -e "$restore_backup" ] && [ ! -L "$restore_backup" ] && [ ! -e "$restore_absent" ] && [ ! -L "$restore_absent" ]; then
        return 0
    else
        return 1
    fi
}

snapshot_managed_files() {
    managed_snapshot_dir=$(mktemp -d "$LOCK_DIR/.keenwg-managed.XXXXXX") || return 1
    chmod 700 "$managed_snapshot_dir" || { rm -rf "$managed_snapshot_dir"; managed_snapshot_dir=; return 1; }
    managed_snapshot_active=true
    snapshot_managed_file "$INIT" init || return 1
    snapshot_managed_file "$ROOT/opt/etc/ndm/ifcreated.d/95-keenwg" ifcreated || return 1
    snapshot_managed_file "$ROOT/opt/etc/ndm/ifdestroyed.d/95-keenwg" ifdestroyed || return 1
    snapshot_managed_file "$ROOT/opt/etc/ndm/ifipchanged.d/95-keenwg" ifipchanged || return 1
}

restore_managed_files() {
    [ "$managed_snapshot_active" = true ] || return 0
    restore_failed=false
    restore_managed_file "$INIT" init || restore_failed=true
    restore_managed_file "$ROOT/opt/etc/ndm/ifcreated.d/95-keenwg" ifcreated || restore_failed=true
    restore_managed_file "$ROOT/opt/etc/ndm/ifdestroyed.d/95-keenwg" ifdestroyed || restore_failed=true
    restore_managed_file "$ROOT/opt/etc/ndm/ifipchanged.d/95-keenwg" ifipchanged || restore_failed=true
    [ "$restore_failed" = false ] || return 1
    rm -rf "$managed_snapshot_dir" || return 1
    managed_snapshot_dir=
    managed_snapshot_active=false
}

discard_managed_snapshots() {
    [ "$managed_snapshot_active" = true ] || return 0
    [ -d "$managed_snapshot_dir" ] && [ ! -L "$managed_snapshot_dir" ] || return 1
    case "$managed_snapshot_dir" in "$LOCK_DIR/.keenwg-managed."*) ;; *) return 1;; esac
    rm -rf "$managed_snapshot_dir" || return 1
    managed_snapshot_dir=
    managed_snapshot_active=false
}

snapshot_managed_files
install_managed_file "$HERE/S95keenwg" "$INIT" 755
for hook_dir in ifcreated.d ifdestroyed.d ifipchanged.d; do
    install_managed_file "$HERE/95-keenwg-signal" "$ROOT/opt/etc/ndm/$hook_dir/95-keenwg" 755
done

switch_current() {
    switch_target=$1
    case "$switch_target" in
        releases/*)
            switch_release_id=${switch_target#releases/}
            case "$switch_release_id" in ''|.|..|*/*|*[!A-Za-z0-9._-]*) return 1;; esac
            ;;
        *) return 1 ;;
    esac
    tmp_current_dir=$(mktemp -d "$ROOT/opt/keenwg/.current.XXXXXX") || return 1
    chmod 700 "$tmp_current_dir" || { rm -rf "$tmp_current_dir"; tmp_current_dir=; return 1; }
    tmp_current=$tmp_current_dir/link
    ln -s "$switch_target" "$tmp_current" || return 1
    [ -L "$tmp_current" ] || return 1
    mv -fT "$tmp_current" "$CURRENT" || return 1
    tmp_current=
    rmdir "$tmp_current_dir" 2>/dev/null || true
    tmp_current_dir=
}

new_target=releases/$RELEASE_ID

service_control() {
    action=$1
    if [ -n "${KEENWG_TEST_SERVICE:-}" ]; then
        "$KEENWG_TEST_SERVICE" "$action"
        return $?
    elif $LIVE; then
        "$INIT" "$action"
        return $?
    fi
    return 0
}

service_process_alive() {
    if [ -n "$PROCESS_ALIVE_CMD" ]; then
        "$PROCESS_ALIVE_CMD" "$1"
    else
        kill -0 "$1" 2>/dev/null
    fi
}

read_process_start_time() {
    stat_line=$(cat "$PROC_ROOT/$1/stat" 2>/dev/null || true)
    stat_tail=${stat_line##*) }
    [ -n "$stat_line" ] && [ "$stat_tail" != "$stat_line" ] || return 1
    set -- $stat_tail
    [ "$#" -ge 20 ] || return 1
    shift 19
    case "$1" in ''|*[!0-9]*) return 1;; esac
    process_start_time=$1
}

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
    process_scan_count=0
    for process_scan_dir in "$PROC_ROOT"/[0-9]*; do
        [ -d "$process_scan_dir" ] || continue
        process_scan_count=$((process_scan_count + 1))
        [ "$process_scan_count" -le "$PROC_SCAN_LIMIT" ] || return 2
        process_scan_exe=$(readlink "$process_scan_dir/exe" 2>/dev/null || true)
        [ -n "$process_scan_exe" ] || continue
        if is_collector_executable "$process_scan_exe"; then return 0; fi
    done
    return 1
}

verify_running_release() {
    expected_running_hash=$1
    $LIVE || return 0
    [ -f "$SERVICE_PID_FILE" ] && [ ! -L "$SERVICE_PID_FILE" ] || return 1
    running_pid=$(cat "$SERVICE_PID_FILE" 2>/dev/null || true)
    case "$running_pid" in ''|*[!0-9]*) return 1;; esac
    [ -f "$SERVICE_IDENTITY_FILE" ] && [ ! -L "$SERVICE_IDENTITY_FILE" ] || return 1
    tracked_extra=
    IFS=' ' read -r tracked_pid tracked_start tracked_extra <"$SERVICE_IDENTITY_FILE" || return 1
    [ -z "$tracked_extra" ] && [ "$tracked_pid" = "$running_pid" ] || return 1
    case "$tracked_pid:$tracked_start" in *[!0-9:]*|:*|*:) return 1;; esac
    service_process_alive "$running_pid" || return 1
    read_process_start_time "$running_pid" || return 1
    [ "$process_start_time" = "$tracked_start" ] || return 1
    running_hash=$(sha256sum "$PROC_ROOT/$running_pid/exe" 2>/dev/null | awk '{print $1}')
    [ "$running_hash" = "$expected_running_hash" ] || return 1

    collector_count=0
    tracked_seen=false
    scanned=0
    for proc_dir in "$PROC_ROOT"/[0-9]*; do
        [ -d "$proc_dir" ] || continue
        scanned=$((scanned + 1))
        [ "$scanned" -le "$PROC_SCAN_LIMIT" ] || return 1
        proc_pid=${proc_dir##*/}
        case "$proc_pid" in ''|*[!0-9]*) continue;; esac
        proc_exe=$(readlink "$proc_dir/exe" 2>/dev/null || true)
        [ -n "$proc_exe" ] || continue
        if is_collector_executable "$proc_exe"; then
            collector_count=$((collector_count + 1))
            if [ "$proc_pid" = "$running_pid" ]; then tracked_seen=true; fi
        fi
    done
    [ "$collector_count" = 1 ] && [ "$tracked_seen" = true ] || return 1

    service_process_alive "$running_pid" || return 1
    read_process_start_time "$running_pid" || return 1
    [ "$process_start_time" = "$tracked_start" ] || return 1
    final_hash=$(sha256sum "$PROC_ROOT/$running_pid/exe" 2>/dev/null | awk '{print $1}')
    [ "$final_hash" = "$expected_running_hash" ]
}

health_check() {
    expected_version=$1
    if [ -n "${KEENWG_TEST_HEALTH:-}" ]; then
        health_output=$("$KEENWG_TEST_HEALTH" "$expected_version") || return 1
    elif $LIVE; then
        sleep 1
        listen_address=$(sed -n 's/.*"listen_address"[ ]*:[ ]*"\([^"]*\)".*/\1/p' "$CONFIG")
        [ -n "$listen_address" ] || return 1
        health_output=$(wget -qO- "http://$listen_address/v1/health") || return 1
    else
        return 0
    fi
    compact_health=$(printf '%s' "$health_output" | tr -d '[:space:]')
    printf '%s' "$compact_health" | grep -Fq "\"version\":\"$expected_version\""
}

restore_previous() {
    if [ -n "$old_target" ]; then
        if [ ! -L "$CURRENT" ] || [ "$(readlink "$CURRENT")" != "$old_target" ]; then
            switch_current "$old_target" || return 1
        fi
        current_switched=false
        restore_managed_files || return 1
        service_control restart || return 1
        verify_running_release "$old_hash" || return 1
        health_check "$old_version" || return 1
    else
        service_control stop >/dev/null 2>&1 || true
        if collector_process_is_live; then
            return 1
        else
            rollback_scan_status=$?
        fi
        [ "$rollback_scan_status" = 1 ] || return 1
        [ -L "$CURRENT" ] && [ "$(readlink "$CURRENT")" = "$new_target" ] || return 1
        rm -f "$CURRENT" || return 1
        current_switched=false
        restore_managed_files || return 1
    fi
    rollback_required=false
    return 0
}

if [ -n "$old_target" ]; then
    [ -L "$CURRENT" ] && [ "$(readlink "$CURRENT")" = "$old_target" ] || fail "current changed during install"
else
    [ ! -e "$CURRENT" ] && [ ! -L "$CURRENT" ] || fail "current appeared during install"
fi
trap '' HUP INT TERM
if switch_current "$new_target"; then
    current_switched=true
    rollback_required=true
    switch_succeeded=true
else
    switch_succeeded=false
fi
trap 'exit 1' HUP INT TERM
if [ "$switch_succeeded" != true ]; then
    restore_managed_files || fail "current switch failed and lifecycle files could not be restored"
    fail "failed to atomically switch current release"
fi
if [ -n "${KEENWG_TEST_AFTER_SWITCH:-}" ]; then
    "$KEENWG_TEST_AFTER_SWITCH"
fi

if ! service_control restart; then
    restore_previous || fail "collector restart failed and previous release could not be restored"
    fail "collector restart failed; previous release restored"
fi
if ! verify_running_release "$actual"; then
    restore_previous || fail "collector process verification failed and previous release could not be restored"
    fail "collector process verification failed; previous release restored"
fi
if ! health_check "$VERSION"; then
    restore_previous || fail "collector health check failed and previous release could not be restored"
    fail "collector health check failed; previous release restored"
fi
if ! verify_running_release "$actual"; then
    restore_previous || fail "collector changed during health verification and previous release could not be restored"
    fail "collector changed during health verification; previous release restored"
fi

trap '' HUP INT TERM
if discard_managed_snapshots; then
    rollback_required=false
    current_switched=false
    commit_succeeded=true
else
    commit_succeeded=false
fi
trap 'exit 1' HUP INT TERM
[ "$commit_succeeded" = true ] || { restore_previous || fail "lifecycle commit failed and previous release could not be restored"; fail "lifecycle commit failed; previous release restored"; }

echo "KeenWG collector $VERSION installed as $RELEASE_ID"
