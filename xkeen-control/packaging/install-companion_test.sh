#!/bin/sh
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TMP=$(mktemp -d)
PROCESSES=""
cleanup() {
    for pid in $PROCESSES; do kill "$pid" 2>/dev/null || true; done
    rm -rf "$TMP"
}
trap cleanup EXIT HUP INT TERM

fail() { echo "$*" >&2; exit 1; }
assert_file() { [ -f "$1" ] || fail "missing file: $1"; }

make_bundle() {
    bundle=$1
    mkdir -p "$bundle"
    cp "$HERE/install-companion.sh" "$HERE/uninstall-companion.sh" "$HERE/S96keenwg-companion" "$HERE/companion.config.example.json" "$bundle/"
    chmod 755 "$bundle/install-companion.sh" "$bundle/uninstall-companion.sh" "$bundle/S96keenwg-companion"
    printf '%s\n' '0.7.0' >"$bundle/VERSION"
    cat >"$bundle/keenwg-companion" <<'EOF'
#!/bin/sh
set -eu
ROOT=${KEENWG_DESTDIR:-}
CONFIG=/opt/etc/keenwg/companion.json
map_path() { case "$1" in /opt/*) printf '%s%s\n' "$ROOT" "$1";; *) printf '%s\n' "$1";; esac; }
for arg in "$@"; do [ "$arg" = '-version' ] && { echo 'keenwg-companion 0.7.0 (test)'; exit 0; }; done
bootstrap=false; check=false
legacy= request=
while [ "$#" -gt 0 ]; do
    case "$1" in
        -config) CONFIG=$2; shift 2;;
        -bootstrap-from) bootstrap=true; legacy=$2; shift 2;;
        -bootstrap-request) request=$2; shift 2;;
        -check) check=true; shift;;
        *) shift;;
    esac
done
if $bootstrap; then
    [ -f "$legacy" ] && [ -f "$request" ] || exit 2
    mkdir -p "$(dirname "$CONFIG")" "$ROOT/opt/etc/keenwg/identity"
    sed '$d' "$legacy" >"$CONFIG"
    cat >>"$CONFIG" <<'JSON'
,
  "secure_listen_address":"10.8.0.1:18779",
  "legacy_api_enabled":true
}
JSON
    printf '%s\n' 'fake certificate' >"$ROOT/opt/etc/keenwg/identity/certificate.pem"
    printf '%s\n' 'fake key' >"$ROOT/opt/etc/keenwg/identity/private-key.pem"
    chmod 600 "$CONFIG" "$ROOT/opt/etc/keenwg/identity/certificate.pem" "$ROOT/opt/etc/keenwg/identity/private-key.pem"
    exit 0
fi
if $check; then [ -f "$CONFIG" ] && exit 0; fi
READY="$ROOT/opt/var/run/keenwg-companion.ready"
mkdir -p "$(dirname "$READY")"
[ ! -f "$ROOT/opt/var/run/legacy-controller.running" ] || exit 9
[ -f "$ROOT/opt/etc/keenwg/fail-health" ] || : >"$READY"
trap 'rm -f "$READY"; exit 0' TERM INT
while :; do sleep 1; done
EOF
    chmod 755 "$bundle/keenwg-companion"
    (cd "$bundle" && sha256sum keenwg-companion >SHA256SUMS)
}

make_root() {
    root=$1
    mkdir -p "$root/opt/etc/keenwg" "$root/opt/etc/init.d" "$root/opt/etc/xkeen" "$root/opt/tmp" "$root/opt/var/run"
    cat >"$root/opt/etc/keenwg/xkeen-control.json" <<'JSON'
{
  "listen_address":"10.8.0.1:18778",
  "token":"0123456789abcdef0123456789abcdef",
  "subscription_url":"https://vpn.example.test/sub/private",
  "subscription_cache_path":"/opt/etc/keenwg/xkeen-subscription.json",
  "state_path":"/opt/etc/keenwg/xkeen-state.json",
  "backup_dir":"/opt/etc/keenwg/backups",
  "outbounds_path":"/opt/etc/xray/configs/04_outbounds.json",
  "exclude_path":"/opt/etc/xkeen/ip_exclude.lst",
  "domain_policy_path":"/opt/etc/keenwg/domain-policy.json",
  "domain_policy_backup_path":"/opt/etc/keenwg/domain-policy.json.bak",
  "routing_path":"/opt/etc/xray/configs/05_routing.json",
  "init_script":"/opt/etc/init.d/S05xkeen",
  "xray_binary":"/opt/sbin/xray",
  "asset_dir":"/opt/etc/xray/dat",
  "max_subscription_bytes":262144,
  "max_nodes":128
}
JSON
    chmod 600 "$root/opt/etc/keenwg/xkeen-control.json"
    cat >"$root/opt/etc/init.d/S96keenwg-xkeen-control" <<'EOF'
#!/bin/sh
set -eu
ROOT=${KEENWG_DESTDIR:-}
MARKER=$ROOT/opt/var/run/legacy-controller.running
case "${1:-}" in
    start) mkdir -p "${MARKER%/*}"; : >"$MARKER";;
    stop) rm -f "$MARKER";;
    status) [ -f "$MARKER" ];;
    *) exit 2;;
esac
EOF
    chmod 755 "$root/opt/etc/init.d/S96keenwg-xkeen-control"
    printf '%s\n' 'foreign xkeen data' >"$root/opt/etc/xkeen/foreign-rule.lst"
    printf '%s\n' '{"schema_version":1,"secure_listen_address":"10.8.0.1:18779"}' >"$root/opt/tmp/keenwg-0123456789abcdef0123456789abcdef.json"
    KEENWG_DESTDIR="$root" "$root/opt/etc/init.d/S96keenwg-xkeen-control" start
}

BUNDLE=$TMP/bundle
make_bundle "$BUNDLE"

ROOT=$TMP/root
make_root "$ROOT"
legacy_before=$(sha256sum "$ROOT/opt/etc/keenwg/xkeen-control.json" | awk '{print $1}')
KEENWG_DESTDIR="$ROOT" "$BUNDLE/install-companion.sh" --request /opt/tmp/keenwg-0123456789abcdef0123456789abcdef.json
assert_file "$ROOT/opt/etc/keenwg/companion.json"
[ -L "$ROOT/opt/lib/keenwg-companion/current" ] || fail 'current release link missing'
KEENWG_DESTDIR="$ROOT" "$ROOT/opt/etc/init.d/S96keenwg-companion" status || fail 'companion not running'
! KEENWG_DESTDIR="$ROOT" "$ROOT/opt/etc/init.d/S96keenwg-xkeen-control" status || fail 'legacy controller still running'
[ "$(sha256sum "$ROOT/opt/etc/keenwg/xkeen-control.json" | awk '{print $1}')" = "$legacy_before" ] || fail 'legacy config changed'

config_before=$(sha256sum "$ROOT/opt/etc/keenwg/companion.json" | awk '{print $1}')
KEENWG_DESTDIR="$ROOT" "$BUNDLE/install-companion.sh" --request /opt/tmp/keenwg-0123456789abcdef0123456789abcdef.json
[ "$(sha256sum "$ROOT/opt/etc/keenwg/companion.json" | awk '{print $1}')" = "$config_before" ] || fail 'repeat install rewrote companion identity config'

FAIL_ROOT=$TMP/fail-root
make_root "$FAIL_ROOT"
: >"$FAIL_ROOT/opt/etc/keenwg/fail-health"
if KEENWG_DESTDIR="$FAIL_ROOT" "$BUNDLE/install-companion.sh" --request /opt/tmp/keenwg-0123456789abcdef0123456789abcdef.json; then
    fail 'health failure install unexpectedly succeeded'
fi
KEENWG_DESTDIR="$FAIL_ROOT" "$FAIL_ROOT/opt/etc/init.d/S96keenwg-xkeen-control" status || fail 'failed install stopped legacy controller'
[ ! -e "$FAIL_ROOT/opt/lib/keenwg-companion/current" ] || fail 'failed install retained current release'

KEENWG_DESTDIR="$ROOT" "$BUNDLE/uninstall-companion.sh"
KEENWG_DESTDIR="$ROOT" "$ROOT/opt/etc/init.d/S96keenwg-xkeen-control" status || fail 'uninstall did not restart legacy controller'
assert_file "$ROOT/opt/etc/xkeen/foreign-rule.lst"
assert_file "$ROOT/opt/etc/keenwg/xkeen-control.json"
[ ! -e "$ROOT/opt/etc/init.d/S96keenwg-companion" ] || fail 'companion init remains after uninstall'

echo 'install-companion tests passed'
