#!/bin/sh
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP/bin"
cat >"$TMP/bin/od" <<'EOF'
#!/bin/sh
exit 99
EOF
chmod 755 "$TMP/bin/od"
cat >"$TMP/bin/hexdump" <<'EOF'
#!/bin/sh
exit 99
EOF
chmod 755 "$TMP/bin/hexdump"

fail() { echo "FAIL: $*" >&2; exit 1; }
assert_file() { [ -f "$1" ] && [ ! -L "$1" ] || fail "missing regular file: $1"; }
touch "$TMP/mode-probe"; chmod 600 "$TMP/mode-probe"
if [ "$(stat -c %a "$TMP/mode-probe")" = 600 ]; then supports_modes=true; else supports_modes=false; fi
mkdir "$TMP/link-target"
if ln -s "$TMP/link-target" "$TMP/link-probe" 2>/dev/null && [ -L "$TMP/link-probe" ]; then supports_symlinks=true; else supports_symlinks=false; fi
assert_mode() { $supports_modes || return 0; [ "$(stat -c %a "$2")" = "$1" ] || fail "mode of $2 is not $1"; }

make_bundle() {
    destination=$1
    mkdir -p "$destination"
    for file in install.sh uninstall.sh S96keenwg-xkeen-control xkeen-country config.example.json; do
        cp "$HERE/$file" "$destination/$file"
    done
    cat >"$destination/keenwg-xkeen-control" <<'EOF'
#!/bin/sh
case " $* " in
    *' -version '*) echo 'keenwg-xkeen-control 0.4.0 (test)' ;;
    *' -check '*) [ "${KEENWG_TEST_FAIL_CHECK:-0}" != 1 ] ;;
    *' -bootstrap-active '*)
        config=
        while [ "$#" -gt 0 ]; do [ "$1" = -config ] && { shift; config=$1; }; shift || true; done
        state=$(sed -n 's/.*"state_path"[ ]*:[ ]*"\([^"]*\)".*/\1/p' "$config")
        root=${KEENWG_DESTDIR:-}
        mkdir -p "${root}${state%/*}"
        printf '%s\n' '{"state_version":1,"active":{"display_name":"Нидерланды 1","resolved_ip":"203.0.113.10","fingerprint":"firefox"},"operations":[]}' >"${root}${state}"
        chmod 600 "${root}${state}"
        ;;
    *' -status '*) echo 'Страна: Нидерланды 1' ;;
    *) [ "${KEENWG_TEST_FAIL_START:-0}" != 1 ] || exit 1; while :; do sleep 60; done ;;
esac
EOF
    chmod 755 "$destination/keenwg-xkeen-control" "$destination/install.sh" "$destination/uninstall.sh" "$destination/S96keenwg-xkeen-control" "$destination/xkeen-country"
    printf '%s\n' 0.4.0 >"$destination/VERSION"
    (cd "$destination" && sha256sum keenwg-xkeen-control >SHA256SUMS)
}

make_router() {
    root=$1
    mkdir -p "$root/opt/etc/init.d" "$root/opt/etc/xkeen" "$root/opt/etc/xray/configs" "$root/opt/sbin" "$root/opt/var/run"
    cat >"$root/opt/etc/init.d/S05xkeen" <<'EOF'
#!/bin/sh
# Версия: 2.30
ipv4_exclude="192.0.2.0/24 203.0.113.10/32 198.51.100.0/24"
file_ip_exclude="/opt/etc/xkeen/ip_exclude.lst"
exit 0
EOF
    chmod 755 "$root/opt/etc/init.d/S05xkeen"
    printf '%s\n' '# keep-user-entry' '198.18.0.0/15' >"$root/opt/etc/xkeen/ip_exclude.lst"
    cat >"$root/opt/etc/xray/configs/04_outbounds.json" <<'EOF'
{"outbounds":[{"tag":"vless-reality","protocol":"vless","settings":{"vnext":[{"address":"203.0.113.10","port":443,"users":[{"id":"aaaaaaaa-aaaa-2aaa-eaaa-aaaaaaaaaaaa","encryption":"none","flow":"xtls-rprx-vision"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"publicKey":"SYNTHETIC_KEY","fingerprint":"firefox","serverName":"intel.example.test","shortId":"0123456789abcdef","spiderX":"/"}}}]}
EOF
    cat >"$root/opt/etc/xray/configs/05_routing.json" <<'EOF'
{"routing":{"rules":[{"domain":["domain:okko.sport","regexp:^([\\w\\-\\.]+\\.)info$","regexp:^([\\w\\-\\.]+\\.)tv$","regexp:^([\\w\\-\\.]+\\.)ru$"],"outboundTag":"direct","type":"field"},{"ip":["ext:geoip_v2fly.dat:ru"],"outboundTag":"direct","type":"field"},{"outboundTag":"vless-reality","type":"field"}]}}
EOF
    mkdir -p "$root/opt/etc/xray/dat"
    printf '%s' 'CATEGORY-GOV-RU' >"$root/opt/etc/xray/dat/geosite_v2fly.dat"
    cat >"$root/opt/sbin/xkeen-country" <<'EOF'
#!/bin/sh
echo 'Страна: Нидерланды 1'
EOF
    chmod 755 "$root/opt/sbin/xkeen-country"
}

BUNDLE=$TMP/bundle
ROOT=$TMP/root
make_bundle "$BUNDLE"
make_router "$ROOT"
original_routing=$(sha256sum "$ROOT/opt/etc/xray/configs/05_routing.json" | awk '{print $1}')
grep -q 'start-stop-daemon' "$BUNDLE/S96keenwg-xkeen-control" || fail 'live init does not use start-stop-daemon'

PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$ROOT" KEENWG_TEST_SUBSCRIPTION_URL='https://vpn.example.test/sub/private' sh "$BUNDLE/install.sh" >/dev/null

CONFIG=$ROOT/opt/etc/keenwg/xkeen-control.json
assert_file "$CONFIG"
assert_mode 600 "$CONFIG"
grep -q '"routing_path": "/opt/etc/xray/configs/05_routing.json"' "$CONFIG" || fail 'routing path missing from config'
latest_backup=$(ls -1dt "$ROOT/opt/etc/keenwg/backups"/install-* | head -n 1)
assert_file "$latest_backup/05_routing.json"
grep -q '^# BEGIN KEENWG XKeen ENDPOINT$' "$ROOT/opt/etc/xkeen/ip_exclude.lst" || fail 'managed marker missing'
grep -q '^203\.0\.113\.10/32$' "$ROOT/opt/etc/xkeen/ip_exclude.lst" || fail 'active endpoint missing from managed block'
grep -q '^# keep-user-entry$' "$ROOT/opt/etc/xkeen/ip_exclude.lst" || fail 'user exclusion lost'
grep -q '192\.0\.2\.0/24' "$ROOT/opt/etc/init.d/S05xkeen" || fail 'unrelated S05 exclusion lost'
grep -q '198\.51\.100\.0/24' "$ROOT/opt/etc/init.d/S05xkeen" || fail 'unrelated S05 exclusion lost'
! grep -q '203\.0\.113\.10/32' "$ROOT/opt/etc/init.d/S05xkeen" || fail 'hard-coded endpoint not migrated'
assert_file "$ROOT/opt/sbin/xkeen-country"
grep -q -- '-status' "$ROOT/opt/sbin/xkeen-country" || fail 'compatibility wrapper can still mutate XKeen'
if $supports_symlinks; then
    [ -L "$ROOT/opt/lib/keenwg-xkeen-control/current" ] || fail 'current release link missing'
else
    assert_file "$ROOT/opt/lib/keenwg-xkeen-control/current/keenwg-xkeen-control"
fi

if $supports_symlinks; then
    config_before_upgrade=$(sha256sum "$CONFIG" | awk '{print $1}')
    initial_target=$(readlink "$ROOT/opt/lib/keenwg-xkeen-control/current")
    printf '%s\n' '# upgrade candidate' >>"$BUNDLE/keenwg-xkeen-control"
    (cd "$BUNDLE" && sha256sum keenwg-xkeen-control >SHA256SUMS)
    PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$ROOT" sh "$BUNDLE/install.sh" >/dev/null
    [ "$(readlink "$ROOT/opt/lib/keenwg-xkeen-control/current")" != "$initial_target" ] || fail 'upgrade did not switch the current release target'
    [ "$(sha256sum "$CONFIG" | awk '{print $1}')" = "$config_before_upgrade" ] || fail 'upgrade replaced the control token or subscription URL'
    grep -q '^# BEGIN KEENWG XKeen ENDPOINT$' "$ROOT/opt/etc/xkeen/ip_exclude.lst" || fail 'upgrade lost managed endpoint'
    ! grep -q '203\.0\.113\.10/32' "$ROOT/opt/etc/init.d/S05xkeen" || fail 'upgrade put endpoint back into S05'
    upgrade_target=$(readlink "$ROOT/opt/lib/keenwg-xkeen-control/current")
    upgrade_pid=$(cat "$ROOT/opt/var/run/keenwg-xkeen-control.pid")
    printf '%s\n' '# rejected upgrade candidate' >>"$BUNDLE/keenwg-xkeen-control"
    (cd "$BUNDLE" && sha256sum keenwg-xkeen-control >SHA256SUMS)
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$ROOT" KEENWG_TEST_FAIL_CHECK=1 sh "$BUNDLE/install.sh" >/dev/null 2>&1; then
        fail 'upgrade with failed check succeeded'
    fi
    [ "$(readlink "$ROOT/opt/lib/keenwg-xkeen-control/current")" = "$upgrade_target" ] || fail 'failed upgrade changed current release'
    [ "$(cat "$ROOT/opt/var/run/keenwg-xkeen-control.pid")" = "$upgrade_pid" ] || fail 'pre-check failure restarted the running service'
    KEENWG_DESTDIR="$ROOT" "$ROOT/opt/etc/init.d/S96keenwg-xkeen-control" status || fail 'failed upgrade did not restore running service'
fi

before_init=$(sha256sum "$ROOT/opt/etc/init.d/S05xkeen" | awk '{print $1}')
before_exclude=$(sha256sum "$ROOT/opt/etc/xkeen/ip_exclude.lst" | awk '{print $1}')
printf '%s\n' '// BEGIN KEENWG DOMAIN POLICY' '// END KEENWG DOMAIN POLICY' >"$ROOT/opt/etc/xray/configs/05_routing.json"
KEENWG_DESTDIR="$ROOT" sh "$BUNDLE/uninstall.sh" >/dev/null
[ "$(sha256sum "$ROOT/opt/etc/init.d/S05xkeen" | awk '{print $1}')" != "$before_init" ] || fail 'uninstall did not restore S05 endpoint ownership'
grep -q '203\.0\.113\.10/32' "$ROOT/opt/etc/init.d/S05xkeen" || fail 'uninstall did not restore active endpoint to S05'
! grep -q '^# BEGIN KEENWG XKeen ENDPOINT$' "$ROOT/opt/etc/xkeen/ip_exclude.lst" || fail 'uninstall left managed block'
grep -q '^# keep-user-entry$' "$ROOT/opt/etc/xkeen/ip_exclude.lst" || fail 'uninstall lost user exclusion'
[ "$before_exclude" != "$(sha256sum "$ROOT/opt/etc/xkeen/ip_exclude.lst" | awk '{print $1}')" ] || fail 'uninstall did not remove managed block'
grep -q 'Страна: Нидерланды 1' "$ROOT/opt/sbin/xkeen-country" || fail 'uninstall did not restore legacy xkeen-country'
[ "$(sha256sum "$ROOT/opt/etc/xray/configs/05_routing.json" | awk '{print $1}')" = "$original_routing" ] || fail 'uninstall did not restore original domain routing'

FAIL_ROOT=$TMP/fail-root
make_router "$FAIL_ROOT"
fail_init=$(sha256sum "$FAIL_ROOT/opt/etc/init.d/S05xkeen" | awk '{print $1}')
fail_exclude=$(sha256sum "$FAIL_ROOT/opt/etc/xkeen/ip_exclude.lst" | awk '{print $1}')
if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$FAIL_ROOT" KEENWG_TEST_SUBSCRIPTION_URL='https://vpn.example.test/sub/private' KEENWG_TEST_FAIL_CHECK=1 sh "$BUNDLE/install.sh" >/dev/null 2>&1; then
    fail 'failed controller check still installed'
fi
[ "$(sha256sum "$FAIL_ROOT/opt/etc/init.d/S05xkeen" | awk '{print $1}')" = "$fail_init" ] || fail 'failed install did not restore S05'
[ "$(sha256sum "$FAIL_ROOT/opt/etc/xkeen/ip_exclude.lst" | awk '{print $1}')" = "$fail_exclude" ] || fail 'failed install did not restore exclusion list'
[ ! -e "$FAIL_ROOT/opt/etc/keenwg/xkeen-control.json" ] || fail 'failed install left private config'

START_ROOT=$TMP/start-root
make_router "$START_ROOT"
start_init=$(sha256sum "$START_ROOT/opt/etc/init.d/S05xkeen" | awk '{print $1}')
start_exclude=$(sha256sum "$START_ROOT/opt/etc/xkeen/ip_exclude.lst" | awk '{print $1}')
if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$START_ROOT" KEENWG_TEST_SUBSCRIPTION_URL='https://vpn.example.test/sub/private' KEENWG_TEST_FAIL_START=1 sh "$BUNDLE/install.sh" >/dev/null 2>&1; then
    fail 'failed controller service start still installed'
fi
[ "$(sha256sum "$START_ROOT/opt/etc/init.d/S05xkeen" | awk '{print $1}')" = "$start_init" ] || fail 'failed start did not restore S05'
[ "$(sha256sum "$START_ROOT/opt/etc/xkeen/ip_exclude.lst" | awk '{print $1}')" = "$start_exclude" ] || fail 'failed start did not restore exclusion list'

MALFORMED=$TMP/malformed-root
make_router "$MALFORMED"
printf '%s\n' '# BEGIN KEENWG XKeen ENDPOINT' >>"$MALFORMED/opt/etc/xkeen/ip_exclude.lst"
if KEENWG_DESTDIR="$MALFORMED" KEENWG_TEST_SUBSCRIPTION_URL='https://vpn.example.test/sub/private' sh "$BUNDLE/install.sh" >/dev/null 2>&1; then
    fail 'malformed managed marker accepted'
fi

DUPLICATE=$TMP/duplicate-root
make_router "$DUPLICATE"
cat >>"$DUPLICATE/opt/etc/xkeen/ip_exclude.lst" <<'EOF'
# BEGIN KEENWG XKeen ENDPOINT
203.0.113.10/32
# END KEENWG XKeen ENDPOINT
# BEGIN KEENWG XKeen ENDPOINT
203.0.113.10/32
# END KEENWG XKeen ENDPOINT
EOF
if KEENWG_DESTDIR="$DUPLICATE" KEENWG_TEST_SUBSCRIPTION_URL='https://vpn.example.test/sub/private' sh "$BUNDLE/install.sh" >/dev/null 2>&1; then
    fail 'duplicate managed markers accepted'
fi

AMBIGUOUS=$TMP/ambiguous-root
make_router "$AMBIGUOUS"
printf '%s\n' 'ipv4_exclude="198.51.100.0/24"' >>"$AMBIGUOUS/opt/etc/init.d/S05xkeen"
if KEENWG_DESTDIR="$AMBIGUOUS" KEENWG_TEST_SUBSCRIPTION_URL='https://vpn.example.test/sub/private' sh "$BUNDLE/install.sh" >/dev/null 2>&1; then
    fail 'ambiguous ipv4_exclude assignment accepted'
fi

mkdir -p "$TMP/escape-root" "$TMP/outside"
ln -s "$TMP/outside" "$TMP/escape-root/opt"
if KEENWG_DESTDIR="$TMP/escape-root" KEENWG_TEST_SUBSCRIPTION_URL='https://vpn.example.test/sub/private' sh "$BUNDLE/install.sh" >/dev/null 2>&1; then
    fail 'symlink escape accepted'
fi
if KEENWG_DESTDIR=/ KEENWG_TEST_SUBSCRIPTION_URL='https://vpn.example.test/sub/private' sh "$BUNDLE/install.sh" >/dev/null 2>&1; then
    fail 'unsafe staging root accepted'
fi

echo 'PASS: staged XKeen controller lifecycle'
