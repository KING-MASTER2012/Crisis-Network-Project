#!/usr/bin/env bash
# Generates the Kotlin and Swift bindings of the core (crate `ffi`, library `crisis_core`).
#
#   scripts/gen-bindings.sh [kotlin|swift|all]    (default: all)
#
# Output:   target/bindings/kotlin   target/bindings/swift
#
# The bindings are generated from an *unstripped* debug build: UniFFI reads the interface
# description from symbols in the library, and the release profile strips them. The
# generated code does not depend on the build profile or the target platform, so the same
# bindings work with the release libraries built for each platform.
set -euo pipefail

cd "$(dirname "$0")/.."

languages="${1:-all}"
case "$languages" in
    kotlin | swift) list="$languages" ;;
    all) list="kotlin swift" ;;
    *)
        echo "usage: $0 [kotlin|swift|all]" >&2
        exit 2
        ;;
esac

cargo build -p ffi

case "$(uname -s)" in
    Darwin) library="target/debug/libcrisis_core.dylib" ;;
    MINGW* | MSYS* | CYGWIN*) library="target/debug/crisis_core.dll" ;;
    *) library="target/debug/libcrisis_core.so" ;;
esac

for language in $list; do
    out="target/bindings/$language"
    rm -rf "$out"
    mkdir -p "$out"
    cargo run -q -p ffi --bin uniffi-bindgen -- \
        generate --library "$library" --language "$language" --out-dir "$out"
    echo "generated $language bindings in $out"
done
