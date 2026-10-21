#!/usr/bin/env bash
# Browser audit for site/ (axe, overflow at 360/390/1280, reduced motion, JS off, approval demo, screenshots).
# Local only: needs Node 22+ and Playwright's Chromium (npx -y playwright@1.56.1 install chromium).
# Screenshots go to $SHOTS_DIR (see audit.mjs for the default). Exit 0 = pass.
#
#   bash scripts/site/audit.sh
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
npm ci --no-audit --no-fund --silent
node audit.mjs
