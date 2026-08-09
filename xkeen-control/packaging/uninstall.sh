#!/bin/sh
set -eu
umask 077

ROOT=${KEENWG_DESTDIR:-}
PURGE=false
case "${1:-}" in '' ) ;; --purge) PURGE=true;; *) echo 'usage: uninstall.sh [--purge]' >&2; exit 2;; esac
case "$ROOT" in ''|/*) ;; *) echo 'KEENWG_DESTDIR must be empty or absolute' >&2; exit 2;; esac
[ "$ROOT" != / ] || { echo 'KEENWG_DESTDIR=/ is forbidden' >&2; exit 2; }
case "$ROOT" in *'/../'*|*/..|*'/./'*|*/.) echo 'KEENWG_DESTDIR must be canonical' >&2; exit 2;; esac
if [ -n "$ROOT" ]; then [ -d "$ROOT" ] && [ "$(CDPATH= cd -- "$ROOT" && pwd -P)" = "$ROOT" ] || exit 2; fi

S05=$ROOT/opt/etc/init.d/S05xkeen
EXCLUDES=$ROOT/opt/etc/xkeen/ip_exclude.lst
ROUTING=$ROOT/opt/etc/xray/configs/05_routing.json
COUNTRY=$ROOT/opt/sbin/xkeen-country
INIT=$ROOT/opt/etc/init.d/S96keenwg-xkeen-control
PIDFILE=$ROOT/opt/var/run/keenwg-xkeen-control.pid
LIB=$ROOT/opt/lib/keenwg-xkeen-control
CONFIG_DIR=$ROOT/opt/etc/keenwg
BACKUPS=$CONFIG_DIR/backups
DOMAIN_POLICY=$CONFIG_DIR/domain-policy.json
DOMAIN_POLICY_BACKUP=$CONFIG_DIR/domain-policy.json.bak

for target in "$S05" "$EXCLUDES" "$ROUTING" "$COUNTRY" "$INIT" "$PIDFILE" "$LIB" "$CONFIG_DIR" "$BACKUPS" "$DOMAIN_POLICY" "$DOMAIN_POLICY_BACKUP"; do
    case "$target" in "$ROOT/opt"|"$ROOT/opt/"*) ;; *) exit 2;; esac
    relative=${target#"$ROOT"/}; current=$ROOT; oldIFS=$IFS; IFS=/
    for component in $relative; do IFS=$oldIFS; current=$current/$component; [ ! -L "$current" ] || { [ "$current" = "$LIB/current" ] || exit 2; }; IFS=/; done
    IFS=$oldIFS
done
[ -f "$S05" ] && [ -f "$EXCLUDES" ] && [ -f "$ROUTING" ] || { echo 'XKeen files missing' >&2; exit 1; }
[ "$(grep -c '^# BEGIN KEENWG XKeen ENDPOINT$' "$EXCLUDES" || true)" = 1 ] || { echo 'managed block missing' >&2; exit 1; }
[ "$(grep -c '^# END KEENWG XKeen ENDPOINT$' "$EXCLUDES" || true)" = 1 ] || { echo 'managed block malformed' >&2; exit 1; }
ACTIVE_IP=$(awk '/^# BEGIN KEENWG XKeen ENDPOINT$/{getline; sub(/\/32$/,""); print; exit}' "$EXCLUDES")
printf '%s\n' "$ACTIVE_IP" | awk -F. 'NF==4 {for(i=1;i<=4;i++) if($i !~ /^[0-9]+$/ || $i>255) exit 1; ok=1} END{exit !ok}' || exit 1
awk -v target="$ACTIVE_IP/32" '
    /^# BEGIN KEENWG XKeen ENDPOINT$/ {seen++; if((getline line)<=0 || line!=target) exit 20; if((getline line)<=0 || line!="# END KEENWG XKeen ENDPOINT") exit 21; next}
    /^# END KEENWG XKeen ENDPOINT$/ {exit 22}
    END {if(seen!=1) exit 23}
' "$EXCLUDES" || { echo 'managed block malformed' >&2; exit 1; }

if [ -x "$INIT" ]; then KEENWG_DESTDIR="$ROOT" "$INIT" stop || { echo 'controller did not stop' >&2; exit 1; }; fi
[ ! -e "$PIDFILE" ] || { echo 'controller PID remains' >&2; exit 1; }

SNAPSHOT=$(mktemp -d "$ROOT/opt/etc/.keenwg-uninstall.XXXXXX")
trap 'cp -p "$SNAPSHOT/S05xkeen" "$S05" 2>/dev/null || true; cp -p "$SNAPSHOT/ip_exclude.lst" "$EXCLUDES" 2>/dev/null || true; cp -p "$SNAPSHOT/05_routing.json" "$ROUTING" 2>/dev/null || true; if [ -f "$SNAPSHOT/domain-policy.json" ]; then cp -p "$SNAPSHOT/domain-policy.json" "$DOMAIN_POLICY" 2>/dev/null || true; else rm -f "$DOMAIN_POLICY"; fi; if [ -f "$SNAPSHOT/domain-policy.json.bak" ]; then cp -p "$SNAPSHOT/domain-policy.json.bak" "$DOMAIN_POLICY_BACKUP" 2>/dev/null || true; else rm -f "$DOMAIN_POLICY_BACKUP"; fi; if [ -f "$SNAPSHOT/xkeen-country" ]; then cp -p "$SNAPSHOT/xkeen-country" "$COUNTRY" 2>/dev/null || true; fi; rm -rf "$SNAPSHOT"' EXIT
trap 'exit 1' HUP INT TERM
cp -p "$S05" "$SNAPSHOT/S05xkeen"
cp -p "$EXCLUDES" "$SNAPSHOT/ip_exclude.lst"
cp -p "$ROUTING" "$SNAPSHOT/05_routing.json"
if [ -f "$DOMAIN_POLICY" ] && [ ! -L "$DOMAIN_POLICY" ]; then cp -p "$DOMAIN_POLICY" "$SNAPSHOT/domain-policy.json"; fi
if [ -f "$DOMAIN_POLICY_BACKUP" ] && [ ! -L "$DOMAIN_POLICY_BACKUP" ]; then cp -p "$DOMAIN_POLICY_BACKUP" "$SNAPSHOT/domain-policy.json.bak"; fi
if [ -f "$COUNTRY" ] && [ ! -L "$COUNTRY" ]; then cp -p "$COUNTRY" "$SNAPSHOT/xkeen-country"; fi

tmp_s05=$SNAPSHOT/S05xkeen.candidate
awk -v target="$ACTIVE_IP/32" '
/^[[:space:]]*ipv4_exclude[[:space:]]*=/ {
    count++; eq=index($0,"="); prefix=substr($0,1,eq); value=substr($0,eq+1); gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
    if(substr(value,1,1)!="\"" || substr(value,length(value),1)!="\"") exit 20
    body=substr(value,2,length(value)-2); n=split(body,item,/[[:space:]]+/); found=0
    for(i=1;i<=n;i++) if(item[i]==target) found++
    if(found>0) exit 21
    body=body (body?" ":"") target; print prefix "\"" body "\""; next
}
{print}
END {if(count!=1) exit 22}
' "$S05" >"$tmp_s05" || { echo 'cannot restore endpoint ownership to S05xkeen' >&2; exit 1; }
s05_publish=$ROOT/opt/etc/init.d/.S05xkeen.uninstall.$$
cp "$tmp_s05" "$s05_publish"; chmod 755 "$s05_publish"; mv -f "$s05_publish" "$S05"
awk '
BEGIN {inside=0; seen=0}
/^# BEGIN KEENWG XKeen ENDPOINT$/ {if(inside||seen) exit 20; inside=1; seen=1; next}
/^# END KEENWG XKeen ENDPOINT$/ {if(!inside) exit 21; inside=0; next}
!inside {print}
END {if(inside||!seen) exit 22}
' "$EXCLUDES" >"$SNAPSHOT/ip_exclude.candidate" || exit 1
exclude_publish=$ROOT/opt/etc/xkeen/.ip_exclude.lst.uninstall.$$
cp "$SNAPSHOT/ip_exclude.candidate" "$exclude_publish"; chmod 600 "$exclude_publish"; mv -f "$exclude_publish" "$EXCLUDES"

latest=$(ls -1dt "$BACKUPS"/install-* 2>/dev/null | head -n 1 || true)
[ -n "$latest" ] && [ -f "$latest/xkeen-country" ] || { echo 'legacy xkeen-country backup missing' >&2; exit 1; }
[ -f "$latest/05_routing.json" ] || { echo 'original domain routing backup missing' >&2; exit 1; }
cp -p "$latest/xkeen-country" "$COUNTRY"
cp -p "$latest/05_routing.json" "$ROUTING"
rm -f "$DOMAIN_POLICY" "$DOMAIN_POLICY_BACKUP"
rm -f "$INIT"
rm -rf "$LIB"
if $PURGE; then rm -rf "$CONFIG_DIR"; fi
rm -rf "$SNAPSHOT"
trap - EXIT HUP INT TERM
echo 'KeenWG XKeen controller removed; last confirmed outbound remains active.'
