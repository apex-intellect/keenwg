#!/bin/sh
set -eu
umask 077

ROOT=${KEENWG_DESTDIR:-}
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
fail() { echo "$*" >&2; exit 1; }
case "$ROOT" in ''|/*) ;; *) fail 'KEENWG_DESTDIR must be empty or absolute';; esac
[ "$ROOT" != / ] || fail 'KEENWG_DESTDIR=/ is forbidden'
case "$ROOT" in *'/../'*|*/..|*'/./'*|*/.) fail 'KEENWG_DESTDIR must be canonical';; esac
if [ -n "$ROOT" ]; then
    [ -d "$ROOT" ] || fail 'KEENWG_DESTDIR must already exist'
    [ "$(CDPATH= cd -- "$ROOT" && pwd -P)" = "$ROOT" ] || fail 'KEENWG_DESTDIR must be canonical'
fi

INIT=$ROOT/opt/etc/init.d/S96keenwg-companion
LIB=$ROOT/opt/lib/keenwg-companion
case "$INIT" in "$ROOT/opt/etc/init.d/S96keenwg-companion") ;; *) fail 'unsafe init target';; esac
case "$LIB" in "$ROOT/opt/lib/keenwg-companion") ;; *) fail 'unsafe library target';; esac
[ ! -L "$INIT" ] || fail 'refusing symlink init target'
[ ! -L "$LIB" ] || fail 'refusing symlink library target'

# This bundle's controller also handles init versions which predate watchdogs.
# A failed cron read aborts removal rather than erasing unrelated router jobs.
CONTROL=$HERE/S96keenwg-companion
[ -f "$CONTROL" ] && [ ! -L "$CONTROL" ] || fail 'safe companion service control unavailable'
KEENWG_DESTDIR="$ROOT" sh "$CONTROL" uninstall || fail 'could not stop companion or safely remove its watchdog'
rm -f "$INIT"
if [ -d "$LIB" ]; then rm -rf "$LIB"; fi
echo 'keenwg-companion runtime removed; configuration, identity, pairing data, and user state were preserved'
