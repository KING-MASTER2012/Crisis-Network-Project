#!/usr/bin/env bash
# Builds the Apple app in every flavour that a release carries and packs each one as a file (phase I9).
#
# Usage: scripts/package-apple.sh ios|macos OUT_DIR
#
# Needs the core in clients/apple/Packages/CrisisCore (scripts/build-apple.sh --universal) and the generated Xcode
# project (xcodegen generate, in clients/apple). Environment: NAME (the version in file names, default 0.0.0-dev),
# MARKETING (the version the system shows, default 0.1.0), BUILD (the build number, default 1): scripts/apple-version.sh.
#
# Files, for each of Debug and Release:
#
#   macOS  CrisisNetwork-<NAME>-macos-<debug|release>-<arm64|x86_64|universal>.zip
#          The app, signed ad hoc (the sandbox rights are in force; the system does not know the signer, so Gatekeeper asks
#          on the first start: right-click > Open). The notarized disk image is made by scripts/notarize-mac.sh.
#   iOS    CrisisNetwork-<NAME>-ios-device-<debug|release>-arm64-unsigned.ipa
#          The app for iPhones and iPads, not signed: install it with a tool that signs with your own identity (Xcode,
#          AltStore, Sideloadly...). The App Store build is made by the release workflow, with the signing secrets.
#          CrisisNetwork-<NAME>-ios-simulator-<debug|release>-<arm64|x86_64>.zip
#          The app for the iOS simulator of Macs with Apple silicon (arm64) or Intel (x86_64): unzip, then
#          `xcrun simctl install booted CrisisNetwork-iOS.app`.
#
# The Release builds of macOS are also checked: the sandbox rights (scripts/check-mac-app.sh), the privacy manifest
# (scripts/check-privacy-manifest.sh).
set -euo pipefail

cd "$(dirname "$0")/.."

platform="${1:-}"
out="${2:-}"
if { [ "$platform" != "ios" ] && [ "$platform" != "macos" ]; } || [ -z "$out" ]; then
    echo "usage: $0 ios|macos OUT_DIR" >&2
    exit 2
fi
if [ "$(uname -s)" != "Darwin" ]; then
    echo "This script needs macOS." >&2
    exit 1
fi

NAME="${NAME:-0.0.0-dev}"
MARKETING="${MARKETING:-0.1.0}"
BUILD="${BUILD:-1}"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
project="clients/apple/CrisisNetwork.xcodeproj"
derived="clients/apple/build/package"
[ -d "$project" ] || { echo "no $project: run xcodegen generate in clients/apple first" >&2; exit 1; }

lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

# build SCHEME CONFIGURATION DESTINATION "ARCHS" DERIVED_DATA [extra build settings...]
build() {
    local scheme="$1" config="$2" destination="$3" archs="$4" data="$5"
    shift 5
    rm -rf "$data"
    echo "==> $scheme $config ($archs)"
    xcodebuild -project "$project" -scheme "$scheme" -configuration "$config" -destination "$destination" \
        -derivedDataPath "$data" ARCHS="$archs" ONLY_ACTIVE_ARCH=NO \
        MARKETING_VERSION="$MARKETING" CURRENT_PROJECT_VERSION="$BUILD" \
        "$@" build
}

# the_app DIRECTORY: the one .app in it
the_app() {
    local found
    found="$(ls -d "$1"/*.app 2>/dev/null | head -n 1 || true)"
    [ -n "$found" ] || { echo "no .app in $1" >&2; exit 1; }
    printf '%s' "$found"
}

# check_archs APP "EXPECTED ARCHS": the executable of a macOS app has exactly the processor types that its file name says.
check_archs() {
    local app="$1" expected="$2" exe have want
    exe="$app/Contents/MacOS/$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$app/Contents/Info.plist")"
    have="$(lipo -archs "$exe" | tr ' ' '\n' | sort | tr '\n' ' ')"
    want="$(printf '%s' "$expected" | tr ' ' '\n' | sort | tr '\n' ' ')"
    if [ "$have" != "$want" ]; then
        echo "FAIL: $app has [$have], expected [$want]" >&2
        exit 1
    fi
}

zip_app() { # app file
    ditto -c -k --sequesterRsrc --keepParent "$1" "$2"
    echo "    $(basename "$2")  $(du -h "$2" | cut -f1)"
}

if [ "$platform" = "macos" ]; then
    for config in Debug Release; do
        for arch in arm64 x86_64 universal; do
            archs="$arch"
            if [ "$arch" = "universal" ]; then archs="arm64 x86_64"; fi
            data="$derived/mac-$config-$arch"
            # A Release build must not be debuggable: Xcode adds get-task-allow to a locally signed build otherwise, and
            # notarization refuses it (see project.yml).
            inject=""
            if [ "$config" = "Release" ]; then inject="CODE_SIGN_INJECT_BASE_ENTITLEMENTS=NO"; fi
            build CrisisNetwork-macOS "$config" "generic/platform=macOS" "$archs" "$data" \
                CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=YES DEVELOPMENT_TEAM= ${inject:+"$inject"}
            app="$(the_app "$data/Build/Products/$config")"
            if [ "$config" = "Release" ]; then
                bash scripts/check-mac-app.sh "$app" --signed
                bash scripts/check-privacy-manifest.sh "$app"
            fi
            check_archs "$app" "$archs"
            zip_app "$app" "$out/CrisisNetwork-$NAME-macos-$(lower "$config")-$arch.zip"
        done
    done
else
    for config in Debug Release; do
        label="$(lower "$config")"

        # The device: not signed. An .ipa is a zip with the app in a folder called Payload.
        data="$derived/ios-device-$config"
        build CrisisNetwork-iOS "$config" "generic/platform=iOS" arm64 "$data" CODE_SIGNING_ALLOWED=NO
        app="$(the_app "$data/Build/Products/$config-iphoneos")"
        if [ "$config" = "Release" ]; then bash scripts/check-privacy-manifest.sh "$app"; fi
        stage="$(mktemp -d)"
        mkdir "$stage/Payload"
        cp -R "$app" "$stage/Payload/"
        (cd "$stage" && zip -qry "$out/CrisisNetwork-$NAME-ios-device-$label-arm64-unsigned.ipa" Payload)
        rm -rf "$stage"
        echo "    CrisisNetwork-$NAME-ios-device-$label-arm64-unsigned.ipa"

        # The simulator, one file per processor type. Signed ad hoc: an unsigned app has no application identifier,
        # and the Keychain then fails with -34018.
        for arch in arm64 x86_64; do
            data="$derived/ios-sim-$config-$arch"
            build CrisisNetwork-iOS "$config" "generic/platform=iOS Simulator" "$arch" "$data" \
                CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=YES DEVELOPMENT_TEAM=
            app="$(the_app "$data/Build/Products/$config-iphonesimulator")"
            zip_app "$app" "$out/CrisisNetwork-$NAME-ios-simulator-$label-$arch.zip"
        done
    done
fi

echo "==> Files in $out"
ls -l "$out"
