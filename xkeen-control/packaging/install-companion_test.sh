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
assert_absent() { [ ! -e "$1" ] && [ ! -L "$1" ] || fail "obsolete path remains: $1"; }

write_fake_binary() {
    target=$1
    version=$2
    cat >"$target" <<EOF
#!/bin/sh
set -eu
ROOT=\${KEENWG_DESTDIR:-}
VERSION=$version
CONFIG=/opt/etc/keenwg/companion.json
mode=run
for arg in "\$@"; do [ "\$arg" = '-version' ] && { echo "keenwg-companion \$VERSION (test)"; exit 0; }; done
while [ "\$#" -gt 0 ]; do
    case "\$1" in
        -config) CONFIG=\$2; shift 2;;
        -bootstrap-request) REQUEST=\$2; mode=bootstrap; shift 2;;
        -upgrade-config) mode=upgrade; shift;;
        -check) mode=check; shift;;
        *) shift;;
    esac
done
case "\$mode" in
    bootstrap)
        [ -f "\$REQUEST" ] && [ ! -e "\$CONFIG" ] || exit 2
        mkdir -p "\${CONFIG%/*}" "\$ROOT/opt/etc/keenwg/identity"
        cat >"\$CONFIG" <<'JSON'
{"schema_version":2,"secure_listen_address":"10.8.0.1:18779","subscription_url":"","subscription_cache_path":"/opt/etc/keenwg/xkeen-subscription.json","state_path":"/opt/etc/keenwg/xkeen-state.json","backup_dir":"/opt/etc/keenwg/backups","outbounds_path":"/opt/etc/xray/configs/04_outbounds.json","exclude_path":"/opt/etc/xkeen/ip_exclude.lst","domain_policy_path":"/opt/etc/keenwg/domain-policy.json","domain_policy_backup_path":"/opt/etc/keenwg/domain-policy.json.bak","routing_path":"/opt/etc/xray/configs/05_routing.json","init_script":"/opt/etc/init.d/S05xkeen","xray_binary":"/opt/sbin/xray","asset_dir":"/opt/etc/xray/dat","max_subscription_bytes":262144,"max_nodes":128,"allow_private_servers":false,"discovery":{"xkeen_init_path":"/opt/etc/init.d/S05xkeen","asc_path":"/opt/sbin/asc"},"tls_certificate_path":"/opt/etc/keenwg/identity/certificate.pem","tls_private_key_path":"/opt/etc/keenwg/identity/private-key.pem","device_store_path":"/opt/etc/keenwg/devices.json","pairing_store_path":"/opt/etc/keenwg/pairing-offers.json","catalog_path":"/opt/etc/keenwg/catalog.json","catalog_secrets_path":"/opt/etc/keenwg/catalog-secrets.json","recovery_path":"/opt/etc/keenwg/recovery.json"}
JSON
        printf '%s\n' certificate >"\$ROOT/opt/etc/keenwg/identity/certificate.pem"
        printf '%s\n' private-key >"\$ROOT/opt/etc/keenwg/identity/private-key.pem"
        chmod 600 "\$CONFIG" "\$ROOT/opt/etc/keenwg/identity/certificate.pem" "\$ROOT/opt/etc/keenwg/identity/private-key.pem"
        ;;
    upgrade)
        subscription=\$(sed -n 's/.*"subscription_url"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "\$CONFIG")
        if grep -q '"schema_version"' "\$CONFIG"; then
            grep -q '"schema_version"[[:space:]]*:[[:space:]]*1' "\$CONFIG" || exit 2
            sed 's/"schema_version"[[:space:]]*:[[:space:]]*1/"schema_version":2/' "\$CONFIG" >"\$CONFIG.schema"
        else
            awk 'NR == 1 { print; print "  \\"schema_version\\":2,"; next } { print }' "\$CONFIG" >"\$CONFIG.schema"
        fi
        sed -e '/"listen_address"/d' -e '/"token"/d' -e '/"legacy_api_enabled"/d' "\$CONFIG.schema" >"\$CONFIG.upgrade"
        rm -f "\$CONFIG.schema"
        mv "\$CONFIG.upgrade" "\$CONFIG"
        [ -n "\$subscription" ] || true
        ;;
    check)
        grep -q '"schema_version"[[:space:]]*:[[:space:]]*2' "\$CONFIG"
        ! grep -q '"legacy_api_enabled"\|"listen_address"\|"token"' "\$CONFIG"
        [ -f "\$ROOT/opt/etc/keenwg/identity/certificate.pem" ]
        [ -f "\$ROOT/opt/etc/keenwg/identity/private-key.pem" ]
        ;;
    run)
        READY="\$ROOT/opt/var/run/keenwg-companion.ready"
        mkdir -p "\${READY%/*}"
        if [ ! -f "\$ROOT/opt/etc/keenwg/fail-health-\$VERSION" ]; then : >"\$READY"; fi
        trap 'rm -f "\$READY"; exit 0' TERM INT
        while :; do sleep 1; done
        ;;
esac
EOF
    sed -i 's/\r$//' "$target"
    chmod 755 "$target"
}

make_bundle() {
    bundle=$1
    mkdir -p "$bundle"
    cp "$HERE/install-companion.sh" "$HERE/uninstall-companion.sh" "$HERE/cleanup-obsolete-controller.sh" "$HERE/S96keenwg-companion" "$HERE/companion.config.example.json" "$bundle/"
    sed -i 's/\r$//' "$bundle/install-companion.sh" "$bundle/uninstall-companion.sh" "$bundle/cleanup-obsolete-controller.sh" "$bundle/S96keenwg-companion"
    chmod 755 "$bundle/install-companion.sh" "$bundle/uninstall-companion.sh" "$bundle/cleanup-obsolete-controller.sh" "$bundle/S96keenwg-companion"
    printf '%s\n' '2.0.0' >"$bundle/VERSION"
    write_fake_binary "$bundle/keenwg-companion" '2.0.0'
    (cd "$bundle" && sha256sum keenwg-companion >SHA256SUMS)
}

make_root() {
    root=$1
    mkdir -p "$root/opt/etc/keenwg" "$root/opt/etc/init.d" "$root/opt/etc/xkeen" "$root/opt/lib" "$root/opt/sbin" "$root/opt/tmp" "$root/opt/var/run"
    printf '%s\n' 'foreign xkeen data' >"$root/opt/etc/xkeen/foreign-rule.lst"
    printf '%s\n' '{"schema_version":1,"secure_listen_address":"10.8.0.1:18779"}' >"$root/opt/tmp/keenwg-0123456789abcdef0123456789abcdef.json"
}

install_previous_companion() {
    root=$1
    mkdir -p "$root/opt/lib/keenwg-companion/releases/1.0.0-test" "$root/opt/etc/keenwg/identity"
    write_fake_binary "$root/opt/lib/keenwg-companion/releases/1.0.0-test/keenwg-companion" '1.0.0'
    ln -s releases/1.0.0-test "$root/opt/lib/keenwg-companion/current"
    cp "$HERE/S96keenwg-companion" "$root/opt/etc/init.d/S96keenwg-companion"
    sed -i 's/\r$//' "$root/opt/etc/init.d/S96keenwg-companion"
    chmod 755 "$root/opt/etc/init.d/S96keenwg-companion"
}

write_v1_config() {
    root=$1
    mkdir -p "$root/opt/etc/keenwg/identity"
    cat >"$root/opt/etc/keenwg/companion.json" <<'JSON'
{
  "listen_address":"10.8.0.1:18778",
  "secure_listen_address":"10.8.0.1:18779",
  "token":"obsolete-shared-controller-value",
  "subscription_url":"https://vpn.example.test/sub/test",
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
  "max_nodes":128,
  "allow_private_servers":false,
  "tls_certificate_path":"/opt/etc/keenwg/identity/certificate.pem",
  "tls_private_key_path":"/opt/etc/keenwg/identity/private-key.pem",
  "device_store_path":"/opt/etc/keenwg/devices.json",
  "pairing_store_path":"/opt/etc/keenwg/pairing-offers.json",
  "legacy_api_enabled":true
}
JSON
    printf '%s\n' stable-certificate >"$root/opt/etc/keenwg/identity/certificate.pem"
    printf '%s\n' stable-private-key >"$root/opt/etc/keenwg/identity/private-key.pem"
    printf '%s\n' stable-devices >"$root/opt/etc/keenwg/devices.json"
    chmod 600 "$root/opt/etc/keenwg/companion.json" "$root/opt/etc/keenwg/identity/certificate.pem" "$root/opt/etc/keenwg/identity/private-key.pem" "$root/opt/etc/keenwg/devices.json"
}

write_v2_config() {
    root=$1
    write_v1_config "$root"
    sed -e 's/"schema_version":1/"schema_version":2/' -e '/"listen_address"/d' -e '/"token"/d' -e '/"legacy_api_enabled"/d' "$root/opt/etc/keenwg/companion.json" >"$root/opt/etc/keenwg/companion.json.next"
    mv "$root/opt/etc/keenwg/companion.json.next" "$root/opt/etc/keenwg/companion.json"
}

write_obsolete_controller() {
    root=$1
    mkdir -p "$root/opt/lib/keenwg-xkeen-control/releases/1.0.0" "$root/opt/etc/init.d"
    printf '%s\n' old >"$root/opt/lib/keenwg-xkeen-control/releases/1.0.0/binary"
    ln -s releases/1.0.0 "$root/opt/lib/keenwg-xkeen-control/current"
    printf '%s\n' preserve >"$root/opt/etc/keenwg/legacy-cleanup-sentinel"
    ln -s ../../etc/keenwg/legacy-cleanup-sentinel "$root/opt/lib/keenwg-xkeen-control/stale-outside"
    printf '%s\n' old-config >"$root/opt/etc/keenwg/xkeen-control.json"
    printf '%s\n' old-helper >"$root/opt/sbin/xkeen-country"
    printf '%s\n' old-pid >"$root/opt/var/run/keenwg-xkeen-control.pid"
    cat >"$root/opt/etc/init.d/S96keenwg-xkeen-control" <<'EOF'
#!/bin/sh
exit 0
EOF
    chmod 755 "$root/opt/etc/init.d/S96keenwg-xkeen-control"
}

BUNDLE=$TMP/bundle
make_bundle "$BUNDLE"
REQUEST=/opt/tmp/keenwg-0123456789abcdef0123456789abcdef.json

# Clean install must not depend on any old controller file.
CLEAN_ROOT=$TMP/clean
make_root "$CLEAN_ROOT"
KEENWG_DESTDIR="$CLEAN_ROOT" "$BUNDLE/install-companion.sh" --request "$REQUEST"
assert_file "$CLEAN_ROOT/opt/etc/keenwg/companion.json"
grep -q '"schema_version":2' "$CLEAN_ROOT/opt/etc/keenwg/companion.json" || fail 'clean install did not write schema v2'
KEENWG_DESTDIR="$CLEAN_ROOT" "$CLEAN_ROOT/opt/etc/init.d/S96keenwg-companion" health || fail 'clean companion is unhealthy'
assert_file "$CLEAN_ROOT/opt/etc/xkeen/foreign-rule.lst"

# An existing v1 Companion is upgraded in place; identity and state survive.
UPDATE_ROOT=$TMP/update
make_root "$UPDATE_ROOT"
install_previous_companion "$UPDATE_ROOT"
write_v1_config "$UPDATE_ROOT"
write_obsolete_controller "$UPDATE_ROOT"
KEENWG_DESTDIR="$UPDATE_ROOT" "$UPDATE_ROOT/opt/etc/init.d/S96keenwg-companion" start
cert_before=$(sha256sum "$UPDATE_ROOT/opt/etc/keenwg/identity/certificate.pem" | awk '{print $1}')
devices_before=$(sha256sum "$UPDATE_ROOT/opt/etc/keenwg/devices.json" | awk '{print $1}')
KEENWG_DESTDIR="$UPDATE_ROOT" "$BUNDLE/install-companion.sh" --request "$REQUEST"
grep -q '"schema_version":2' "$UPDATE_ROOT/opt/etc/keenwg/companion.json" || fail 'v1 config was not upgraded'
! grep -q '"legacy_api_enabled"\|"listen_address"\|"token"' "$UPDATE_ROOT/opt/etc/keenwg/companion.json" || fail 'obsolete config fields remain'
[ "$(sha256sum "$UPDATE_ROOT/opt/etc/keenwg/identity/certificate.pem" | awk '{print $1}')" = "$cert_before" ] || fail 'identity changed during update'
[ "$(sha256sum "$UPDATE_ROOT/opt/etc/keenwg/devices.json" | awk '{print $1}')" = "$devices_before" ] || fail 'device store changed during update'
assert_absent "$UPDATE_ROOT/opt/etc/init.d/S96keenwg-xkeen-control"
assert_absent "$UPDATE_ROOT/opt/lib/keenwg-xkeen-control"
assert_absent "$UPDATE_ROOT/opt/etc/keenwg/xkeen-control.json"
assert_absent "$UPDATE_ROOT/opt/sbin/xkeen-country"
assert_file "$UPDATE_ROOT/opt/etc/xkeen/foreign-rule.lst"
assert_file "$UPDATE_ROOT/opt/etc/keenwg/legacy-cleanup-sentinel"

# A failed candidate restores the previous Companion and does not run cleanup.
FAIL_ROOT=$TMP/failure
make_root "$FAIL_ROOT"
install_previous_companion "$FAIL_ROOT"
write_v2_config "$FAIL_ROOT"
write_obsolete_controller "$FAIL_ROOT"
KEENWG_DESTDIR="$FAIL_ROOT" "$FAIL_ROOT/opt/etc/init.d/S96keenwg-companion" start
: >"$FAIL_ROOT/opt/etc/keenwg/fail-health-2.0.0"
FAIL_LOG=$FAIL_ROOT/install-failure.log
if KEENWG_DESTDIR="$FAIL_ROOT" "$BUNDLE/install-companion.sh" --request "$REQUEST" >"$FAIL_LOG" 2>&1; then
    fail 'failed candidate unexpectedly committed'
fi
grep -q 'companion HTTPS health failed' "$FAIL_LOG" || fail 'candidate failed for an unexpected reason'
[ "$(readlink "$FAIL_ROOT/opt/lib/keenwg-companion/current")" = 'releases/1.0.0-test' ] || fail 'previous release link was not restored'
KEENWG_DESTDIR="$FAIL_ROOT" "$FAIL_ROOT/opt/etc/init.d/S96keenwg-companion" health || fail 'previous Companion was not restarted'
assert_file "$FAIL_ROOT/opt/etc/init.d/S96keenwg-xkeen-control"

# Uninstall preserves user state and never starts an obsolete controller.
KEENWG_DESTDIR="$UPDATE_ROOT" "$BUNDLE/uninstall-companion.sh"
assert_absent "$UPDATE_ROOT/opt/etc/init.d/S96keenwg-companion"
assert_absent "$UPDATE_ROOT/opt/lib/keenwg-companion"
assert_file "$UPDATE_ROOT/opt/etc/keenwg/companion.json"
assert_file "$UPDATE_ROOT/opt/etc/keenwg/identity/private-key.pem"
assert_file "$UPDATE_ROOT/opt/etc/keenwg/devices.json"

echo 'install-companion tests passed'
