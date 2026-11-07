#!/usr/bin/env bash
# Prints the identifier of an available iPhone simulator (a booted one if there is one), for
# `xcodebuild -destination "id=..."`. Exits with an error when there is none.
set -euo pipefail

xcrun simctl list devices available --json | python3 -c '
import json, sys
data = json.load(sys.stdin)["devices"]
phones = []
for runtime, devices in data.items():
    if "iOS" not in runtime:
        continue
    for device in devices:
        if device.get("isAvailable", True) and device["name"].startswith("iPhone"):
            phones.append((device["state"] != "Booted", runtime, device["name"], device["udid"]))
if not phones:
    sys.exit("no iPhone simulator is available")
# Booted first, then the newest runtime.
phones.sort(key=lambda p: (p[0], [-int(x) if x.isdigit() else 0 for x in p[1].rsplit("iOS-", 1)[-1].split("-")], p[2]))
print(phones[0][3])
'
