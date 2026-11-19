#!/usr/bin/env bash
# Builds the Mac app, signs it with a Developer ID certificate, has Apple notarize it and packs it as a disk image that
# opens on any Mac without a warning (phase I8). The App Store / TestFlight build is a different path (phase I9).
#
# Usage: scripts/notarize-mac.sh [--skip-build] [--out DIR]
#
#   --skip-build   Use the archive from an earlier run (DIR/CrisisNetwork.xcarchive) instead of building again.
#   --out DIR      Where the archive, the exported app and the disk image go (default: build/mac-release).
#
# Environment (nothing secret is kept in the repository):
#
#   TEAM_ID          Your 10-character Apple developer team identifier (required).
#   SIGN_IDENTITY    The signing certificate (default: "Developer ID Application"; use the full name when you have several).
#   APP_BUNDLE_ID    The bundle identifier to build with (default: the one in clients/apple/Config/Base.xcconfig).
#
#   How to log in to the notary service, one of:
#   NOTARY_PROFILE   A keychain profile made once with
#                      xcrun notarytool store-credentials crisis-notary --apple-id YOU --team-id TEAM --password APP_SPECIFIC_PASSWORD
#   NOTARY_KEY_PATH + NOTARY_KEY_ID + NOTARY_ISSUER
#                    An App Store Connect API key (.p8 file, its key id and the issuer id), which is what a CI job uses.
#
# Needs: macOS, Xcode, XcodeGen, Rust, the "Developer ID Application" certificate in the keychain, and a network.
# The disk image is DIR/CrisisNetwork.dmg; its SHA-256 is printed at the end.
set -euo pipefail

cd "$(dirname "$0")/.."
root="$PWD"

skip_build="false"
out="build/mac-release"
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build) skip_build="true"; shift ;;
        --out) out="${2:?--out needs a directory}"; shift 2 ;;
        -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

if [ "$(uname -s)" != "Darwin" ]; then
    echo "This script needs macOS." >&2
    exit 1
fi
: "${TEAM_ID:?set TEAM_ID to your Apple developer team identifier}"
sign_identity="${SIGN_IDENTITY:-Developer ID Application}"

for tool in xcodebuild xcrun codesign hdiutil ditto plutil; do
    command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done

# How to reach the notary service.
notary_auth=()
if [ -n "${NOTARY_PROFILE:-}" ]; then
    notary_auth=(--keychain-profile "$NOTARY_PROFILE")
elif [ -n "${NOTARY_KEY_PATH:-}" ] && [ -n "${NOTARY_KEY_ID:-}" ] && [ -n "${NOTARY_ISSUER:-}" ]; then
    notary_auth=(--key "$NOTARY_KEY_PATH" --key-id "$NOTARY_KEY_ID" --issuer "$NOTARY_ISSUER")
else
    echo "set NOTARY_PROFILE, or NOTARY_KEY_PATH with NOTARY_KEY_ID and NOTARY_ISSUER (see the top of this script)" >&2
    exit 1
fi

mkdir -p "$out"
out="$(cd "$out" && pwd)"
archive="$out/CrisisNetwork.xcarchive"
export_dir="$out/export"
project="$root/clients/apple/CrisisNetwork.xcodeproj"

# Submits a file to the notary service and waits for the verdict. On a refusal the log says why, and the script stops.
notarize() {
    local file="$1" result status id
    echo "==> Notarizing $(basename "$file") (this takes a few minutes)"
    result="$(xcrun notarytool submit "$file" "${notary_auth[@]}" --wait --output-format json)"
    status="$(printf '%s' "$result" | plutil -extract status raw -o - - 2>/dev/null || echo unknown)"
    id="$(printf '%s' "$result" | plutil -extract id raw -o - - 2>/dev/null || echo unknown)"
    if [ "$status" != "Accepted" ]; then
        echo "Notarization of $(basename "$file") ended as: $status (submission $id)" >&2
        xcrun notarytool log "$id" "${notary_auth[@]}" >&2 || true
        exit 1
    fi
    echo "    accepted (submission $id)"
}

# --- Build, sign ---------------------------------------------------------------------------------------------------------

if [ "$skip_build" = "false" ]; then
    echo "==> The core, for Macs with Apple silicon and Intel, release profile, encrypted database"
    bash scripts/build-apple.sh --profile release --sqlcipher --universal --platforms mac

    echo "==> The Xcode project"
    (cd clients/apple && xcodegen generate)

    echo "==> Archive (Release, hardened runtime, signed with: $sign_identity)"
    rm -rf "$archive" "$export_dir"
    extra=()
    if [ -n "${APP_BUNDLE_ID:-}" ]; then extra+=("APP_BUNDLE_ID=$APP_BUNDLE_ID"); fi
    xcodebuild -project "$project" -scheme CrisisNetwork-macOS -configuration Release \
        -destination 'generic/platform=macOS' -archivePath "$archive" \
        DEVELOPMENT_TEAM="$TEAM_ID" CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY="$sign_identity" \
        OTHER_CODE_SIGN_FLAGS="--timestamp" ${extra[@]+"${extra[@]}"} \
        archive

    echo "==> Export for Developer ID"
    cat > "$out/ExportOptions.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key><string>developer-id</string>
    <key>teamID</key><string>$TEAM_ID</string>
    <key>signingStyle</key><string>manual</string>
    <key>signingCertificate</key><string>$sign_identity</string>
</dict>
</plist>
PLIST
    xcodebuild -exportArchive -archivePath "$archive" -exportOptionsPlist "$out/ExportOptions.plist" -exportPath "$export_dir"
fi

app="$(ls -d "$export_dir"/*.app 2>/dev/null | head -n 1 || true)"
[ -n "$app" ] || { echo "no .app in $export_dir (run without --skip-build first)" >&2; exit 1; }
echo "==> App: $app"

echo "==> Checking the signature, the hardened runtime and the sandbox rights"
bash scripts/check-mac-app.sh "$app" --signed

# --- Notarize the app, staple, pack ----------------------------------------------------------------------------------------

zip="$out/CrisisNetwork-notarize.zip"
rm -f "$zip"
ditto -c -k --keepParent "$app" "$zip"
notarize "$zip"
xcrun stapler staple "$app"
xcrun stapler validate "$app"
spctl --assess --type execute --verbose=2 "$app"

echo "==> Disk image"
dmg="$out/CrisisNetwork.dmg"
stage="$out/dmg-stage"
rm -rf "$stage" "$dmg"
mkdir -p "$stage"
cp -R "$app" "$stage/"
ln -s /Applications "$stage/Applications"
hdiutil create -volname "Crisis Network" -srcfolder "$stage" -ov -format UDZO "$dmg"
rm -rf "$stage"
codesign --sign "$sign_identity" --timestamp "$dmg"
notarize "$dmg"
xcrun stapler staple "$dmg"
xcrun stapler validate "$dmg"
spctl --assess --type open --context context:primary-signature --verbose=2 "$dmg"

echo "==> Done: $dmg"
shasum -a 256 "$dmg"
