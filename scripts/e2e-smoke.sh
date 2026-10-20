#!/usr/bin/env bash
# End-to-end smoke test against the TEST Hermes (never the real instance).
# Reads HERMES_TEST_URL / HERMES_TEST_USER / HERMES_TEST_PASSWORD from .local/hermes-test-creds.env.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
creds="${HERMES_TEST_CREDS:-$root/.local/hermes-test-creds.env}"
[[ -r "$creds" ]] || { echo "missing creds file: $creds" >&2; exit 2; }
set -a; . "$creds"; set +a
: "${HERMES_TEST_URL:?}" "${HERMES_TEST_USER:?}" "${HERMES_TEST_PASSWORD:?}"
case "$HERMES_TEST_URL" in
  http://hermes:*|http://hermes/*|http://hermes) echo "refusing to run against the real instance" >&2; exit 2;;
esac
exec uv run --quiet --with websockets python -I "$root/scripts/e2e_smoke.py"
