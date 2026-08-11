#!/bin/sh
set -eu
umask 077

ROOT=${KEENWG_DESTDIR:-}
fail() { echo "$*" >&2; exit 1; }

case "$ROOT" in ''|/*) ;; *) fail 'KEENWG_DESTDIR must be empty or absolute';; esac
[ "$ROOT" != / ] || fail 'KEENWG_DESTDIR=/ is forbidden'
case "$ROOT" in *'/../'*|*/..|*'/./'*|*/.) fail 'KEENWG_DESTDIR must be canonical';; esac
if [ -n "$ROOT" ]; then
    [ -d "$ROOT" ] || fail 'KEENWG_DESTDIR must already exist'
    [ "$(CDPATH= cd -- "$ROOT" && pwd -P)" = "$ROOT" ] || fail 'KEENWG_DESTDIR must be canonical'
fi

COMPANION=$ROOT/opt/lib/keenwg-companion/current/keenwg-companion
COMPANION_INIT=$ROOT/opt/etc/init.d/S96keenwg-companion
COMPANION_CONFIG=$ROOT/opt/etc/keenwg/companion.json
OLD_INIT=$ROOT/opt/etc/init.d/S96keenwg-xkeen-control
OLD_LIB=$ROOT/opt/lib/keenwg-xkeen-control
OLD_CONFIG=$ROOT/opt/etc/keenwg/xkeen-control.json
OLD_HELPER=$ROOT/opt/sbin/xkeen-country
OLD_PID=$ROOT/opt/var/run/keenwg-xkeen-control.pid

for target in "$COMPANION" "$COMPANION_INIT" "$COMPANION_CONFIG" "$OLD_INIT" "$OLD_LIB" "$OLD_CONFIG" "$OLD_HELPER" "$OLD_PID"; do
    case "$target" in "$ROOT/opt/"*) ;; *) fail 'unsafe cleanup target';; esac
done

[ -x "$COMPANION" ] && [ ! -L "$COMPANION_CONFIG" ] || fail 'healthy Companion is required before cleanup'
KEENWG_DESTDIR="$ROOT" "$COMPANION" -config "$COMPANION_CONFIG" -check >/dev/null 2>&1 || fail 'Companion self-check failed'
[ -x "$COMPANION_INIT" ] && [ ! -L "$COMPANION_INIT" ] || fail 'Companion init unavailable'
KEENWG_DESTDIR="$ROOT" "$COMPANION_INIT" health >/dev/null 2>&1 || fail 'Companion health check failed'

for target in "$OLD_INIT" "$OLD_CONFIG" "$OLD_HELPER" "$OLD_PID"; do
    [ ! -L "$target" ] || fail "refusing obsolete symlink: $target"
    if [ -e "$target" ]; then [ -f "$target" ] || fail "unexpected obsolete path type: $target"; fi
done
if [ -e "$OLD_LIB" ] || [ -L "$OLD_LIB" ]; then
    [ -d "$OLD_LIB" ] && [ ! -L "$OLD_LIB" ] || fail 'unexpected obsolete library path type'
fi

if [ -x "$OLD_INIT" ] && KEENWG_DESTDIR="$ROOT" "$OLD_INIT" status >/dev/null 2>&1; then
    KEENWG_DESTDIR="$ROOT" "$OLD_INIT" stop >/dev/null 2>&1 || fail 'obsolete controller did not stop'
fi

rm -f "$OLD_INIT" "$OLD_CONFIG" "$OLD_HELPER" "$OLD_PID"
if [ -d "$OLD_LIB" ]; then
    # Public 1.x release trees legitimately contain current/rollback symlinks.
    # OLD_LIB itself is a fixed allowlisted real directory; rm unlinks child
    # symlinks without following their targets.
    rm -rf "$OLD_LIB"
fi
echo 'obsolete standalone XKeen controller removed'
