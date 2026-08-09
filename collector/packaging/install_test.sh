#!/bin/sh
set -eu
MSYS=winsymlinks:nativestrict
export MSYS

fail() { echo "FAIL: $*" >&2; exit 1; }
assert_file() { [ -f "$1" ] || fail "missing file $1"; }
assert_dir() { [ -d "$1" ] || fail "missing directory $1"; }

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TMP=${TMPDIR:-/tmp}/keenwg-install-test-$$
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP/bundle" "$TMP/root/opt/var/lib/keenwg" "$TMP/bin"
mkdir -p "$TMP/lock-proc/901"
printf '%s\n' '901 (installer) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 0 0 0 0 111 0' >"$TMP/lock-proc/901/stat"
printf '%s\n' live >"$TMP/lock-proc/901/alive"
printf '%s\n' keenwg-test-boot >"$TMP/lock-boot-id"
cat >"$TMP/bin/lock-alive" <<EOF
#!/bin/sh
[ -f "$TMP/lock-proc/\$1/alive" ]
EOF
chmod 755 "$TMP/bin/lock-alive"
KEENWG_LOCK_PROC_ROOT="$TMP/lock-proc"
KEENWG_LOCK_BOOT_ID_FILE="$TMP/lock-boot-id"
KEENWG_LOCK_SELF_PID=901
KEENWG_LOCK_ALIVE="$TMP/bin/lock-alive"
export KEENWG_LOCK_PROC_ROOT KEENWG_LOCK_BOOT_ID_FILE KEENWG_LOCK_SELF_PID KEENWG_LOCK_ALIVE
mkdir -p "$TMP/symlink-target"
if ln -s "$TMP/symlink-target" "$TMP/symlink-probe" 2>/dev/null && [ -L "$TMP/symlink-probe" ]; then supports_symlinks=true; else supports_symlinks=false; fi
touch "$TMP/mode-probe"; chmod 600 "$TMP/mode-probe"; if [ "$(stat -c %a "$TMP/mode-probe")" = 600 ]; then supports_modes=true; else supports_modes=false; fi
cp "$HERE/install.sh" "$HERE/uninstall.sh" "$HERE/S95keenwg" "$HERE/95-keenwg-signal" "$HERE/config.example.json" "$TMP/bundle/"
for script in install.sh uninstall.sh S95keenwg 95-keenwg-signal; do
    sh -n "$TMP/bundle/$script" || fail "$script has invalid shell syntax"
done

cat >"$TMP/bundle/keenwg-collector" <<'EOF'
#!/bin/sh
[ "${1:-}" = "-check" ] || exit 2
[ "${FAIL_CHECK:-0}" = 1 ] && exit 1
[ -z "${CHECK_REQUIRE_FILE:-}" ] || [ -f "$CHECK_REQUIRE_FILE" ] || exit 4
if [ -n "${CHECK_CURRENT:-}" ]; then
    [ "$(readlink "$CHECK_CURRENT")" = "${CHECK_EXPECTED:-}" ] || exit 3
fi
exit 0
EOF
chmod 755 "$TMP/bundle/keenwg-collector"
(cd "$TMP/bundle" && sha256sum keenwg-collector >SHA256SUMS)
printf '%s\n' 0.3.0 >"$TMP/bundle/VERSION"
BINARY_HASH=$(sha256sum "$TMP/bundle/keenwg-collector" | awk '{print $1}')
RELEASE_ID=0.3.0-$BINARY_HASH
cat >"$TMP/bin/opkg" <<'EOF'
#!/bin/sh
[ -z "${OPKG_LOG:-}" ] || printf '%s\n' "$*" >>"$OPKG_LOG"
[ -z "${OPKG_PROVISION_FILE:-}" ] || : >"$OPKG_PROVISION_FILE"
if [ -n "${OPKG_NDMQ_PATH:-}" ]; then
    printf '%s\n' '#!/bin/sh' 'exit 0' >"$OPKG_NDMQ_PATH"
    chmod 755 "$OPKG_NDMQ_PATH"
fi
exit 0
EOF
cat >"$TMP/bin/uname" <<'EOF'
#!/bin/sh
[ "${1:-}" = "-m" ] || exit 2
printf '%s\n' aarch64
EOF
cat >"$TMP/bin/success-service" <<'EOF'
#!/bin/sh
exit 0
EOF
cat >"$TMP/bin/track-current-service" <<'EOF'
#!/bin/sh
case "${1:-}" in
    restart|start)
        rm -rf "$PROOF_PROC_ROOT/321"
        mkdir -p "$PROOF_PROC_ROOT/321"
        printf '%s\n' 321 >"$PROOF_PID_FILE"
        printf '%s\n' '321 777' >"$PROOF_IDENTITY_FILE"
        printf '%s\n' '321 (keenwg) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 0 0 0 0 777 0' >"$PROOF_PROC_ROOT/321/stat"
        ln -s "$(readlink -f "$PROOF_CURRENT/keenwg-collector")" "$PROOF_PROC_ROOT/321/exe"
        ;;
    stop)
        rm -rf "$PROOF_PROC_ROOT/321"
        rm -f "$PROOF_PID_FILE" "$PROOF_IDENTITY_FILE"
        ;;
    *) exit 2 ;;
esac
EOF
cat >"$TMP/bin/proof-alive" <<'EOF'
#!/bin/sh
exit 0
EOF
cat >"$TMP/bin/correct-health" <<'EOF'
#!/bin/sh
printf '{"status":"ok","version":"%s"}\n' "$1"
EOF
chmod 755 "$TMP/bin/opkg" "$TMP/bin/uname" "$TMP/bin/success-service" "$TMP/bin/track-current-service" "$TMP/bin/proof-alive" "$TMP/bin/correct-health"

mkdir -p "$TMP/fresh-live-root"
mkdir -p "$TMP/fresh-proof-proc"
if CHECK_REQUIRE_FILE="$TMP/ndmq-provisioned" OPKG_PROVISION_FILE="$TMP/ndmq-provisioned" OPKG_NDMQ_PATH="$TMP/bin/ndmq" OPKG_LOG="$TMP/fresh-opkg.log" \
    PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/fresh-live-root" KEENWG_TEST_LIVE=1 \
    KEENWG_TEST_SERVICE="$TMP/bin/track-current-service" KEENWG_TEST_HEALTH="$TMP/bin/correct-health" \
    KEENWG_PID_FILE="$TMP/fresh.pid" KEENWG_IDENTITY_FILE="$TMP/fresh.pid.start" KEENWG_PROC_ROOT="$TMP/fresh-proof-proc" KEENWG_PROCESS_ALIVE="$TMP/bin/proof-alive" \
    KEENWG_COLLECTOR_PREFIX="$TMP/fresh-live-root/opt/keenwg" PROOF_PID_FILE="$TMP/fresh.pid" PROOF_IDENTITY_FILE="$TMP/fresh.pid.start" PROOF_PROC_ROOT="$TMP/fresh-proof-proc" PROOF_CURRENT="$TMP/fresh-live-root/opt/keenwg/current" \
    sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then
    :
else
    fail "fresh live install did not provision ndmq before candidate self-check"
fi
grep -qx 'install ndmq' "$TMP/fresh-opkg.log" || fail "fresh live install did not request ndmq exactly once"

mkdir -p "$TMP/bin-with-ndmq" "$TMP/preinstalled-live-root"
cp "$TMP/bin/uname" "$TMP/bin/success-service" "$TMP/bin/correct-health" "$TMP/bin-with-ndmq/"
cat >"$TMP/bin-with-ndmq/ndmq" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod 755 "$TMP/bin-with-ndmq/"*
rm -f "$TMP/preinstalled-opkg.log"
CHECK_REQUIRE_FILE="$TMP/bin-with-ndmq/ndmq" OPKG_LOG="$TMP/preinstalled-opkg.log" \
    PATH="$TMP/bin-with-ndmq:$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/preinstalled-live-root" KEENWG_TEST_LIVE=1 \
    KEENWG_TEST_SERVICE="$TMP/bin/track-current-service" KEENWG_TEST_HEALTH="$TMP/bin/correct-health" \
    KEENWG_PID_FILE="$TMP/preinstalled.pid" KEENWG_IDENTITY_FILE="$TMP/preinstalled.pid.start" KEENWG_PROC_ROOT="$TMP/fresh-proof-proc" KEENWG_PROCESS_ALIVE="$TMP/bin/proof-alive" \
    KEENWG_COLLECTOR_PREFIX="$TMP/preinstalled-live-root/opt/keenwg" PROOF_PID_FILE="$TMP/preinstalled.pid" PROOF_IDENTITY_FILE="$TMP/preinstalled.pid.start" PROOF_PROC_ROOT="$TMP/fresh-proof-proc" PROOF_CURRENT="$TMP/preinstalled-live-root/opt/keenwg/current" \
    sh "$TMP/bundle/install.sh" >/dev/null 2>&1 || fail "live install with existing ndmq failed"
[ ! -e "$TMP/preinstalled-opkg.log" ] || fail "live install called opkg although ndmq was already available"

for invalid_version in '' '../escape' '0.3.0/../../escape' '.' '..' 'bad/version' 'bad version' '-hidden'; do
    version_root=$TMP/version-root-$(printf '%s' "$invalid_version" | sha256sum | awk '{print $1}')
    mkdir -p "$version_root"
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$version_root" KEENWG_VERSION="$invalid_version" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then
        fail "unsafe release version succeeded: $invalid_version"
    fi
done
[ ! -e "$TMP/version-root-$(printf '%s' '../escape' | sha256sum | awk '{print $1}')/opt/keenwg/escape" ] || fail "traversal version created path outside releases"

mkdir -p "$TMP/version-mismatch-root"
if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/version-mismatch-root" KEENWG_VERSION=0.3.1 sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then
    fail "installer accepted version different from bundle manifest"
fi

if $supports_symlinks; then
    printf '%s\n' config-sentinel >"$TMP/outside-config"
    mkdir -p "$TMP/config-link-root/opt/etc/keenwg"
    ln -s "$TMP/outside-config" "$TMP/config-link-root/opt/etc/keenwg/config.json"
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/config-link-root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then fail "install accepted config symlink leaf"; fi
    grep -q config-sentinel "$TMP/outside-config" || fail "install changed config symlink target"

    printf '%s\n' init-sentinel >"$TMP/outside-init"
    mkdir -p "$TMP/init-link-root/opt/etc/init.d"
    ln -s "$TMP/outside-init" "$TMP/init-link-root/opt/etc/init.d/S95keenwg"
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/init-link-root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then fail "install accepted init symlink leaf"; fi
    grep -q init-sentinel "$TMP/outside-init" || fail "install changed init symlink target"

    printf '%s\n' hook-sentinel >"$TMP/outside-hook"
    mkdir -p "$TMP/hook-link-root/opt/etc/ndm/ifcreated.d"
    ln -s "$TMP/outside-hook" "$TMP/hook-link-root/opt/etc/ndm/ifcreated.d/95-keenwg"
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/hook-link-root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then fail "install accepted hook symlink leaf"; fi
    grep -q hook-sentinel "$TMP/outside-hook" || fail "install changed hook symlink target"

    cat >"$TMP/outside-uninstall-init" <<EOF
#!/bin/sh
printf '%s\n' executed >"$TMP/uninstall-init-executed"
EOF
    chmod 755 "$TMP/outside-uninstall-init"
    mkdir -p "$TMP/uninstall-init-link-root/opt/etc/init.d"
    ln -s "$TMP/outside-uninstall-init" "$TMP/uninstall-init-link-root/opt/etc/init.d/S95keenwg"
    if KEENWG_DESTDIR="$TMP/uninstall-init-link-root" KEENWG_TEST_LIVE=1 sh "$TMP/bundle/uninstall.sh" >/dev/null 2>&1; then fail "uninstall accepted init symlink leaf"; fi
    [ ! -e "$TMP/uninstall-init-executed" ] || fail "uninstall executed init symlink before validation"

    printf '%s\n' binary-sentinel >"$TMP/outside-binary"
    mkdir -p "$TMP/binary-link-root/opt/keenwg/releases/$RELEASE_ID"
    ln -s "$TMP/outside-binary" "$TMP/binary-link-root/opt/keenwg/releases/$RELEASE_ID/keenwg-collector"
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/binary-link-root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then fail "install accepted release binary symlink leaf"; fi
    grep -q binary-sentinel "$TMP/outside-binary" || fail "install changed release binary symlink target"

    mkdir -p "$TMP/corrupt-release-root/opt/keenwg/releases/$RELEASE_ID"
    printf '%s\n' corrupt >"$TMP/corrupt-release-root/opt/keenwg/releases/$RELEASE_ID/keenwg-collector"
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/corrupt-release-root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then fail "install replaced corrupt immutable release"; fi
    grep -q corrupt "$TMP/corrupt-release-root/opt/keenwg/releases/$RELEASE_ID/keenwg-collector" || fail "install overwrote corrupt immutable release"
fi

assert_line=$(awk '/assert_safe_target "\$INIT"/ { print NR; exit }' "$TMP/bundle/uninstall.sh")
stop_line=$(awk '/"\$INIT" stop/ { print NR; exit }' "$TMP/bundle/uninstall.sh")
[ -n "$assert_line" ] && [ -n "$stop_line" ] && [ "$assert_line" -lt "$stop_line" ] || fail "uninstall validates init only after executing it"

PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/root" sh "$TMP/bundle/install.sh"
assert_file "$TMP/root/opt/keenwg/releases/$RELEASE_ID/keenwg-collector"
grep -qx 0.3.0 "$TMP/root/opt/keenwg/releases/$RELEASE_ID/VERSION" || fail "immutable release lacks its version manifest"
assert_file "$TMP/root/opt/etc/keenwg/config.json"
assert_file "$TMP/root/opt/etc/init.d/S95keenwg"
for hook in ifcreated.d ifdestroyed.d ifipchanged.d; do assert_file "$TMP/root/opt/etc/ndm/$hook/95-keenwg"; done
assert_dir "$TMP/root/opt/var/lib/keenwg"
if $supports_symlinks; then [ -L "$TMP/root/opt/keenwg/current" ] || fail "current is not a symlink"; else [ -e "$TMP/root/opt/keenwg/current" ] || fail "current release missing"; fi
if $supports_modes; then [ "$(stat -c %a "$TMP/root/opt/etc/keenwg/config.json")" = 600 ] || fail "config mode is not 0600"; fi

mkdir -p "$TMP/lock-proc/902" "$TMP/root/opt/var/lock/keenwg-lifecycle.lock"
printf '%s\n' '902 (installer) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 0 0 0 0 222 0' >"$TMP/lock-proc/902/stat"
printf '%s\n' live >"$TMP/lock-proc/902/alive"
printf '%s\n' '902 222 keenwg-test-boot' >"$TMP/root/opt/var/lock/keenwg-lifecycle.lock/owner"
before_lock_target=$(readlink "$TMP/root/opt/keenwg/current")
if $supports_modes; then chmod 755 "$TMP/root/opt/etc/keenwg"; fi
if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then
    fail "installer ignored an active lifecycle lock"
fi
[ "$(readlink "$TMP/root/opt/keenwg/current")" = "$before_lock_target" ] || fail "active lock allowed current to change"
grep -qx '902 222 keenwg-test-boot' "$TMP/root/opt/var/lock/keenwg-lifecycle.lock/owner" || fail "active lifecycle lock owner was changed"
if $supports_modes; then
    [ "$(stat -c %a "$TMP/root/opt/etc/keenwg")" = 755 ] || fail "active lifecycle lock allowed installer mutation"
    chmod 700 "$TMP/root/opt/etc/keenwg"
fi
rm -rf "$TMP/root/opt/var/lock/keenwg-lifecycle.lock"

mkdir -p "$TMP/colon-lock-root/opt/var/lock/keenwg-lifecycle.lock"
printf '%s\n' '9:03 333 keenwg-test-boot' >"$TMP/colon-lock-root/opt/var/lock/keenwg-lifecycle.lock/owner"
colon_lock_hash=$(sha256sum "$TMP/colon-lock-root/opt/var/lock/keenwg-lifecycle.lock/owner" | awk '{print $1}')
if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/colon-lock-root" sh "$TMP/bundle/install.sh" >"$TMP/colon-lock.log" 2>&1; then
    fail "installer accepted a colon-delimited lifecycle owner identity"
fi
grep -q 'lifecycle lock is unsafe' "$TMP/colon-lock.log" || fail "installer did not classify colon-delimited lifecycle owner as unsafe"
[ "$(sha256sum "$TMP/colon-lock-root/opt/var/lock/keenwg-lifecycle.lock/owner" | awk '{print $1}')" = "$colon_lock_hash" ] || fail "installer changed colon-delimited lifecycle owner"

mkdir -p "$TMP/multiline-lock-root/opt/var/lock/keenwg-lifecycle.lock"
printf '%s\n' '901 111 keenwg-test-boot' 'trailing owner record' >"$TMP/multiline-lock-root/opt/var/lock/keenwg-lifecycle.lock/owner"
multiline_lock_hash=$(sha256sum "$TMP/multiline-lock-root/opt/var/lock/keenwg-lifecycle.lock/owner" | awk '{print $1}')
if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/multiline-lock-root" sh "$TMP/bundle/install.sh" >"$TMP/multiline-lock.log" 2>&1; then
    fail "installer accepted a multiline lifecycle owner record"
fi
grep -q 'lifecycle lock is unsafe' "$TMP/multiline-lock.log" || fail "installer did not classify multiline lifecycle owner as unsafe"
[ "$(sha256sum "$TMP/multiline-lock-root/opt/var/lock/keenwg-lifecycle.lock/owner" | awk '{print $1}')" = "$multiline_lock_hash" ] || fail "installer changed multiline lifecycle owner"

mkdir -p "$TMP/stale-lock-root/opt/var/lock/keenwg-lifecycle.lock"
printf '%s\n' '903 333 previous-boot' >"$TMP/stale-lock-root/opt/var/lock/keenwg-lifecycle.lock/owner"
mkdir -p "$TMP/stale-lock-root/opt/var/lock/.keenwg-lifecycle-mutex-previous-boot"
printf '%s\n' '903 333 previous-boot' >"$TMP/stale-lock-root/opt/var/lock/.keenwg-lifecycle-mutex-previous-boot/owner"
mkdir -p "$TMP/stale-lock-root/opt/var/lock/.keenwg-candidate.orphan" "$TMP/stale-lock-root/opt/var/lock/.keenwg-stale.orphan" "$TMP/stale-lock-root/opt/var/lock/.keenwg-release.orphan"
PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/stale-lock-root" sh "$TMP/bundle/install.sh" >/dev/null || fail "installer did not recover a stale power-loss lock"
[ ! -e "$TMP/stale-lock-root/opt/var/lock/keenwg-lifecycle.lock" ] || fail "installer left lifecycle lock after success"

mkdir -p "$TMP/owner-write-fail-root"
if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/owner-write-fail-root" KEENWG_TEST_LOCK_OWNER_WRITE_FAIL=1 \
    sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then
    fail "installer continued after lifecycle owner publication failed"
fi
[ ! -e "$TMP/owner-write-fail-root/opt/var/lock/keenwg-lifecycle.lock" ] || fail "failed owner publication left a fixed lifecycle lock"
[ -z "$(find "$TMP/owner-write-fail-root/opt/var/lock" -maxdepth 1 -name '.keenwg-candidate.*' -print -quit)" ] || fail "failed owner publication left a candidate directory"

mkdir -p "$TMP/mutex-owner-write-fail-root"
if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/mutex-owner-write-fail-root" KEENWG_TEST_LOCK_MUTEX_OWNER_WRITE_FAIL=1 \
    sh "$TMP/bundle/install.sh" >"$TMP/mutex-owner-write-fail.log" 2>&1; then
    fail "installer continued after lifecycle mutex owner publication failed"
fi
grep -q 'lifecycle acquisition mutex is unsafe' "$TMP/mutex-owner-write-fail.log" || fail "installer did not report mutex owner publication failure safely"
[ ! -e "$TMP/mutex-owner-write-fail-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot" ] || fail "failed mutex owner publication left an acquisition mutex"
[ ! -e "$TMP/mutex-owner-write-fail-root/opt/var/lock/keenwg-lifecycle.lock" ] || fail "failed mutex owner publication left a fixed lifecycle lock"

mkdir -p "$TMP/malformed-mutex-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot"
if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/malformed-mutex-root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then
    fail "installer replaced a malformed lifecycle acquisition mutex"
fi
[ -d "$TMP/malformed-mutex-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot" ] || fail "malformed mutex leaf was replaced"
[ ! -e "$TMP/malformed-mutex-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner" ] || fail "malformed mutex leaf was modified"

mkdir -p "$TMP/colon-mutex-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot"
printf '%s\n' '9:04 444 keenwg-test-boot' >"$TMP/colon-mutex-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner"
colon_mutex_hash=$(sha256sum "$TMP/colon-mutex-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner" | awk '{print $1}')
if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/colon-mutex-root" sh "$TMP/bundle/install.sh" >"$TMP/colon-mutex.log" 2>&1; then
    fail "installer accepted a colon-delimited lifecycle mutex identity"
fi
grep -q 'lifecycle acquisition mutex is unsafe' "$TMP/colon-mutex.log" || fail "installer did not classify colon-delimited lifecycle mutex as unsafe"
[ "$(sha256sum "$TMP/colon-mutex-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner" | awk '{print $1}')" = "$colon_mutex_hash" ] || fail "installer changed colon-delimited lifecycle mutex owner"

mkdir -p "$TMP/multiline-mutex-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot"
printf '%s\n' '901 111 keenwg-test-boot' 'trailing owner record' >"$TMP/multiline-mutex-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner"
multiline_mutex_hash=$(sha256sum "$TMP/multiline-mutex-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner" | awk '{print $1}')
if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/multiline-mutex-root" sh "$TMP/bundle/install.sh" >"$TMP/multiline-mutex.log" 2>&1; then
    fail "installer accepted a multiline lifecycle mutex owner record"
fi
grep -q 'lifecycle acquisition mutex is unsafe' "$TMP/multiline-mutex.log" || fail "installer did not classify multiline lifecycle mutex as unsafe"
[ "$(sha256sum "$TMP/multiline-mutex-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner" | awk '{print $1}')" = "$multiline_mutex_hash" ] || fail "installer changed multiline lifecycle mutex owner"

if $supports_symlinks; then
    mkdir -p "$TMP/mutex-link-root/opt/var/lock" "$TMP/mutex-outside"
    printf '%s\n' mutex-sentinel >"$TMP/mutex-outside/sentinel"
    ln -s "$TMP/mutex-outside" "$TMP/mutex-link-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot"
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/mutex-link-root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then
        fail "installer replaced a lifecycle acquisition mutex symlink"
    fi
    [ -L "$TMP/mutex-link-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot" ] || fail "mutex symlink leaf was replaced"
    grep -q mutex-sentinel "$TMP/mutex-outside/sentinel" || fail "installer changed mutex symlink target"
fi

mkdir -p "$TMP/lock-proc/904" "$TMP/live-mutex-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot"
printf '%s\n' '904 (mutex) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 0 0 0 0 444 0' >"$TMP/lock-proc/904/stat"
printf '%s\n' live >"$TMP/lock-proc/904/alive"
printf '%s\n' '904 444 keenwg-test-boot' >"$TMP/live-mutex-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner"
if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/live-mutex-root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then
    fail "installer stole a live lifecycle acquisition mutex"
fi
grep -qx '904 444 keenwg-test-boot' "$TMP/live-mutex-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner" || fail "live mutex identity was changed"
rm -rf "$TMP/lock-proc/904"

cat >"$TMP/bin/hold-lock-mutex" <<EOF
#!/bin/sh
: >"$TMP/mutex-ready"
while [ ! -f "$TMP/mutex-release" ]; do sleep 1; done
EOF
chmod 755 "$TMP/bin/hold-lock-mutex"
mkdir -p "$TMP/mutex-race-root/opt/var/lock/keenwg-lifecycle.lock"
printf '%s\n' '903 333 previous-boot' >"$TMP/mutex-race-root/opt/var/lock/keenwg-lifecycle.lock/owner"
PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/mutex-race-root" KEENWG_TEST_LOCK_MUTEX_HOOK="$TMP/bin/hold-lock-mutex" \
    sh "$TMP/bundle/install.sh" >"$TMP/mutex-first.log" 2>&1 &
mutex_first_pid=$!
mutex_wait=0
while [ ! -f "$TMP/mutex-ready" ] && [ "$mutex_wait" -lt 10 ]; do sleep 1; mutex_wait=$((mutex_wait + 1)); done
if [ ! -f "$TMP/mutex-ready" ]; then
    : >"$TMP/mutex-release"
    wait "$mutex_first_pid" 2>/dev/null || true
    fail "first stale-lock recovery did not acquire its sibling mutex"
fi
if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/mutex-race-root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then
    mutex_second_succeeded=true
else
    mutex_second_succeeded=false
fi
: >"$TMP/mutex-release"
wait "$mutex_first_pid" || fail "mutex winner did not complete installation"
[ "$mutex_second_succeeded" = false ] || fail "concurrent stale-lock recovery bypassed the acquisition mutex"
[ ! -e "$TMP/mutex-race-root/opt/var/lock/keenwg-lifecycle.lock" ] || fail "concurrent stale recovery left lifecycle lock"

cat >"$TMP/bin/hold-lock-owner" <<EOF
#!/bin/sh
: >"$TMP/owner-ready"
while [ ! -f "$TMP/owner-release" ]; do sleep 1; done
EOF
cat >"$TMP/bin/hold-generation-mutex" <<EOF
#!/bin/sh
: >"$TMP/generation-mutex-ready"
while [ ! -f "$TMP/generation-mutex-release" ]; do sleep 1; done
EOF
chmod 755 "$TMP/bin/hold-lock-owner" "$TMP/bin/hold-generation-mutex"
mkdir -p "$TMP/lock-proc/905" "$TMP/generation-race-root"
printf '%s\n' '905 (owner) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 0 0 0 0 555 0' >"$TMP/lock-proc/905/stat"
printf '%s\n' live >"$TMP/lock-proc/905/alive"
PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/generation-race-root" KEENWG_LOCK_SELF_PID=905 KEENWG_TEST_LOCK_ACQUIRED_HOOK="$TMP/bin/hold-lock-owner" \
    sh "$TMP/bundle/install.sh" >"$TMP/generation-owner.log" 2>&1 &
generation_owner_pid=$!
owner_wait=0
while [ ! -f "$TMP/owner-ready" ] && [ "$owner_wait" -lt 10 ]; do sleep 1; owner_wait=$((owner_wait + 1)); done
if [ ! -f "$TMP/owner-ready" ]; then
    : >"$TMP/owner-release"
    wait "$generation_owner_pid" 2>/dev/null || true
    fail "generation owner did not acquire the main lock"
fi
PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/generation-race-root" KEENWG_TEST_LOCK_MUTEX_HOOK="$TMP/bin/hold-generation-mutex" \
    sh "$TMP/bundle/install.sh" >"$TMP/generation-contender.log" 2>&1 &
generation_contender_pid=$!
generation_wait=0
while [ ! -f "$TMP/generation-mutex-ready" ] && [ "$generation_wait" -lt 10 ]; do sleep 1; generation_wait=$((generation_wait + 1)); done
if [ ! -f "$TMP/generation-mutex-ready" ]; then
    : >"$TMP/owner-release"; : >"$TMP/generation-mutex-release"
    wait "$generation_owner_pid" 2>/dev/null || true; wait "$generation_contender_pid" 2>/dev/null || true
    fail "generation contender did not acquire the sibling mutex"
fi
: >"$TMP/owner-release"
if wait "$generation_owner_pid"; then generation_owner_succeeded=true; else generation_owner_succeeded=false; fi
if [ "$generation_owner_succeeded" != true ]; then
    : >"$TMP/generation-mutex-release"; wait "$generation_contender_pid" 2>/dev/null || true
    fail "main-lock owner failed while contender held sibling mutex"
fi
[ ! -e "$TMP/generation-race-root/opt/var/lock/keenwg-lifecycle.lock" ] || fail "main-lock owner could not release its generation"
grep -qx '901 111 keenwg-test-boot' "$TMP/generation-race-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner" || fail "contender mutex disappeared with the released generation"
rm -rf "$TMP/lock-proc/905"
: >"$TMP/generation-mutex-release"
wait "$generation_contender_pid" || fail "contender did not publish a new generation after owner release"
[ ! -e "$TMP/generation-race-root/opt/var/lock/keenwg-lifecycle.lock" ] || fail "generation handoff left lifecycle lock"

mkdir -p "$TMP/locked-uninstall-root/opt/var/lib/keenwg"
PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/locked-uninstall-root" sh "$TMP/bundle/install.sh" >/dev/null
if KEENWG_DESTDIR="$TMP/locked-uninstall-root" KEENWG_TEST_LOCK_OWNER_WRITE_FAIL=1 sh "$TMP/bundle/uninstall.sh" >/dev/null 2>&1; then
    fail "uninstaller continued after lifecycle owner publication failed"
fi
assert_file "$TMP/locked-uninstall-root/opt/keenwg/current/keenwg-collector"
[ ! -e "$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock" ] || fail "failed uninstall owner publication left a fixed lifecycle lock"
if KEENWG_DESTDIR="$TMP/locked-uninstall-root" KEENWG_TEST_LOCK_MUTEX_OWNER_WRITE_FAIL=1 \
    sh "$TMP/bundle/uninstall.sh" >"$TMP/uninstall-mutex-owner-write-fail.log" 2>&1; then
    fail "uninstaller continued after lifecycle mutex owner publication failed"
fi
grep -q 'lifecycle acquisition mutex is unsafe' "$TMP/uninstall-mutex-owner-write-fail.log" || fail "uninstaller did not report mutex owner publication failure safely"
assert_file "$TMP/locked-uninstall-root/opt/keenwg/current/keenwg-collector"
[ ! -e "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot" ] || fail "failed uninstall mutex owner publication left an acquisition mutex"
[ ! -e "$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock" ] || fail "failed uninstall mutex owner publication left a fixed lifecycle lock"
mkdir -p "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot"
if KEENWG_DESTDIR="$TMP/locked-uninstall-root" sh "$TMP/bundle/uninstall.sh" >/dev/null 2>&1; then
    fail "uninstaller replaced a malformed lifecycle acquisition mutex"
fi
assert_file "$TMP/locked-uninstall-root/opt/keenwg/current/keenwg-collector"
[ ! -e "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner" ] || fail "uninstaller modified malformed mutex leaf"
rmdir "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot"
mkdir -p "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot"
printf '%s\n' '9:04 444 keenwg-test-boot' >"$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner"
uninstall_colon_mutex_hash=$(sha256sum "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner" | awk '{print $1}')
if KEENWG_DESTDIR="$TMP/locked-uninstall-root" sh "$TMP/bundle/uninstall.sh" >"$TMP/uninstall-colon-mutex.log" 2>&1; then
    fail "uninstaller accepted a colon-delimited lifecycle mutex identity"
fi
grep -q 'lifecycle acquisition mutex is unsafe' "$TMP/uninstall-colon-mutex.log" || fail "uninstaller did not classify colon-delimited lifecycle mutex as unsafe"
assert_file "$TMP/locked-uninstall-root/opt/keenwg/current/keenwg-collector"
[ "$(sha256sum "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner" | awk '{print $1}')" = "$uninstall_colon_mutex_hash" ] || fail "uninstaller changed colon-delimited lifecycle mutex owner"
rm -rf "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot"
mkdir -p "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot"
printf '%s\n' '901 111 keenwg-test-boot' 'trailing owner record' >"$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner"
uninstall_multiline_mutex_hash=$(sha256sum "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner" | awk '{print $1}')
if KEENWG_DESTDIR="$TMP/locked-uninstall-root" sh "$TMP/bundle/uninstall.sh" >"$TMP/uninstall-multiline-mutex.log" 2>&1; then
    fail "uninstaller accepted a multiline lifecycle mutex owner record"
fi
grep -q 'lifecycle acquisition mutex is unsafe' "$TMP/uninstall-multiline-mutex.log" || fail "uninstaller did not classify multiline lifecycle mutex as unsafe"
assert_file "$TMP/locked-uninstall-root/opt/keenwg/current/keenwg-collector"
[ "$(sha256sum "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot/owner" | awk '{print $1}')" = "$uninstall_multiline_mutex_hash" ] || fail "uninstaller changed multiline lifecycle mutex owner"
rm -rf "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot"
if $supports_symlinks; then
    mkdir -p "$TMP/uninstall-mutex-outside"
    printf '%s\n' uninstall-mutex-sentinel >"$TMP/uninstall-mutex-outside/sentinel"
    ln -s "$TMP/uninstall-mutex-outside" "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot"
    if KEENWG_DESTDIR="$TMP/locked-uninstall-root" sh "$TMP/bundle/uninstall.sh" >/dev/null 2>&1; then
        fail "uninstaller replaced a lifecycle acquisition mutex symlink"
    fi
    assert_file "$TMP/locked-uninstall-root/opt/keenwg/current/keenwg-collector"
    [ -L "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot" ] || fail "uninstaller replaced mutex symlink leaf"
    grep -q uninstall-mutex-sentinel "$TMP/uninstall-mutex-outside/sentinel" || fail "uninstaller changed mutex symlink target"
    rm "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-keenwg-test-boot"
fi
mkdir -p "$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock"
printf '%s\n' '9:02 222 keenwg-test-boot' >"$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock/owner"
uninstall_colon_lock_hash=$(sha256sum "$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock/owner" | awk '{print $1}')
if KEENWG_DESTDIR="$TMP/locked-uninstall-root" sh "$TMP/bundle/uninstall.sh" >"$TMP/uninstall-colon-lock.log" 2>&1; then
    fail "uninstaller accepted a colon-delimited lifecycle owner identity"
fi
grep -q 'lifecycle lock is unsafe' "$TMP/uninstall-colon-lock.log" || fail "uninstaller did not classify colon-delimited lifecycle owner as unsafe"
assert_file "$TMP/locked-uninstall-root/opt/keenwg/current/keenwg-collector"
[ "$(sha256sum "$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock/owner" | awk '{print $1}')" = "$uninstall_colon_lock_hash" ] || fail "uninstaller changed colon-delimited lifecycle owner"
rm -rf "$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock"
mkdir -p "$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock"
printf '%s\n' '902 222 keenwg-test-boot' 'trailing owner record' >"$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock/owner"
uninstall_multiline_lock_hash=$(sha256sum "$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock/owner" | awk '{print $1}')
if KEENWG_DESTDIR="$TMP/locked-uninstall-root" sh "$TMP/bundle/uninstall.sh" >"$TMP/uninstall-multiline-lock.log" 2>&1; then
    fail "uninstaller accepted a multiline lifecycle owner record"
fi
grep -q 'lifecycle lock is unsafe' "$TMP/uninstall-multiline-lock.log" || fail "uninstaller did not classify multiline lifecycle owner as unsafe"
assert_file "$TMP/locked-uninstall-root/opt/keenwg/current/keenwg-collector"
[ "$(sha256sum "$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock/owner" | awk '{print $1}')" = "$uninstall_multiline_lock_hash" ] || fail "uninstaller changed multiline lifecycle owner"
rm -rf "$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock"
mkdir -p "$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock"
printf '%s\n' '902 222 keenwg-test-boot' >"$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock/owner"
if KEENWG_DESTDIR="$TMP/locked-uninstall-root" sh "$TMP/bundle/uninstall.sh" >/dev/null 2>&1; then
    fail "uninstaller ignored an active lifecycle lock"
fi
assert_file "$TMP/locked-uninstall-root/opt/keenwg/current/keenwg-collector"
rm -rf "$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock"
rm -rf "$TMP/lock-proc/902"
mkdir -p "$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock"
printf '%s\n' '903 333 previous-boot' >"$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock/owner"
mkdir -p "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-previous-boot"
printf '%s\n' '903 333 previous-boot' >"$TMP/locked-uninstall-root/opt/var/lock/.keenwg-lifecycle-mutex-previous-boot/owner"
mkdir -p "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-candidate.orphan" "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-stale.orphan" "$TMP/locked-uninstall-root/opt/var/lock/.keenwg-release.orphan"
KEENWG_DESTDIR="$TMP/locked-uninstall-root" sh "$TMP/bundle/uninstall.sh" >/dev/null || fail "uninstaller did not recover a stale power-loss lock"
[ ! -e "$TMP/locked-uninstall-root/opt/var/lock/keenwg-lifecycle.lock" ] || fail "uninstaller left stale lifecycle lock after success"
[ ! -e "$TMP/locked-uninstall-root/opt/keenwg" ] || fail "stale lock recovery prevented uninstall"

if $supports_symlinks; then
    mkdir -p "$TMP/lock-link-root/opt/var/lock"
    printf '%s\n' lock-sentinel >"$TMP/lock-outside"
    ln -s "$TMP/lock-outside" "$TMP/lock-link-root/opt/var/lock/keenwg-lifecycle.lock"
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/lock-link-root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then
        fail "installer accepted a lifecycle lock symlink"
    fi
    grep -q lock-sentinel "$TMP/lock-outside" || fail "installer changed lifecycle lock symlink target"
    if KEENWG_DESTDIR="$TMP/lock-link-root" sh "$TMP/bundle/uninstall.sh" >/dev/null 2>&1; then
        fail "uninstaller accepted a lifecycle lock symlink"
    fi
    grep -q lock-sentinel "$TMP/lock-outside" || fail "uninstaller changed lifecycle lock symlink target"
fi

printf '%s\n' '{"preserved":true}' >"$TMP/root/opt/etc/keenwg/config.json"
printf '%s\n' preserved-db >"$TMP/root/opt/var/lib/keenwg/history.db"
PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/root" sh "$TMP/bundle/install.sh"
grep -q preserved "$TMP/root/opt/etc/keenwg/config.json" || fail "reinstall replaced config"
grep -q preserved "$TMP/root/opt/var/lib/keenwg/history.db" || fail "reinstall replaced database"

if $supports_symlinks; then
    rm "$TMP/root/opt/keenwg/current"; mkdir "$TMP/root/opt/keenwg/current"; printf '%s\n' marker >"$TMP/root/opt/keenwg/current/marker"
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then fail "install accepted directory current"; fi
    grep -q marker "$TMP/root/opt/keenwg/current/marker" || fail "invalid current directory was changed"
    rm -rf "$TMP/root/opt/keenwg/current"; ln -s "releases/$RELEASE_ID" "$TMP/root/opt/keenwg/current"
    rm "$TMP/root/opt/keenwg/current"; ln -s "$TMP/outside" "$TMP/root/opt/keenwg/current"
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then fail "install accepted arbitrary current symlink"; fi
    rm "$TMP/root/opt/keenwg/current"; ln -s "releases/$RELEASE_ID" "$TMP/root/opt/keenwg/current"
fi

if $supports_symlinks; then
    old_target=$(readlink "$TMP/root/opt/keenwg/current")
    old_binary_hash=$(sha256sum "$TMP/root/opt/keenwg/current/keenwg-collector" | awk '{print $1}')
    printf '%s\n' '# immutable update' >>"$TMP/bundle/keenwg-collector"
    (cd "$TMP/bundle" && sha256sum keenwg-collector >SHA256SUMS)
    UPDATED_HASH=$(sha256sum "$TMP/bundle/keenwg-collector" | awk '{print $1}')
    CHECK_CURRENT="$TMP/root/opt/keenwg/current" CHECK_EXPECTED="$old_target" PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/root" sh "$TMP/bundle/install.sh"
    updated_target=$(readlink "$TMP/root/opt/keenwg/current")
    [ "$updated_target" = "releases/0.3.0-$UPDATED_HASH" ] || fail "current did not switch to content-addressed release"
    [ "$updated_target" != "$old_target" ] || fail "same-version update reused mutable release"
    [ "$(sha256sum "$TMP/root/opt/keenwg/$old_target/keenwg-collector" | awk '{print $1}')" = "$old_binary_hash" ] || fail "old running release was overwritten"
    [ -z "$(find "$TMP/root/opt/keenwg/$old_target" -maxdepth 1 -name '.current.*' -print -quit)" ] || fail "atomic switch moved temporary current link into old release"

    cat >"$TMP/bin/coexisting-service" <<EOF
#!/bin/sh
count=0; [ -r "$TMP/coexisting-service-count" ] && count=\$(cat "$TMP/coexisting-service-count")
count=\$((count+1)); printf '%s\n' "\$count" >"$TMP/coexisting-service-count"
rm -rf "$TMP/coexisting-proc/411" "$TMP/coexisting-proc/412"
mkdir -p "$TMP/coexisting-proc/412"
printf '%s\n' 412 >"$TMP/coexisting.pid"
printf '%s\n' '412 888' >"$TMP/coexisting.pid.start"
printf '%s\n' '412 (keenwg) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 0 0 0 0 888 0' >"$TMP/coexisting-proc/412/stat"
ln -s "\$(readlink -f "$TMP/root/opt/keenwg/current/keenwg-collector")" "$TMP/coexisting-proc/412/exe"
if [ "\$count" = 1 ]; then
    mkdir -p "$TMP/coexisting-proc/411"
    printf '%s\n' '411 (keenwg) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 0 0 0 0 777 0' >"$TMP/coexisting-proc/411/stat"
    ln -s "$(readlink -f "$TMP/root/opt/keenwg/$old_target/keenwg-collector")" "$TMP/coexisting-proc/411/exe"
fi
exit 0
EOF
    chmod 755 "$TMP/bin/coexisting-service"
    printf '%s\n' '# coexisting same-version candidate' >>"$TMP/bundle/keenwg-collector"
    (cd "$TMP/bundle" && sha256sum keenwg-collector >SHA256SUMS)
    before_coexisting=$(readlink "$TMP/root/opt/keenwg/current")
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/root" KEENWG_TEST_LIVE=1 \
        KEENWG_TEST_SERVICE="$TMP/bin/coexisting-service" KEENWG_TEST_HEALTH="$TMP/bin/correct-health" \
        KEENWG_PID_FILE="$TMP/coexisting.pid" KEENWG_IDENTITY_FILE="$TMP/coexisting.pid.start" KEENWG_PROC_ROOT="$TMP/coexisting-proc" KEENWG_PROCESS_ALIVE="$TMP/bin/proof-alive" \
        KEENWG_COLLECTOR_PREFIX="$TMP/root/opt/keenwg" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then
        fail "same-version update accepted an untracked old collector beside the candidate"
    fi
    [ "$(readlink "$TMP/root/opt/keenwg/current")" = "$before_coexisting" ] || fail "coexisting collector rejection did not restore old target"
    [ "$(cat "$TMP/coexisting-service-count")" = 2 ] || fail "coexisting collector rollback did not restart the old release"

    before=$updated_target
    running_hash=$(sha256sum "$TMP/root/opt/keenwg/current/keenwg-collector")
    printf '%s\n' '# failing immutable update' >>"$TMP/bundle/keenwg-collector"
    (cd "$TMP/bundle" && sha256sum keenwg-collector >SHA256SUMS)
else
    before=$(sha256sum "$TMP/root/opt/keenwg/current/keenwg-collector")
fi
if FAIL_CHECK=1 PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then fail "failed self-check install succeeded"; fi
if $supports_symlinks; then rolled_back=$(readlink "$TMP/root/opt/keenwg/current"); else rolled_back=$(sha256sum "$TMP/root/opt/keenwg/current/keenwg-collector"); fi
[ "$rolled_back" = "$before" ] || fail "self-check failure did not restore current"
[ ! "$supports_symlinks" = true ] || [ "$(sha256sum "$TMP/root/opt/keenwg/current/keenwg-collector")" = "$running_hash" ] || fail "failed same-version update changed running binary"

if $supports_symlinks; then
    for managed in \
        "$TMP/root/opt/etc/init.d/S95keenwg" \
        "$TMP/root/opt/etc/ndm/ifcreated.d/95-keenwg" \
        "$TMP/root/opt/etc/ndm/ifdestroyed.d/95-keenwg" \
        "$TMP/root/opt/etc/ndm/ifipchanged.d/95-keenwg"
    do
        printf '%s\n' '# old-lifecycle-marker' >>"$managed"
    done
    cat >"$TMP/bin/fail-once-service" <<EOF
#!/bin/sh
count=0; [ -r "$TMP/service-count" ] && count=\$(cat "$TMP/service-count")
count=\$((count+1)); printf '%s\n' "\$count" >"$TMP/service-count"
[ "\$count" -gt 1 ]
EOF
    cat >"$TMP/bin/correct-health" <<EOF
#!/bin/sh
printf '%s\n' "\$1" >>"$TMP/rollback-health.log"
printf '{\"status\":\"ok\",\"version\":\"%s\"}\n' "\$1"
EOF
    chmod 755 "$TMP/bin/fail-once-service" "$TMP/bin/correct-health"
    printf '%s\n' '# restart failure candidate' >>"$TMP/bundle/keenwg-collector"
    (cd "$TMP/bundle" && sha256sum keenwg-collector >SHA256SUMS)
    before=$(readlink "$TMP/root/opt/keenwg/current")
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/root" KEENWG_TEST_SERVICE="$TMP/bin/fail-once-service" KEENWG_TEST_HEALTH="$TMP/bin/correct-health" \
        KEENWG_TEST_RESTORE_MODE_FAIL_ONCE_KEY=ifdestroyed KEENWG_TEST_RESTORE_MODE_STATE="$TMP/restore-failed-once" \
        sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then fail "restart failure update succeeded"; fi
    [ "$(readlink "$TMP/root/opt/keenwg/current")" = "$before" ] || fail "restart failure did not restore old target"
    [ "$(cat "$TMP/service-count")" = 2 ] || fail "old release was not restarted after rollback"
    [ "$(wc -l <"$TMP/rollback-health.log")" = 1 ] || fail "restart rollback did not health-check old release"
    [ -f "$TMP/restore-failed-once" ] || fail "rollback fault injection did not run"
    for managed in \
        "$TMP/root/opt/etc/init.d/S95keenwg" \
        "$TMP/root/opt/etc/ndm/ifcreated.d/95-keenwg" \
        "$TMP/root/opt/etc/ndm/ifdestroyed.d/95-keenwg" \
        "$TMP/root/opt/etc/ndm/ifipchanged.d/95-keenwg"
    do
        grep -q '^# old-lifecycle-marker$' "$managed" || fail "restart rollback did not restore $managed"
    done

    cat >"$TMP/bin/signal-parent" <<'EOF'
#!/bin/sh
kill -TERM "$PPID"
EOF
    chmod 755 "$TMP/bin/signal-parent"
    printf '%s\n' '# signal rollback candidate' >>"$TMP/bundle/keenwg-collector"
    (cd "$TMP/bundle" && sha256sum keenwg-collector >SHA256SUMS)
    before=$(readlink "$TMP/root/opt/keenwg/current")
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/root" KEENWG_TEST_AFTER_SWITCH="$TMP/bin/signal-parent" \
        sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then
        fail "installer ignored termination after current switch"
    fi
    [ "$(readlink "$TMP/root/opt/keenwg/current")" = "$before" ] || fail "termination after switch did not restore old target"
    for managed in \
        "$TMP/root/opt/etc/init.d/S95keenwg" \
        "$TMP/root/opt/etc/ndm/ifcreated.d/95-keenwg" \
        "$TMP/root/opt/etc/ndm/ifdestroyed.d/95-keenwg" \
        "$TMP/root/opt/etc/ndm/ifipchanged.d/95-keenwg"
    do
        grep -q '^# old-lifecycle-marker$' "$managed" || fail "termination after switch did not restore $managed"
    done

    cp "$TMP/bundle/S95keenwg" "$TMP/original-S95keenwg"
    cat >"$TMP/root/opt/etc/init.d/S95keenwg" <<'EOF'
#!/bin/sh
# broken-init-old-marker
printf '%s\n' "$1" >>"$BROKEN_INIT_LOG"
exec "$BROKEN_ROLLBACK_SERVICE" "$@"
EOF
    chmod 755 "$TMP/root/opt/etc/init.d/S95keenwg"
    cat >"$TMP/bundle/S95keenwg" <<'EOF'
#!/bin/sh
exit 1
EOF
    chmod 755 "$TMP/bundle/S95keenwg"
    printf '%s\n' '# broken init candidate' >>"$TMP/bundle/keenwg-collector"
    (cd "$TMP/bundle" && sha256sum keenwg-collector >SHA256SUMS)
    rm -rf "$TMP/broken-init-proc"
    mkdir -p "$TMP/broken-init-proc"
    before=$(readlink "$TMP/root/opt/keenwg/current")
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/root" KEENWG_TEST_LIVE=1 KEENWG_TEST_HEALTH="$TMP/bin/correct-health" \
        KEENWG_PID_FILE="$TMP/broken-init.pid" KEENWG_IDENTITY_FILE="$TMP/broken-init.pid.start" KEENWG_PROC_ROOT="$TMP/broken-init-proc" KEENWG_PROCESS_ALIVE="$TMP/bin/proof-alive" \
        KEENWG_COLLECTOR_PREFIX="$TMP/root/opt/keenwg" PROOF_PID_FILE="$TMP/broken-init.pid" PROOF_IDENTITY_FILE="$TMP/broken-init.pid.start" PROOF_PROC_ROOT="$TMP/broken-init-proc" PROOF_CURRENT="$TMP/root/opt/keenwg/current" \
        BROKEN_INIT_LOG="$TMP/broken-init.log" BROKEN_ROLLBACK_SERVICE="$TMP/bin/track-current-service" \
        sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then
        fail "update with a broken candidate init script succeeded"
    fi
    [ "$(readlink "$TMP/root/opt/keenwg/current")" = "$before" ] || fail "broken candidate init did not restore old target"
    grep -q '^# broken-init-old-marker$' "$TMP/root/opt/etc/init.d/S95keenwg" || fail "broken candidate init did not restore old init script"
    [ "$(cat "$TMP/broken-init.log")" = restart ] || fail "restored init script did not restart the old release"

    mkdir -p "$TMP/fresh-broken-init-root" "$TMP/fresh-broken-init-proc"
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/fresh-broken-init-root" KEENWG_TEST_LIVE=1 \
        KEENWG_PROC_ROOT="$TMP/fresh-broken-init-proc" KEENWG_COLLECTOR_PREFIX="$TMP/fresh-broken-init-root/opt/keenwg" \
        sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then
        fail "fresh install with a broken candidate init script succeeded"
    fi
    [ ! -e "$TMP/fresh-broken-init-root/opt/keenwg/current" ] && [ ! -L "$TMP/fresh-broken-init-root/opt/keenwg/current" ] || fail "fresh broken init left candidate current"
    [ ! -e "$TMP/fresh-broken-init-root/opt/etc/init.d/S95keenwg" ] || fail "fresh broken init left candidate init script"
    for hook in ifcreated.d ifdestroyed.d ifipchanged.d; do
        [ ! -e "$TMP/fresh-broken-init-root/opt/etc/ndm/$hook/95-keenwg" ] || fail "fresh broken init left candidate hook"
    done

    fresh_broken_hash=$(sha256sum "$TMP/bundle/keenwg-collector" | awk '{print $1}')
    mkdir -p "$TMP/fresh-broken-live-root" "$TMP/fresh-broken-live-proc/777"
    ln -s "$TMP/fresh-broken-live-root/opt/keenwg/releases/0.3.0-$fresh_broken_hash/keenwg-collector" "$TMP/fresh-broken-live-proc/777/exe"
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/fresh-broken-live-root" KEENWG_TEST_LIVE=1 \
        KEENWG_PROC_ROOT="$TMP/fresh-broken-live-proc" KEENWG_COLLECTOR_PREFIX="$TMP/fresh-broken-live-root/opt/keenwg" \
        sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then
        fail "fresh broken init with an uncertain live collector succeeded"
    fi
    assert_file "$TMP/fresh-broken-live-root/opt/keenwg/current/keenwg-collector"
    assert_file "$TMP/fresh-broken-live-root/opt/etc/init.d/S95keenwg"
    mv "$TMP/original-S95keenwg" "$TMP/bundle/S95keenwg"

    cat >"$TMP/bin/success-service" <<EOF
#!/bin/sh
count=0; [ -r "$TMP/health-service-count" ] && count=\$(cat "$TMP/health-service-count")
count=\$((count+1)); printf '%s\n' "\$count" >"$TMP/health-service-count"
exit 0
EOF
    cat >"$TMP/bin/wrong-version-health" <<EOF
#!/bin/sh
count=0; [ -r "$TMP/wrong-health-count" ] && count=\$(cat "$TMP/wrong-health-count")
count=\$((count+1)); printf '%s\n' "\$count" >"$TMP/wrong-health-count"
if [ "\$count" = 1 ]; then
    printf '%s\n' '{"status":"ok","version":"wrong"}'
else
    printf '{\"status\":\"ok\",\"version\":\"%s\"}\n' "\$1"
fi
EOF
    chmod 755 "$TMP/bin/success-service" "$TMP/bin/wrong-version-health"
    printf '%s\n' '# health failure candidate' >>"$TMP/bundle/keenwg-collector"
    (cd "$TMP/bundle" && sha256sum keenwg-collector >SHA256SUMS)
    before=$(readlink "$TMP/root/opt/keenwg/current")
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/root" KEENWG_TEST_SERVICE="$TMP/bin/success-service" KEENWG_TEST_HEALTH="$TMP/bin/wrong-version-health" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then fail "wrong-version health update succeeded"; fi
    [ "$(readlink "$TMP/root/opt/keenwg/current")" = "$before" ] || fail "health failure did not restore old target"
    [ "$(cat "$TMP/health-service-count")" = 2 ] || fail "health rollback did not restart old release"
    [ "$(cat "$TMP/wrong-health-count")" = 2 ] || fail "health rollback did not verify old process"
fi

printf '%s  %s\n' "$(printf bad | sha256sum | awk '{print $1}')" keenwg-collector >"$TMP/bundle/SHA256SUMS"
if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then fail "checksum mismatch succeeded"; fi
if $supports_symlinks; then after=$(readlink "$TMP/root/opt/keenwg/current"); else after=$(sha256sum "$TMP/root/opt/keenwg/current/keenwg-collector"); fi
[ "$after" = "$before" ] || fail "checksum failure changed current"
(cd "$TMP/bundle" && sha256sum keenwg-collector >SHA256SUMS)

mkdir -p "$TMP/blocked-purge-root/opt/var/lib/keenwg"
PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/blocked-purge-root" sh "$TMP/bundle/install.sh" >/dev/null
printf '%s\n' keep-me >"$TMP/blocked-purge-root/opt/var/lib/keenwg/history.db"
cat >"$TMP/blocked-purge-root/opt/etc/init.d/S95keenwg" <<'EOF'
#!/bin/sh
exit 1
EOF
chmod 755 "$TMP/blocked-purge-root/opt/etc/init.d/S95keenwg"
if KEENWG_DESTDIR="$TMP/blocked-purge-root" KEENWG_TEST_LIVE=1 sh "$TMP/bundle/uninstall.sh" --purge >/dev/null 2>&1; then fail "purge continued after collector stop failed"; fi
grep -q keep-me "$TMP/blocked-purge-root/opt/var/lib/keenwg/history.db" || fail "failed stop allowed database purge"
assert_file "$TMP/blocked-purge-root/opt/keenwg/current/keenwg-collector"

if $supports_symlinks; then
    mkdir -p "$TMP/missing-init-root/opt/var/lib/keenwg" "$TMP/uninstall-proc/321"
    PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/missing-init-root" sh "$TMP/bundle/install.sh" >/dev/null
    printf '%s\n' keep-live >"$TMP/missing-init-root/opt/var/lib/keenwg/history.db"
    rm -f "$TMP/missing-init-root/opt/etc/init.d/S95keenwg"
    ln -s /opt/keenwg/releases/0.3.0-live/keenwg-collector "$TMP/uninstall-proc/321/exe"
    if KEENWG_DESTDIR="$TMP/missing-init-root" KEENWG_TEST_LIVE=1 KEENWG_PROC_ROOT="$TMP/uninstall-proc" \
        sh "$TMP/bundle/uninstall.sh" --purge >/dev/null 2>&1; then
        fail "purge succeeded with a live collector and missing init script"
    fi
    grep -q keep-live "$TMP/missing-init-root/opt/var/lib/keenwg/history.db" || fail "missing init allowed live collector database purge"
    assert_file "$TMP/missing-init-root/opt/keenwg/current/keenwg-collector"

    mkdir -p "$TMP/missing-pid-root/opt/var/lib/keenwg"
    PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/missing-pid-root" sh "$TMP/bundle/install.sh" >/dev/null
    printf '%s\n' keep-live >"$TMP/missing-pid-root/opt/var/lib/keenwg/history.db"
    cat >"$TMP/missing-pid-root/opt/etc/init.d/S95keenwg" <<'EOF'
#!/bin/sh
exit 0
EOF
    chmod 755 "$TMP/missing-pid-root/opt/etc/init.d/S95keenwg"
    if KEENWG_DESTDIR="$TMP/missing-pid-root" KEENWG_TEST_LIVE=1 KEENWG_PROC_ROOT="$TMP/uninstall-proc" \
        sh "$TMP/bundle/uninstall.sh" --purge >/dev/null 2>&1; then
        fail "purge trusted a successful stop while a collector executable remained live"
    fi
    grep -q keep-live "$TMP/missing-pid-root/opt/var/lib/keenwg/history.db" || fail "missing PID allowed live collector database purge"

    rm -f "$TMP/uninstall-proc/321/exe"
    ln -s /bin/sh "$TMP/uninstall-proc/321/exe"
    KEENWG_DESTDIR="$TMP/missing-init-root" KEENWG_TEST_LIVE=1 KEENWG_PROC_ROOT="$TMP/uninstall-proc" \
        sh "$TMP/bundle/uninstall.sh" --purge >/dev/null || fail "unrelated live executable blocked uninstall"
fi

KEENWG_DESTDIR="$TMP/root" sh "$TMP/bundle/uninstall.sh"
assert_file "$TMP/root/opt/etc/keenwg/config.json"
assert_file "$TMP/root/opt/var/lib/keenwg/history.db"

PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/root" sh "$TMP/bundle/install.sh"
KEENWG_DESTDIR="$TMP/root" sh "$TMP/bundle/uninstall.sh" --purge
[ ! -e "$TMP/root/opt/keenwg" ] || fail "purge left release tree"
[ ! -e "$TMP/root/opt/etc/keenwg" ] || fail "purge left config tree"
[ ! -e "$TMP/root/opt/var/lib/keenwg" ] || fail "purge left data tree"
[ -d "$TMP/root/opt" ] || fail "purge escaped staged /opt"
mkdir -p "$TMP/unknown-root/opt"
if KEENWG_DESTDIR="$TMP/unknown-root" sh "$TMP/bundle/uninstall.sh" --unexpected >/dev/null 2>&1; then fail "uninstall accepted unknown argument"; fi

mkdir -p "$TMP/dotdot/root" "$TMP/escape-root/opt" "$TMP/outside"
if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/dotdot/../dotdot/root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then fail "non-canonical staging root succeeded"; fi
if KEENWG_DESTDIR=/ sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then fail "live root accepted as staging root"; fi
if $supports_symlinks; then
    printf '%s\n' sentinel >"$TMP/outside/sentinel"
    ln -s "$TMP/outside" "$TMP/escape-root/opt/keenwg"
    if PATH="$TMP/bin:$PATH" KEENWG_DESTDIR="$TMP/escape-root" sh "$TMP/bundle/install.sh" >/dev/null 2>&1; then fail "install followed escaping symlink"; fi
    grep -q sentinel "$TMP/outside/sentinel" || fail "install changed outside sentinel"

    mkdir -p "$TMP/uninstall-root/opt/etc" "$TMP/uninstall-root/opt/var/lib"
    ln -s "$TMP/outside" "$TMP/uninstall-root/opt/keenwg"
    if KEENWG_DESTDIR="$TMP/uninstall-root" sh "$TMP/bundle/uninstall.sh" --purge >/dev/null 2>&1; then fail "uninstall accepted escaping symlink"; fi
    grep -q sentinel "$TMP/outside/sentinel" || fail "uninstall changed outside sentinel"

    mkdir -p "$TMP/proc/123"
    printf '%s\n' 123 >"$TMP/test.pid"
    cat >"$TMP/bin/fake-kill" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >>"$TMP/kill.log"
EOF
    chmod 755 "$TMP/bin/fake-kill"
    cat >"$TMP/bin/fake-alive" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >>"$TMP/alive.log"
exit 1
EOF
    cat >"$TMP/bin/fake-sleep" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >>"$TMP/sleep.log"
exit 0
EOF
    chmod 755 "$TMP/bin/fake-alive" "$TMP/bin/fake-sleep"
    cat >"$TMP/bin/fake-init" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >>"$TMP/init.log"
exit 0
EOF
    chmod 755 "$TMP/bin/fake-init"
    ln -s /bin/sh "$TMP/proc/123/exe"
    KEENWG_PROC_ROOT="$TMP/proc" KEENWG_PID_FILE="$TMP/test.pid" KEENWG_KILL="$TMP/bin/fake-kill" KEENWG_INIT="$TMP/bin/fake-init" sh "$TMP/bundle/95-keenwg-signal"
    [ ! -e "$TMP/kill.log" ] || fail "hook signaled stale reused PID"
    grep -q '^start$' "$TMP/init.log" || fail "hook did not start collector for stale PID"
    rm -f "$TMP/test.pid" "$TMP/init.log"
    KEENWG_PROC_ROOT="$TMP/proc" KEENWG_PID_FILE="$TMP/test.pid" KEENWG_KILL="$TMP/bin/fake-kill" KEENWG_INIT="$TMP/bin/fake-init" sh "$TMP/bundle/95-keenwg-signal"
    grep -q '^start$' "$TMP/init.log" || fail "hook did not start collector for missing PID"
    cat >"$TMP/bin/start-stop-daemon" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >>"$TMP/ssd.log"
EOF
    chmod 755 "$TMP/bin/start-stop-daemon"
    printf '%s\n' 123 >"$TMP/test.pid"
    KEENWG_PATH="$TMP/bin:$PATH" KEENWG_PROC_ROOT="$TMP/proc" KEENWG_PID_FILE="$TMP/test.pid" KEENWG_KILL="$TMP/bin/fake-kill" KEENWG_ALIVE="$TMP/bin/fake-alive" KEENWG_SLEEP="$TMP/bin/fake-sleep" sh "$TMP/bundle/S95keenwg" stop
    [ ! -e "$TMP/ssd.log" ] || fail "init script signaled stale reused PID"
    cat >"$TMP/bin/readlink" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >>"$TMP/readlink.log"
printf '%s\n' /opt/keenwg/releases/0.3.0/keenwg-collector
EOF
    chmod 755 "$TMP/bin/readlink"
    printf '%s\n' '../../outside' >"$TMP/test.pid"
    KEENWG_PATH="$TMP/bin:$PATH" KEENWG_PROC_ROOT="$TMP/proc" KEENWG_PID_FILE="$TMP/test.pid" KEENWG_KILL="$TMP/bin/fake-kill" KEENWG_ALIVE="$TMP/bin/fake-alive" KEENWG_SLEEP="$TMP/bin/fake-sleep" sh "$TMP/bundle/S95keenwg" stop
    [ ! -e "$TMP/readlink.log" ] || fail "init resolved /proc path before validating numeric PID"
    cat >"$TMP/bin/alive-once" <<EOF
#!/bin/sh
count=0; [ -r "$TMP/alive-once-count" ] && count=\$(cat "$TMP/alive-once-count")
count=\$((count+1)); printf '%s\n' "\$count" >"$TMP/alive-once-count"
[ "\$count" = 1 ]
EOF
    chmod 755 "$TMP/bin/alive-once"
    printf '%s\n' 123 >"$TMP/test.pid"; rm -f "$TMP/ssd.log"
    printf '%s\n' '123 (keenwg) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 0 0 0 0 777 0' >"$TMP/proc/123/stat"
    printf '%s\n' '123 777' >"$TMP/test.pid.start"
    KEENWG_PATH="$TMP/bin:$PATH" KEENWG_PROC_ROOT="$TMP/proc" KEENWG_PID_FILE="$TMP/test.pid" KEENWG_KILL="$TMP/bin/fake-kill" KEENWG_ALIVE="$TMP/bin/alive-once" KEENWG_SLEEP="$TMP/bin/fake-sleep" sh "$TMP/bundle/S95keenwg" stop
    grep -q -- '-x /opt/keenwg/releases/0.3.0/keenwg-collector' "$TMP/ssd.log" || fail "init did not match verified executable"
    [ ! -e "$TMP/kill.log" ] || ! grep -q -- '-KILL 123' "$TMP/kill.log" || fail "init force-killed a PID reported dead by injected liveness probe"

    cat >"$TMP/bin/readlink" <<EOF
#!/bin/sh
printf '%s\n' '/opt/keenwg/releases/0.3.0/keenwg-collector (deleted)'
EOF
    chmod 755 "$TMP/bin/readlink" "$TMP/bin/alive-once"
    printf '%s\n' 123 >"$TMP/test.pid"; printf '%s\n' '123 777' >"$TMP/test.pid.start"; rm -f "$TMP/kill.log" "$TMP/alive-once-count"
    KEENWG_PATH="$TMP/bin:$PATH" KEENWG_PROC_ROOT="$TMP/proc" KEENWG_PID_FILE="$TMP/test.pid" KEENWG_KILL="$TMP/bin/fake-kill" KEENWG_ALIVE="$TMP/bin/alive-once" KEENWG_SLEEP="$TMP/bin/fake-sleep" sh "$TMP/bundle/S95keenwg" stop
    grep -q -- '-TERM 123' "$TMP/kill.log" || fail "init could not safely stop a deleted collector executable"

    cat >"$TMP/bin/readlink" <<EOF
#!/bin/sh
printf '%s\n' /opt/keenwg/releases/0.3.0/keenwg-collector
EOF
    cat >"$TMP/bin/reuse-start-stop-daemon" <<EOF
#!/bin/sh
printf '%s\n' '123 (keenwg) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 0 0 0 0 888 0' >"$TMP/proc/123/stat"
exit 0
EOF
    cat >"$TMP/bin/always-alive" <<'EOF'
#!/bin/sh
exit 0
EOF
    chmod 755 "$TMP/bin/readlink" "$TMP/bin/reuse-start-stop-daemon" "$TMP/bin/always-alive"
    printf '%s\n' 123 >"$TMP/test.pid"; printf '%s\n' '123 777' >"$TMP/test.pid.start"; printf '%s\n' '123 (keenwg) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 0 0 0 0 777 0' >"$TMP/proc/123/stat"; rm -f "$TMP/kill.log"
    if KEENWG_PATH="$TMP/bin:$PATH" KEENWG_SSD="$TMP/bin/reuse-start-stop-daemon" KEENWG_PROC_ROOT="$TMP/proc" KEENWG_PID_FILE="$TMP/test.pid" KEENWG_KILL="$TMP/bin/fake-kill" KEENWG_ALIVE="$TMP/bin/always-alive" KEENWG_SLEEP="$TMP/bin/fake-sleep" sh "$TMP/bundle/S95keenwg" stop; then
        fail "init reported success while a reused PID still ran a collector executable"
    fi
    assert_file "$TMP/test.pid"
    assert_file "$TMP/test.pid.start"
    [ ! -e "$TMP/kill.log" ] || fail "init signaled a reused PID"

    cat >"$TMP/bin/fail-kill" <<EOF
#!/bin/sh
printf '%s\n' "\$*" >>"$TMP/fail-kill.log"
exit 1
EOF
    chmod 755 "$TMP/bin/fail-kill"
    printf '%s\n' 123 >"$TMP/test.pid"; printf '%s\n' '123 777' >"$TMP/test.pid.start"; printf '%s\n' '123 (keenwg) S 1 1 1 0 -1 0 0 0 0 0 0 0 0 0 0 0 0 0 777 0' >"$TMP/proc/123/stat"
    if KEENWG_PATH="$TMP/bin:$PATH" KEENWG_PROC_ROOT="$TMP/proc" KEENWG_PID_FILE="$TMP/test.pid" KEENWG_KILL="$TMP/bin/fail-kill" KEENWG_ALIVE="$TMP/bin/always-alive" KEENWG_SLEEP="$TMP/bin/fake-sleep" sh "$TMP/bundle/S95keenwg" stop >/dev/null 2>&1; then fail "init reported success when collector could not be killed"; fi
    assert_file "$TMP/test.pid"
    assert_file "$TMP/test.pid.start"

    rm "$TMP/proc/123/exe"; ln -s /opt/keenwg/releases/0.3.0/keenwg-collector "$TMP/proc/123/exe"
    printf '%s\n' 123 >"$TMP/test.pid"; printf '%s\n' '123 777' >"$TMP/test.pid.start"; rm -f "$TMP/init.log"
    KEENWG_PROC_ROOT="$TMP/proc" KEENWG_PID_FILE="$TMP/test.pid" KEENWG_KILL="$TMP/bin/fake-kill" KEENWG_INIT="$TMP/bin/fake-init" sh "$TMP/bundle/95-keenwg-signal"
    grep -q -- '-HUP 123' "$TMP/kill.log" || fail "hook did not signal owned collector PID"
    [ ! -e "$TMP/init.log" ] || fail "hook restarted collector after successful reload signal"
fi

echo "PASS: staged Entware install lifecycle"
