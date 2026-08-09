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
        [ ! -L "$current" ] || fail "refusing symlink install path: $current"
        IFS=/
    done
    IFS=$oldIFS
}

BINARY_SOURCE=$HERE/keenwg-xkeen-control
VERSION_FILE=$HERE/VERSION
SUMS=$HERE/SHA256SUMS
for source in "$BINARY_SOURCE" "$VERSION_FILE" "$SUMS" "$HERE/S96keenwg-xkeen-control" "$HERE/xkeen-country" "$HERE/config.example.json"; do
    [ -f "$source" ] && [ ! -L "$source" ] || fail "missing or unsafe bundle file: $source"
done
VERSION=$(sed -n '1p' "$VERSION_FILE")
case "$VERSION" in ''|*[!A-Za-z0-9._-]*) fail 'invalid VERSION';; esac
expected=$(awk '$2=="keenwg-xkeen-control" || $2=="*keenwg-xkeen-control" {print $1; exit}' "$SUMS")
actual=$(sha256sum "$BINARY_SOURCE" | awk '{print $1}')
[ -n "$expected" ] && [ "$actual" = "$expected" ] || fail 'binary checksum mismatch'
reported=$($BINARY_SOURCE -version 2>/dev/null | awk '$1=="keenwg-xkeen-control" {print $2; exit}')
[ "$reported" = "$VERSION" ] || fail 'binary version mismatch'

if $LIVE; then
    [ "$(uname -m)" = aarch64 ] || fail 'controller requires aarch64'
    [ "$(id -u)" = 0 ] || fail 'installer must run as root'
    command -v curl >/dev/null 2>&1 || fail 'curl is required for controller health verification'
fi

S05=$ROOT/opt/etc/init.d/S05xkeen
S99=$ROOT/opt/etc/init.d/S99xkeen
EXCLUDES=$ROOT/opt/etc/xkeen/ip_exclude.lst
OUTBOUNDS=$ROOT/opt/etc/xray/configs/04_outbounds.json
ROUTING=$ROOT/opt/etc/xray/configs/05_routing.json
COUNTRY=$ROOT/opt/sbin/xkeen-country
CONFIG_DIR=$ROOT/opt/etc/keenwg
CONFIG=$CONFIG_DIR/xkeen-control.json
STATE=$CONFIG_DIR/xkeen-state.json
DOMAIN_POLICY=$CONFIG_DIR/domain-policy.json
DOMAIN_POLICY_BACKUP=$CONFIG_DIR/domain-policy.json.bak
BACKUPS=$CONFIG_DIR/backups
LIB=$ROOT/opt/lib/keenwg-xkeen-control
RELEASES=$LIB/releases
RELEASE_ID=$VERSION-$actual
RELEASE=$RELEASES/$RELEASE_ID
CURRENT=$LIB/current
INIT=$ROOT/opt/etc/init.d/S96keenwg-xkeen-control

for target in "$S05" "$S99" "$EXCLUDES" "$OUTBOUNDS" "$ROUTING" "$COUNTRY" "$CONFIG_DIR" "$CONFIG" "$STATE" "$DOMAIN_POLICY" "$DOMAIN_POLICY_BACKUP" "$BACKUPS" "$LIB" "$RELEASES" "$RELEASE" "$INIT"; do assert_safe_path "$target"; done
[ -f "$S05" ] && [ ! -L "$S05" ] || { [ ! -e "$S99" ] || fail 'legacy S99xkeen found; upgrade to XKeen 2.0 first'; fail 'XKeen 2.0 S05xkeen not found'; }
grep -Eq '^#[[:space:]]*(Версия|Version):[[:space:]]*2\.[0-9]+' "$S05" || fail 'S05xkeen is not XKeen 2.x'
[ -f "$EXCLUDES" ] && [ ! -L "$EXCLUDES" ] || fail 'unsafe ip_exclude.lst'
[ -f "$OUTBOUNDS" ] && [ ! -L "$OUTBOUNDS" ] || fail 'unsafe outbounds file'
[ -f "$ROUTING" ] && [ ! -L "$ROUTING" ] || fail 'unsafe routing file'
[ -f "$COUNTRY" ] && [ ! -L "$COUNTRY" ] || fail 'legacy xkeen-country not found'

compact=$(tr -d '\r\n\t ' <"$OUTBOUNDS")
tag_count=$(printf '%s' "$compact" | awk -F'"tag":"vless-reality"' '{print NF-1}')
[ "$tag_count" = 1 ] || fail 'ambiguous vless-reality outbound'
addresses=$(printf '%s' "$compact" | sed 's/"address":"/\
"address":"/g' | sed -n 's/^"address":"\([0-9][0-9.]*\)".*/\1/p')
[ "$(printf '%s\n' "$addresses" | awk 'NF {n++} END {print n+0}')" = 1 ] || fail 'ambiguous active endpoint'
ACTIVE_IP=$addresses
printf '%s\n' "$ACTIVE_IP" | awk -F. 'NF==4 {for(i=1;i<=4;i++) if($i !~ /^[0-9]+$/ || $i>255) exit 1; ok=1} END {exit !ok}' || fail 'invalid active endpoint'

begin_count=$(grep -c '^# BEGIN KEENWG XKeen ENDPOINT$' "$EXCLUDES" || true)
end_count=$(grep -c '^# END KEENWG XKeen ENDPOINT$' "$EXCLUDES" || true)
[ "$(awk '/^[[:space:]]*ipv4_exclude[[:space:]]*=/{n++} END{print n+0}' "$S05")" = 1 ] || fail 'ambiguous ipv4_exclude assignment'
fresh_migration=false
if [ "$begin_count" = 0 ] && [ "$end_count" = 0 ]; then
    fresh_migration=true
elif [ "$begin_count" = 1 ] && [ "$end_count" = 1 ]; then
    managed_ip=$(awk '/^# BEGIN KEENWG XKeen ENDPOINT$/{getline; sub(/\/32$/,""); print; exit}' "$EXCLUDES")
    [ "$managed_ip" = "$ACTIVE_IP" ] || fail 'managed endpoint does not match active outbound'
    [ "$(grep -c "^$ACTIVE_IP/32$" "$EXCLUDES" || true)" = 1 ] || fail 'managed endpoint block is malformed'
    awk -v target="$ACTIVE_IP/32" '
        /^# BEGIN KEENWG XKeen ENDPOINT$/ {seen++; if((getline line)<=0 || line!=target) exit 20; if((getline line)<=0 || line!="# END KEENWG XKeen ENDPOINT") exit 21; next}
        /^# END KEENWG XKeen ENDPOINT$/ {exit 22}
        END {if(seen!=1) exit 23}
    ' "$EXCLUDES" || fail 'managed endpoint block is malformed'
    grep -q "$ACTIVE_IP/32" "$S05" && fail 'endpoint is owned by both S05 and the managed block'
else
    fail 'managed endpoint block already exists or is malformed'
fi

old_current=
current_existed=false
if [ -L "$CURRENT" ]; then
    old_current=$(readlink "$CURRENT")
    case "$old_current" in
        releases/*)
            old_release_id=${old_current#releases/}
            case "$old_release_id" in ''|*/*|*[!A-Za-z0-9._-]*) fail 'current points outside releases';; esac
            ;;
        *) fail 'current points outside releases';;
    esac
    [ -d "$LIB/$old_current" ] && [ ! -L "$LIB/$old_current" ] || fail 'current release is unavailable'
    current_existed=true
elif [ -e "$CURRENT" ]; then
    fail 'current is not a symlink'
fi

mkdir -p "$CONFIG_DIR" "$BACKUPS" "$RELEASES" "$ROOT/opt/etc/init.d" "$ROOT/opt/sbin" "$ROOT/opt/var/run"
chmod 700 "$CONFIG_DIR" "$BACKUPS" "$LIB" "$RELEASES"
BACKUP=$BACKUPS/install-$(date +%Y%m%d-%H%M%S)-$$
mkdir "$BACKUP"; chmod 700 "$BACKUP"
cp -p "$S05" "$BACKUP/S05xkeen"
cp -p "$EXCLUDES" "$BACKUP/ip_exclude.lst"
cp -p "$OUTBOUNDS" "$BACKUP/04_outbounds.json"
cp -p "$ROUTING" "$BACKUP/05_routing.json"
cp -p "$COUNTRY" "$BACKUP/xkeen-country.current"
previous_backup=$(ls -1dt "$BACKUPS"/install-* 2>/dev/null | grep -v "^$BACKUP$" | head -n 1 || true)
if [ -n "$previous_backup" ] && [ -f "$previous_backup/xkeen-country" ]; then
    cp -p "$previous_backup/xkeen-country" "$BACKUP/xkeen-country"
else
    cp -p "$COUNTRY" "$BACKUP/xkeen-country"
fi
config_existed=false; state_existed=false; init_existed=false; domain_policy_existed=false; domain_policy_backup_existed=false
service_was_running=false
if [ -e "$CONFIG" ]; then cp -p "$CONFIG" "$BACKUP/xkeen-control.json"; config_existed=true; fi
if [ -e "$STATE" ]; then cp -p "$STATE" "$BACKUP/xkeen-state.json"; state_existed=true; fi
if [ -e "$DOMAIN_POLICY" ]; then [ -f "$DOMAIN_POLICY" ] && [ ! -L "$DOMAIN_POLICY" ] || fail 'unsafe domain policy'; cp -p "$DOMAIN_POLICY" "$BACKUP/domain-policy.json"; domain_policy_existed=true; fi
if [ -e "$DOMAIN_POLICY_BACKUP" ]; then [ -f "$DOMAIN_POLICY_BACKUP" ] && [ ! -L "$DOMAIN_POLICY_BACKUP" ] || fail 'unsafe domain policy backup'; cp -p "$DOMAIN_POLICY_BACKUP" "$BACKUP/domain-policy.json.bak"; domain_policy_backup_existed=true; fi
if [ -e "$INIT" ]; then
    cp -p "$INIT" "$BACKUP/S96keenwg-xkeen-control"; init_existed=true
    if [ -x "$INIT" ] && KEENWG_DESTDIR="$ROOT" "$INIT" status >/dev/null 2>&1; then service_was_running=true; fi
fi
release_created=false
committed=false
service_stopped=false
current_switched=false
candidate_started=false

rollback() {
    [ "$committed" = false ] || return 0
    if $candidate_started && [ -x "$INIT" ]; then KEENWG_DESTDIR="$ROOT" "$INIT" stop >/dev/null 2>&1 || true; fi
    cp -p "$BACKUP/S05xkeen" "$S05" 2>/dev/null || true
    cp -p "$BACKUP/ip_exclude.lst" "$EXCLUDES" 2>/dev/null || true
    cp -p "$BACKUP/05_routing.json" "$ROUTING" 2>/dev/null || true
    cp -p "$BACKUP/xkeen-country.current" "$COUNTRY" 2>/dev/null || true
    if $config_existed; then cp -p "$BACKUP/xkeen-control.json" "$CONFIG"; else rm -f "$CONFIG"; fi
    if $state_existed; then cp -p "$BACKUP/xkeen-state.json" "$STATE"; else rm -f "$STATE"; fi
    if $domain_policy_existed; then cp -p "$BACKUP/domain-policy.json" "$DOMAIN_POLICY"; else rm -f "$DOMAIN_POLICY"; fi
    if $domain_policy_backup_existed; then cp -p "$BACKUP/domain-policy.json.bak" "$DOMAIN_POLICY_BACKUP"; else rm -f "$DOMAIN_POLICY_BACKUP"; fi
    if $init_existed; then cp -p "$BACKUP/S96keenwg-xkeen-control" "$INIT"; else rm -f "$INIT"; fi
    if $current_switched; then
        rm -f "$CURRENT"
        if $current_existed; then ln -s "$old_current" "$CURRENT"; fi
    fi
    if $release_created; then rm -rf "$RELEASE"; fi
    if $service_stopped && $service_was_running && [ -x "$INIT" ]; then KEENWG_DESTDIR="$ROOT" "$INIT" start >/dev/null 2>&1 || true; fi
    rm -rf "$BACKUP"
}
trap rollback EXIT
trap 'exit 1' HUP INT TERM

if $fresh_migration; then
    tmp_s05=$BACKUP/S05xkeen.candidate
    awk -v target="$ACTIVE_IP/32" '
/^[[:space:]]*ipv4_exclude[[:space:]]*=/ {
    count++; eq=index($0,"="); prefix=substr($0,1,eq); value=substr($0,eq+1)
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
    if (substr(value,1,1)!="\"" || substr(value,length(value),1)!="\"") exit 20
    body=substr(value,2,length(value)-2); n=split(body,item,/[[:space:]]+/); rebuilt=""
    for(i=1;i<=n;i++) if(item[i]!="") { if(item[i]==target){found++; continue}; rebuilt=rebuilt (rebuilt?" ":"") item[i] }
    print prefix "\"" rebuilt "\""; next
}
{print}
END {if(count!=1 || found!=1) exit 21}
' "$S05" >"$tmp_s05" || fail 'active endpoint is not uniquely owned by S05xkeen'
    s05_publish=$ROOT/opt/etc/init.d/.S05xkeen.keenwg.$$
    cp "$tmp_s05" "$s05_publish"; chmod 755 "$s05_publish"; mv -f "$s05_publish" "$S05"
    exclude_publish=$ROOT/opt/etc/xkeen/.ip_exclude.lst.keenwg.$$
    cp "$EXCLUDES" "$exclude_publish"
    printf '\n# BEGIN KEENWG XKeen ENDPOINT\n%s/32\n# END KEENWG XKeen ENDPOINT\n' "$ACTIVE_IP" >>"$exclude_publish"
    chmod 600 "$exclude_publish"; mv -f "$exclude_publish" "$EXCLUDES"
fi

if $config_existed; then
    chmod 600 "$CONFIG"
    TOKEN=$(sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([0-9a-f]*\)".*/\1/p' "$CONFIG")
    [ "${#TOKEN}" = 64 ] || fail 'existing control token is invalid'
    domain_path_count=$(grep -c '"domain_policy_path"' "$CONFIG" || true)
    domain_backup_count=$(grep -c '"domain_policy_backup_path"' "$CONFIG" || true)
    routing_path_count=$(grep -c '"routing_path"' "$CONFIG" || true)
    if [ "$domain_path_count" = 0 ] && [ "$domain_backup_count" = 0 ] && [ "$routing_path_count" = 0 ]; then
        config_publish=$CONFIG_DIR/.xkeen-control.json.$$
        awk '
            /"exclude_path"[[:space:]]*:/ {
                print
                print "  \"domain_policy_path\": \"/opt/etc/keenwg/domain-policy.json\"," 
                print "  \"domain_policy_backup_path\": \"/opt/etc/keenwg/domain-policy.json.bak\"," 
                print "  \"routing_path\": \"/opt/etc/xray/configs/05_routing.json\"," 
                next
            }
            {print}
        ' "$CONFIG" >"$config_publish"
        chmod 600 "$config_publish"; mv -f "$config_publish" "$CONFIG"
    elif [ "$domain_path_count" != 1 ] || [ "$domain_backup_count" != 1 ] || [ "$routing_path_count" != 1 ]; then
        fail 'existing domain routing config is incomplete'
    fi
else
    if $LIVE; then
        printf 'Private HTTPS subscription URL: ' >/dev/tty
        stty -echo </dev/tty
        IFS= read -r SUBSCRIPTION_URL </dev/tty || { stty echo </dev/tty; fail 'subscription URL input failed'; }
        stty echo </dev/tty
        printf '\n' >/dev/tty
    else
        SUBSCRIPTION_URL=${KEENWG_TEST_SUBSCRIPTION_URL:-}
    fi
    case "$SUBSCRIPTION_URL" in https://*) ;; *) fail 'subscription URL must use HTTPS';; esac
    [ -n "$SUBSCRIPTION_URL" ] && [ "${#SUBSCRIPTION_URL}" -le 2048 ] || fail 'invalid subscription URL'
    [ "$(printf '%s\n' "$SUBSCRIPTION_URL" | awk 'END {print NR}')" = 1 ] || fail 'subscription URL contains unsafe characters'
    case "$SUBSCRIPTION_URL" in *'"'*|*'\'*|*' '*|*"	"*) fail 'subscription URL contains unsafe characters';; esac
    TOKEN=$(head -c 64 /dev/urandom | sha256sum | awk '{print $1}')
    [ "${#TOKEN}" = 64 ] || fail 'control token generation failed'
    case "$TOKEN" in *[!0-9a-f]*) fail 'control token generation failed';; esac
    config_publish=$CONFIG_DIR/.xkeen-control.json.$$
    escaped_url=$(printf '%s' "$SUBSCRIPTION_URL" | sed 's/[&|]/\\&/g')
    sed -e "s|REPLACE_WITH_64_HEX_CONTROL_TOKEN|$TOKEN|" -e "s|REPLACE_WITH_PRIVATE_HTTPS_SUBSCRIPTION_URL|$escaped_url|" "$HERE/config.example.json" >"$config_publish"
    chmod 600 "$config_publish"; mv -f "$config_publish" "$CONFIG"
fi

if [ ! -e "$RELEASE" ]; then
    mkdir "$RELEASE"; chmod 700 "$RELEASE"; release_created=true
    cp "$BINARY_SOURCE" "$RELEASE/keenwg-xkeen-control"
    chmod 755 "$RELEASE/keenwg-xkeen-control"
    printf '%s\n' "$VERSION" >"$RELEASE/VERSION"; chmod 444 "$RELEASE/VERSION"
fi
[ "$(sha256sum "$RELEASE/keenwg-xkeen-control" | awk '{print $1}')" = "$actual" ] || fail 'staged release checksum mismatch'
KEENWG_DESTDIR="$ROOT" "$RELEASE/keenwg-xkeen-control" -config "$CONFIG" -check || fail 'controller self-check failed'
if ! $state_existed; then
    KEENWG_DESTDIR="$ROOT" "$RELEASE/keenwg-xkeen-control" -config "$CONFIG" -bootstrap-active || fail 'active bootstrap failed'
fi

if $service_was_running; then
    KEENWG_DESTDIR="$ROOT" "$INIT" stop || fail 'existing controller service failed to stop'
    service_stopped=true
fi
init_publish=$ROOT/opt/etc/init.d/.S96keenwg-xkeen-control.$$
cp "$HERE/S96keenwg-xkeen-control" "$init_publish"; chmod 755 "$init_publish"; mv -f "$init_publish" "$INIT"
country_publish=$ROOT/opt/sbin/.xkeen-country.$$
cp "$HERE/xkeen-country" "$country_publish"; chmod 755 "$country_publish"; mv -f "$country_publish" "$COUNTRY"
tmp_link=$LIB/.current.$$
ln -s "releases/$RELEASE_ID" "$tmp_link"
mv -fT "$tmp_link" "$CURRENT"
current_switched=true
candidate_started=true
KEENWG_DESTDIR="$ROOT" "$INIT" start || fail 'controller service failed to start'
KEENWG_DESTDIR="$ROOT" "$INIT" status || fail 'controller service is not running'
if $LIVE; then
    health_ok=false; count=0
    while [ "$count" -lt 20 ]; do
        if curl -fsS --max-time 5 -H "Authorization: Bearer $TOKEN" -o /dev/null "http://10.8.0.1:18778/v1/xkeen/status"; then health_ok=true; break; fi
        count=$((count+1)); sleep 1
    done
    $health_ok || fail 'authenticated controller status failed'
    if ! $config_existed; then printf 'KeenWG XKeen control token (save it in the app): %s\n' "$TOKEN" >/dev/tty; fi
fi
committed=true
trap - EXIT HUP INT TERM
echo "Installed KeenWG XKeen controller $VERSION"
