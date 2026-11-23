# Runs the tests of the pure-Rust crates under Miri (undefined behaviour check).
#
#   powershell scripts/miri.ps1 [extra cargo test arguments]
#
# * Isolation is switched off: `proptest` reads the current directory to find its
#   regression files, and Miri refuses that call by default ("`GetCurrentDirectoryW` not
#   available when isolation is enabled"). Nothing else of the tests needs it.
# * Only `proto`, `appcodec` and `crypto` are run. `storage` (and so `mesh`, `engine`, `ffi`,
#   `cli`) call the C code of SQLite, which Miri cannot execute; those crates are covered by
#   `cargo test --workspace`.
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
if (-not $env:MIRIFLAGS) { $env:MIRIFLAGS = "-Zmiri-disable-isolation" }
cargo +nightly miri test -p proto -p appcodec -p crypto @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
