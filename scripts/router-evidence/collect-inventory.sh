#!/bin/sh
set -eu

# Read-only inventory collector. It never reads configuration, keys, tokens,
# addresses, interface state, hostnames, or subscription data.
safe() {
    printf '%s' "$1" | tr '\r\n' '  ' | sed 's/[^A-Za-z0-9А-Яа-яЁё._() /-]/_/g' | cut -c1-80
}

architecture="$(uname -m 2>/dev/null || printf unknown)"
case "$architecture" in
    aarch64|arm64) architecture=arm64 ;;
    mips) architecture=mips ;;
    mipsel) architecture=mipsel ;;
    *) architecture=unverified ;;
esac

version_text="$(ndmc -c 'show version' 2>/dev/null || true)"
model="$(printf '%s\n' "$version_text" | sed -n 's/^[[:space:]]*[Mm]odel[[:space:]]*:[[:space:]]*//p' | head -n 1)"
keenetic_os="$(printf '%s\n' "$version_text" | sed -n 's/^[[:space:]]*[Rr]elease[[:space:]]*:[[:space:]]*//p' | head -n 1)"
[ -n "$model" ] || model=unverified
[ -n "$keenetic_os" ] || keenetic_os=unverified

if command -v opkg >/dev/null 2>&1; then entware=present; else entware=absent; fi
package_version() {
    command -v opkg >/dev/null 2>&1 || return 1
    opkg list-installed 2>/dev/null | awk -v package="$1" '$1 == package && $2 == "-" { print $3; exit }'
}
binary_version() {
    if ! command -v "$1" >/dev/null 2>&1; then
        printf absent
        return
    fi
    package="$(package_version "$1" || true)"
    if [ -n "$package" ]; then
        printf '%s %s' "$1" "$package"
        return
    fi
    for argument in --version version -version -v; do
        output="$("$1" "$argument" 2>/dev/null)" || continue
        output="$(printf '%s\n' "$output" | head -n 1)"
        if [ -n "$output" ]; then
            printf '%s' "$output"
            return
        fi
    done
    printf present
}

printf 'model=%s\n' "$(safe "$model")"
printf 'keenetic_os=%s\n' "$(safe "$keenetic_os")"
printf 'architecture=%s\n' "$architecture"
printf 'entware=%s\n' "$entware"
printf 'xkeen=%s\n' "$(safe "$(binary_version xkeen)")"
printf 'xray=%s\n' "$(safe "$(binary_version xray)")"
printf 'sing_box=%s\n' "$(safe "$(binary_version sing-box)")"
printf 'awg_manager=%s\n' "$(safe "$(binary_version awg-manager)")"
printf 'companion=%s\n' "$(safe "$(binary_version keenwg-companion)")"
