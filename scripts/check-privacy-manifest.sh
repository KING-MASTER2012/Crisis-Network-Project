#!/usr/bin/env bash
# Checks that the privacy manifest of a built Apple app declares what the app's binary uses (phase I9).
#
# Usage: scripts/check-privacy-manifest.sh PATH_TO.app
#
# Since spring 2024 App Store Connect refuses (ITMS-91053) an upload whose binary calls an API from Apple's "required
# reason" list without a reason in `PrivacyInfo.xcprivacy`. The mail comes after the upload, on the release day. This
# script looks at what the binary imports from the system (`nm -u`) and what the manifest declares, and fails when the
# two disagree in the way that Apple checks:
#
#   - the manifest is in the bundle, says that the app does not track and collects no data;
#   - every category of required-reason APIs that the binary imports has a reason in the manifest.
#
# The list of symbols is the part of Apple's list that can be seen in a binary. It is not complete (an API called through
# a Swift or Objective-C name is only found by its class: UserDefaults), and Apple's list grows: look at
# https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api when it does.
# A category that is declared but not found is only reported: the manifest may be ahead of the binary.
set -euo pipefail

app="${1:-}"
if [ -z "$app" ] || [ ! -d "$app" ]; then
    echo "usage: $0 PATH_TO.app" >&2
    exit 2
fi
if [ "$(uname -s)" != "Darwin" ]; then
    echo "This script needs macOS (nm, PlistBuddy)." >&2
    exit 1
fi

plist_buddy=/usr/libexec/PlistBuddy
failures=0
fail() { echo "FAIL: $*" >&2; failures=$((failures + 1)); }

manifest="$(find "$app" -name PrivacyInfo.xcprivacy -not -path '*/Frameworks/*' | head -n 1)"
if [ -z "$manifest" ]; then
    echo "FAIL: no PrivacyInfo.xcprivacy in $app" >&2
    exit 1
fi

# --- What the manifest says ------------------------------------------------------------------------------------------
[ "$($plist_buddy -c 'Print :NSPrivacyTracking' "$manifest" 2>/dev/null || true)" = "false" ] || fail "NSPrivacyTracking is not false"
collected="$($plist_buddy -c 'Print :NSPrivacyCollectedDataTypes' "$manifest" 2>/dev/null || true)"
if printf '%s' "$collected" | grep -q 'NSPrivacyCollectedDataType '; then
    fail "the manifest declares collected data; the app is meant to collect none (see README, App Privacy)"
fi

declared=""
index=0
while category="$($plist_buddy -c "Print :NSPrivacyAccessedAPITypes:$index:NSPrivacyAccessedAPIType" "$manifest" 2>/dev/null)"; do
    # The reasons are the indented lines of the array that PlistBuddy prints (C617.1, 35F9.1, ...).
    reasons="$($plist_buddy -c "Print :NSPrivacyAccessedAPITypes:$index:NSPrivacyAccessedAPITypeReasons" "$manifest" 2>/dev/null | grep -Ec '^ +[A-Z0-9][A-Z0-9.]*$' || true)"
    if [ "$reasons" -lt 1 ]; then fail "$category has no reason"; fi
    declared="$declared $category"
    index=$((index + 1))
done

# --- What the binary imports -----------------------------------------------------------------------------------------
if [ -f "$app/Contents/MacOS/$($plist_buddy -c 'Print :CFBundleExecutable' "$app/Contents/Info.plist" 2>/dev/null || echo none)" ]; then
    exe="$app/Contents/MacOS/$($plist_buddy -c 'Print :CFBundleExecutable' "$app/Contents/Info.plist")"
else
    exe="$app/$($plist_buddy -c 'Print :CFBundleExecutable' "$app/Info.plist")"
fi
[ -f "$exe" ] || { echo "no executable at $exe" >&2; exit 1; }

# A Debug build keeps the code in a dylib next to a small stub: look at both.
binaries=("$exe")
for extra in "$(dirname "$exe")"/*.debug.dylib; do
    [ -f "$extra" ] && binaries+=("$extra")
done
raw_symbols="$(for b in "${binaries[@]}"; do nm -u "$b" 2>/dev/null; done | awk '{print $NF}' | sort -u)"
# Without the suffixes that the system adds to some names (`_fstat$INODE64`).
symbols="$(printf '%s\n' "$raw_symbols" | grep -v 'OBJC_CLASS_' | sed -E 's/\$.*$//' | sort -u)"

uses() { # extended regular expression for the names (without the leading underscore)
    printf '%s\n' "$symbols" | sed -E 's/^_//' | grep -Eq "^($1)\$"
}

check() { # category label regex
    local category="$1" label="$2" pattern="$3"
    if uses "$pattern"; then
        case " $declared " in
            *" $category "*) echo "ok: $label is used and declared ($category)" ;;
            *) fail "the binary uses $label but the manifest has no $category" ;;
        esac
    else
        case " $declared " in
            *" $category "*) echo "note: $category is declared and the binary shows no such import (fine when it is used through a framework)" ;;
            *) echo "ok: $label is not used" ;;
        esac
    fi
}

check NSPrivacyAccessedAPICategoryFileTimestamp "file timestamps" 'stat|fstat|lstat|fstatat|getattrlist|fgetattrlist|getattrlistat|getattrlistbulk|futimes|utimes'
check NSPrivacyAccessedAPICategorySystemBootTime "the system boot time" 'mach_absolute_time|clock_gettime_nsec_np|mach_continuous_time'
check NSPrivacyAccessedAPICategoryDiskSpace "disk space" 'statfs|statvfs|fstatfs|fstatvfs'
# UserDefaults is an Objective-C class: it shows as a class reference.
if printf '%s\n' "$raw_symbols" | grep -q 'OBJC_CLASS_\$_NSUserDefaults'; then
    case " $declared " in
        *" NSPrivacyAccessedAPICategoryUserDefaults "*) echo "ok: UserDefaults is used and declared" ;;
        *) fail "the binary uses UserDefaults but the manifest has no NSPrivacyAccessedAPICategoryUserDefaults" ;;
    esac
fi

if [ "$failures" -gt 0 ]; then
    echo "$failures check(s) failed for $app" >&2
    exit 1
fi
echo "ok: the privacy manifest of $app matches its binary"
