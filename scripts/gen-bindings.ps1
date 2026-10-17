# Generates the Kotlin and Swift bindings of the core (crate `ffi`, library `crisis_core`).
#
#   powershell scripts/gen-bindings.ps1 [-Language kotlin|swift|all]
#
# Output: target/bindings/kotlin  target/bindings/swift
#
# The bindings are generated from an unstripped debug build: UniFFI reads the interface
# description from symbols in the library, and the release profile strips them.
param(
    [ValidateSet("kotlin", "swift", "all")]
    [string]$Language = "all"
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$list = if ($Language -eq "all") { @("kotlin", "swift") } else { @($Language) }

cargo build -p ffi
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$library = "target/debug/crisis_core.dll"
foreach ($lang in $list) {
    $out = "target/bindings/$lang"
    if (Test-Path $out) { Remove-Item -Recurse -Force $out }
    New-Item -ItemType Directory -Force $out | Out-Null
    cargo run -q -p ffi --bin uniffi-bindgen -- generate --library $library --language $lang --out-dir $out
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "generated $lang bindings in $out"
}
