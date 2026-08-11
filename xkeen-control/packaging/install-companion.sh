#!/bin/sh
set -eu
umask 077

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=${KEENWG_DESTDIR:-}
LIVE=true
[ -n "$ROOT" ] && LIVE=false

fail() { echo "$*" >&2; exit 1; }

case "$ROOT" in ''|/*) ;; *) fail 'KEENWG_DESTDIR must be empty or absolute';; esac
[ "$ROOT" != / ] || fail 'KEENWG_DESTDIR=/ is forbidden'
case "$ROOT" in *'/../'*|*/..|*'/./'*|*/.) fail 'KEENWG_DESTDIR must be canonical';; esac
if [ -n "$ROOT" ]; then
    [ -d "$ROOT" ] || fail 'KEENWG_DESTDIR must already exist'
    [ "$(CDPATH= cd -- "$ROOT" && pwd -P)" = "$ROOT" ] || fail 'KEENWG_DESTDIR must be canonical'
fi

[ "${1:-}" = '--request' ] && [ "$#" = 2 ] || fail 'usage: install-companion.sh --request /opt/tmp/keenwg-<nonce>.json'
REQUEST_VIRTUAL=$2
case "$REQUEST_VIRTUAL" in /opt/tmp/keenwg-*.json) ;; *) fail 'invalid bootstrap request path';; esac
nonce=${REQUEST_VIRTUAL#/opt/tmp/keenwg-}; nonce=${nonce%.json}
[ "${#nonce}" = 32 ] || fail 'invalid bootstrap request nonce'
case "$nonce" in *[!0-9a-f]*) fail 'invalid bootstrap request nonce';; esac
REQUEST=$ROOT$REQUEST_VIRTUAL
[ -f "$REQUEST" ] && [ ! -L "$REQUEST" ] || fail 'bootstrap request unavailable'

assert_safe_path() {
    target=$1
    case "$target" in "$ROOT/opt"|"$ROOT/opt/"*) ;; *) fail "unsafe install path: $target";; esac
    relative=${target#"$ROOT"/}
    current=$ROOT
    oldIFS=$IFS; IFS=/
    for component in $relative; do
        IFS=$oldIFS
        case "$component" in ''|.|..) fail 'unsafe install path component';; esac
        current=$current/$component
        [ ! -L "$current" ] || case "$current" in "$ROOT/opt/lib/keenwg-companion/current") ;; *) fail "refusing symlink install path: $current";; esac
        IFS=/
    done
    IFS=$oldIFS
}

BINARY_SOURCE=$HERE/keenwg-companion
VERSION_FILE=$HERE/VERSION
SUMS=$HERE/SHA256SUMS
NEW_INIT_SOURCE=$HERE/S96keenwg-companion
for source in "$BINARY_SOURCE" "$VERSION_FILE" "$SUMS" "$NEW_INIT_SOURCE" "$HERE/uninstall-companion.sh" "$HERE/cleanup-obsolete-controller.sh" "$HERE/companion.config.example.json"; do
    [ -f "$source" ] && [ ! -L "$source" ] || fail "missing or unsafe bundle file: $source"
done
VERSION=$(sed -n '1p' "$VERSION_FILE")
case "$VERSION" in ''|*[!A-Za-z0-9._-]*) fail 'invalid VERSION';; esac
expected=$(awk '$2=="keenwg-companion" || $2=="*keenwg-companion" {print $1; exit}' "$SUMS")
actual=$(sha256sum "$BINARY_SOURCE" | awk '{print $1}')
[ -n "$expected" ] && [ "$actual" = "$expected" ] || fail 'binary checksum mismatch'
reported=$($BINARY_SOURCE -version 2>/dev/null | awk '$1=="keenwg-companion" {print $2; exit}')
[ "$reported" = "$VERSION" ] || fail 'binary version mismatch'

if $LIVE; then
    [ "$(uname -m)" = aarch64 ] || fail 'companion requires aarch64'
    [ "$(id -u)" = 0 ] || fail 'installer must run as root'
    command -v curl >/dev/null 2>&1 || fail 'curl is required for HTTPS health verification'
fi

CONFIG_DIR=$ROOT/opt/etc/keenwg
CONFIG=$CONFIG_DIR/companion.json
IDENTITY_DIR=$CONFIG_DIR/identity
CERT=$IDENTITY_DIR/certificate.pem
KEY=$IDENTITY_DIR/private-key.pem
DEVICES=$CONFIG_DIR/devices.json
OFFERS=$CONFIG_DIR/pairing-offers.json
BACKUPS=$CONFIG_DIR/backups
INIT=$ROOT/opt/etc/init.d/S96keenwg-companion
LIB=$ROOT/opt/lib/keenwg-companion
RELEASES=$LIB/releases
RELEASE_ID=$VERSION-$actual
RELEASE=$RELEASES/$RELEASE_ID
CURRENT=$LIB/current

for target in "$CONFIG_DIR" "$CONFIG" "$IDENTITY_DIR" "$CERT" "$KEY" "$DEVICES" "$OFFERS" "$BACKUPS" "$INIT" "$LIB" "$RELEASES" "$RELEASE" "$CURRENT"; do assert_safe_path "$target"; done

old_current=
current_existed=false
if [ -L "$CURRENT" ]; then
    old_current=$(readlink "$CURRENT")
    case "$old_current" in releases/*) ;; *) fail 'current points outside releases';; esac
    [ -d "$LIB/$old_current" ] && [ ! -L "$LIB/$old_current" ] || fail 'current release unavailable'
    current_existed=true
elif [ -e "$CURRENT" ]; then
    fail 'current is not a symlink'
fi

mkdir -p "$CONFIG_DIR" "$BACKUPS" "$RELEASES" "$ROOT/opt/etc/init.d" "$ROOT/opt/var/run"
chmod 700 "$CONFIG_DIR" "$BACKUPS" "$LIB" "$RELEASES"
BACKUP=$BACKUPS/companion-install-$(date +%Y%m%d-%H%M%S)-$$
mkdir "$BACKUP"; chmod 700 "$BACKUP"

config_existed=false; init_existed=false; cert_existed=false; key_existed=false; devices_existed=false; offers_existed=false
for item in config:$CONFIG init:$INIT cert:$CERT key:$KEY devices:$DEVICES offers:$OFFERS; do
    name=${item%%:*}; path=${item#*:}
    if [ -e "$path" ]; then
        [ -f "$path" ] && [ ! -L "$path" ] || fail "unsafe existing $name"
        cp -p "$path" "$BACKUP/$name"
        eval "${name}_existed=true"
    fi
done
companion_was_running=false
if [ -x "$INIT" ] && KEENWG_DESTDIR="$ROOT" "$INIT" status >/dev/null 2>&1; then companion_was_running=true; fi

release_created=false; current_switched=false; companion_stopped=false; candidate_started=false; committed=false
rollback() {
    [ "$committed" = false ] || return 0
    if $candidate_started && [ -x "$INIT" ]; then KEENWG_DESTDIR="$ROOT" "$INIT" stop >/dev/null 2>&1 || true; fi
    if $init_existed; then cp -p "$BACKUP/init" "$INIT"; else rm -f "$INIT"; fi
    for item in config:$CONFIG cert:$CERT key:$KEY devices:$DEVICES offers:$OFFERS; do
        name=${item%%:*}; path=${item#*:}; eval "existed=\$${name}_existed"
        if $existed; then cp -p "$BACKUP/$name" "$path"; else rm -f "$path"; fi
    done
    if $current_switched; then
        rm -f "$CURRENT"
        if $current_existed; then ln -s "$old_current" "$CURRENT"; fi
    fi
    if $release_created; then rm -rf "$RELEASE"; fi
    if $companion_stopped && $companion_was_running && [ -x "$INIT" ]; then KEENWG_DESTDIR="$ROOT" "$INIT" start >/dev/null 2>&1 || true; fi
    rm -rf "$BACKUP"
}
trap rollback EXIT
trap 'exit 1' HUP INT TERM

if $companion_was_running; then
    KEENWG_DESTDIR="$ROOT" "$INIT" stop || fail 'could not stop previous companion'
    companion_stopped=true
fi
if [ ! -e "$RELEASE" ]; then
    mkdir "$RELEASE"; chmod 700 "$RELEASE"; release_created=true
    cp "$BINARY_SOURCE" "$RELEASE/keenwg-companion"
    chmod 755 "$RELEASE/keenwg-companion"
    printf '%s\n' "$VERSION" >"$RELEASE/VERSION"; chmod 444 "$RELEASE/VERSION"
fi
[ "$(sha256sum "$RELEASE/keenwg-companion" | awk '{print $1}')" = "$actual" ] || fail 'staged release checksum mismatch'
ln -sfn "releases/$RELEASE_ID" "$CURRENT"
[ "$(readlink "$CURRENT")" = "releases/$RELEASE_ID" ] || fail 'current release switch failed'
current_switched=true

init_tmp=$ROOT/opt/etc/init.d/.S96keenwg-companion.$$
cp "$NEW_INIT_SOURCE" "$init_tmp"; chmod 755 "$init_tmp"; mv -f "$init_tmp" "$INIT"
if ! $config_existed; then
    KEENWG_DESTDIR="$ROOT" "$CURRENT/keenwg-companion" -config "$CONFIG" -bootstrap-request "$REQUEST" || fail 'companion bootstrap failed'
else
    schema=$(sed -n 's/^[[:space:]]*"schema_version"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$CONFIG" | sed -n '1p')
    case "$schema" in
        # Public 1.0 configs predate schema_version. The binary upgrader
        # strictly decodes that bounded legacy shape and rejects unknown
        # fields, so an absent marker is safe to delegate but never assume.
        ''|1) KEENWG_DESTDIR="$ROOT" "$CURRENT/keenwg-companion" -config "$CONFIG" -upgrade-config || fail 'companion config upgrade failed';;
        2) ;;
        *) fail 'unsupported companion config schema';;
    esac
fi
KEENWG_DESTDIR="$ROOT" "$CURRENT/keenwg-companion" -config "$CONFIG" -check || fail 'companion self-check failed'
KEENWG_DESTDIR="$ROOT" "$INIT" start || fail 'companion start failed'
candidate_started=true
attempt=0
while [ "$attempt" -lt 10 ]; do
    if KEENWG_DESTDIR="$ROOT" "$INIT" health >/dev/null 2>&1; then break; fi
    attempt=$((attempt+1))
    [ -n "$ROOT" ] || sleep 1
done
[ "$attempt" -lt 10 ] || fail 'companion HTTPS health failed'
KEENWG_DESTDIR="$ROOT" "$HERE/cleanup-obsolete-controller.sh" || fail 'obsolete controller cleanup failed'
committed=true
trap - EXIT HUP INT TERM
echo "keenwg-companion $VERSION installed"
