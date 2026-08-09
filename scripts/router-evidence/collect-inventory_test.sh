#!/bin/sh
set -eu

root="${TMPDIR:-/tmp}/keenwg-inventory-test-$$"
trap 'rm -rf "$root"' EXIT INT TERM
mkdir -p "$root/bin"

cat > "$root/bin/uname" <<'EOF'
#!/bin/sh
printf 'aarch64\n'
EOF
cat > "$root/bin/ndmc" <<'EOF'
#!/bin/sh
printf 'Model: Hopper SE (NC-3812)\nRelease: 5.01.C.1.0-0\n'
EOF
cat > "$root/bin/opkg" <<'EOF'
#!/bin/sh
if [ "$1" = list-installed ]; then
    printf 'xkeen - 2.30\nxray - 26.3.27\n'
fi
EOF
cat > "$root/bin/xkeen" <<'EOF'
#!/bin/sh
printf 'this noisy fallback must not run\n'
exit 1
EOF
cat > "$root/bin/xray" <<'EOF'
#!/bin/sh
printf 'this noisy fallback must not run\n'
exit 1
EOF
chmod +x "$root/bin/"*

PATH="$root/bin:$PATH" sh "$(dirname "$0")/collect-inventory.sh" > "$root/actual"
cat > "$root/expected" <<'EOF'
model=Hopper SE (NC-3812)
keenetic_os=5.01.C.1.0-0
architecture=arm64
entware=present
xkeen=xkeen 2.30
xray=xray 26.3.27
sing_box=absent
awg_manager=absent
companion=absent
EOF

cmp "$root/expected" "$root/actual"
printf 'collect-inventory tests passed\n'
