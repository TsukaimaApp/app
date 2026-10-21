#!/usr/bin/env bash
# Static checks for site/: HTML validation, then scripts/site/check-page.mjs (hosts, local files, copy rules,
# structure, font-face rules, budgets). Exit 0 = pass. Needs Node 22+ and network access for npx on first run.
#
#   bash scripts/site/check.sh
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo"

# Pinned so CI and local runs agree. .htmlvalidate.json extends html-validate:recommended and only sets
# doctype-style to lowercase, because the page uses `<!doctype html>` (case is a style choice, not correctness).
HTML_VALIDATE=html-validate@11.17.0

status=0
echo "== html-validate ($HTML_VALIDATE)"
npx -y "$HTML_VALIDATE" --config scripts/site/.htmlvalidate.json site/index.html || status=1
echo "== check-page"
node scripts/site/check-page.mjs site/index.html || status=1

if (( status == 0 )); then echo "site checks: pass"; else echo "site checks: FAIL" >&2; fi
exit "$status"
