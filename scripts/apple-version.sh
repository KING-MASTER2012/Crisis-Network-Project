#!/usr/bin/env bash
# Works out the version of an Apple release from the git ref (phase I9) and prints it as NAME=VALUE lines, for
# `bash scripts/apple-version.sh >> "$GITHUB_ENV"` in a workflow:
#
#   NAME       The version for file names: the tag `apple-v1.2.3-rc1` gives `1.2.3-rc1`; anything that is not a tag
#              of that kind (a manual run) gives `0.0.0-dev`.
#   MARKETING  The version the system shows (CFBundleShortVersionString): the numbers at the start of NAME (1.2.3),
#              or 0.1.0 when there are none.
#   BUILD      The build number (CFBundleVersion): the number of the workflow run, which only grows; 1 outside CI.
#   IS_TAG     true for an `apple-*` tag.
#   TAG        The tag, or empty.
#
# Reads GITHUB_REF and GITHUB_RUN_NUMBER.
set -euo pipefail

ref="${GITHUB_REF:-}"
if [[ "$ref" == refs/tags/apple-* ]]; then
    tag="${ref#refs/tags/}"
    name="${tag#apple-}"
    name="${name#v}"
    is_tag="true"
else
    tag=""
    name="0.0.0-dev"
    is_tag="false"
fi

marketing="$(printf '%s' "$name" | sed -nE 's/^([0-9]+(\.[0-9]+){0,2}).*/\1/p')"
if [ -z "$marketing" ]; then marketing="0.1.0"; fi

echo "NAME=$name"
echo "MARKETING=$marketing"
echo "BUILD=${GITHUB_RUN_NUMBER:-1}"
echo "IS_TAG=$is_tag"
echo "TAG=$tag"
