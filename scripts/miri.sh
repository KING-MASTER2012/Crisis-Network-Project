#!/usr/bin/env bash
# Runs the tests of the pure-Rust crates under Miri (undefined behaviour check).
#
#   scripts/miri.sh [extra cargo test arguments]
#
# * Isolation is switched off: `proptest` reads the current directory to find its
#   regression files, and Miri refuses that call by default ("`GetCurrentDirectoryW` not
#   available when isolation is enabled"). Nothing else of the tests needs it.
# * Only `proto`, `appcodec` and `crypto` are run. `storage` (and so `mesh`, `engine`, `ffi`,
#   `cli`) call the C code of SQLite, which Miri cannot execute; those crates are covered by
#   `cargo test --workspace`.
set -euo pipefail
cd "$(dirname "$0")/.."
export MIRIFLAGS="${MIRIFLAGS:--Zmiri-disable-isolation}"
exec cargo +nightly miri test -p proto -p appcodec -p crypto "$@"
