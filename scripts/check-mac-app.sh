#!/usr/bin/env bash
# Checks what the Mac app declares about itself (phase I8): the rights of its sandbox and the texts the system shows when
# it asks for a permission. A missing right is a feature that fails on a user's Mac only (the camera stays black, the
# radio stays off); a forbidden one is a hole in the threat model (the app is meant to have no network access at all).
#
# Usage: scripts/check-mac-app.sh PATH_TO.app [--signed]
#
#   (no option)  The app was built without signing (`CODE_SIGNING_ALLOWED=NO`): there is no signature to read the
#                entitlements from, so they are read from the file XcodeGen wrote for the target
#                (clients/apple/App/macOS/CrisisNetwork.entitlements, or $ENTITLEMENTS_FILE).
#   --signed     The app is signed (ad-hoc or Developer ID): the entitlements are read from the signature, which is what
#                the system enforces, and the hardened runtime flag is checked as well.
set -euo pipefail

cd "$(dirname "$0")/.."

app="${1:-}"
mode="${2:-}"
if [ -z "$app" ] || [ ! -d "$app" ]; then
    echo "usage: $0 PATH_TO.app [--signed]" >&2
    exit 2
fi
if [ -n "$mode" ] && [ "$mode" != "--signed" ]; then
    echo "unknown option: $mode" >&2
    exit 2
fi
if [ "$(uname -s)" != "Darwin" ]; then
    echo "This script needs macOS (PlistBuddy, codesign)." >&2
    exit 1
fi

plist_buddy=/usr/libexec/PlistBuddy
info="$app/Contents/Info.plist"
failures=0

fail() {
    echo "FAIL: $*" >&2
    failures=$((failures + 1))
}

# --- Info.plist ------------------------------------------------------------------------------------------------------

[ -f "$info" ] || { echo "no Info.plist in $app" >&2; exit 1; }

info_value() { "$plist_buddy" -c "Print :$1" "$info" 2>/dev/null || true; }

for key in NSBluetoothAlwaysUsageDescription NSCameraUsageDescription; do
    value="$(info_value "$key")"
    if [ -z "$value" ]; then
        fail "Info.plist has no $key (the system would end the app when it first asks for the permission)"
    fi
done
[ "$(info_value LSMinimumSystemVersion)" = "14.0" ] || fail "LSMinimumSystemVersion is not 14.0"
[ "$(info_value LSMultipleInstancesProhibited)" = "true" ] || fail "LSMultipleInstancesProhibited is not true (the database cannot be opened twice)"
[ "$(info_value ITSAppUsesNonExemptEncryption)" = "false" ] || fail "ITSAppUsesNonExemptEncryption is not false (see the export compliance note in clients/apple/README.md)"
[ -n "$(info_value CFBundleIdentifier)" ] || fail "no CFBundleIdentifier"

# --- Entitlements ----------------------------------------------------------------------------------------------------

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
entitlements="$work/entitlements.plist"

if [ "$mode" = "--signed" ]; then
    codesign --verify --deep --strict "$app" || fail "the signature does not verify"
    if ! codesign -d --entitlements :- "$app" > "$entitlements" 2>/dev/null || [ ! -s "$entitlements" ]; then
        fail "the signature carries no entitlements (the sandbox is not in force)"
        : > "$entitlements"
    fi
    flags="$(codesign -dv --verbose=4 "$app" 2>&1 | grep -i '^CodeDirectory' || true)"
    case "$flags" in
        *runtime*) ;;
        *) fail "the hardened runtime is not enabled (notarization refuses such an app): $flags" ;;
    esac
else
    source_file="${ENTITLEMENTS_FILE:-clients/apple/App/macOS/CrisisNetwork.entitlements}"
    if [ -f "$source_file" ]; then
        cp "$source_file" "$entitlements"
    else
        fail "no entitlements file at $source_file (run xcodegen generate)"
        : > "$entitlements"
    fi
fi

entitlement() { "$plist_buddy" -c "Print :$1" "$entitlements" 2>/dev/null || true; }

required=(
    com.apple.security.app-sandbox
    com.apple.security.device.bluetooth
    com.apple.security.device.camera
    com.apple.security.files.user-selected.read-write
)
for key in "${required[@]}"; do
    [ "$(entitlement "$key")" = "true" ] || fail "entitlement $key is not true"
done

# What must not be there. No network: the app only talks over Bluetooth, so a sandbox without network access is a
# property worth keeping. No file access beyond what the person picks. No way around the hardened runtime.
forbidden=(
    com.apple.security.network.client
    com.apple.security.network.server
    com.apple.security.files.all
    com.apple.security.files.downloads.read-write
    com.apple.security.cs.disable-library-validation
    com.apple.security.cs.allow-unsigned-executable-memory
    com.apple.security.cs.allow-jit
    com.apple.security.cs.disable-executable-page-protection
)
for key in "${forbidden[@]}"; do
    if [ -n "$(entitlement "$key")" ]; then
        fail "entitlement $key must not be there"
    fi
done
if grep -q 'temporary-exception' "$entitlements"; then
    fail "a temporary-exception entitlement is present (the App Store refuses it and it widens the sandbox)"
fi
# A release build must not be debuggable; a debug or ad-hoc test build may be.
if [ "$mode" = "--signed" ] && [ "${ALLOW_GET_TASK_ALLOW:-0}" != "1" ] && [ -n "$(entitlement com.apple.security.get-task-allow)" ]; then
    fail "get-task-allow is present: the app can be debugged by any process of the same user (set ALLOW_GET_TASK_ALLOW=1 for a debug build)"
fi

if [ "$failures" -gt 0 ]; then
    echo "$failures check(s) failed for $app" >&2
    exit 1
fi
echo "ok: $app (${mode:-unsigned}) has the rights it needs and none it must not have"
