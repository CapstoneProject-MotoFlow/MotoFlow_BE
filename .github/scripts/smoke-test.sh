#!/usr/bin/env bash
# Smoke test of a deployed backend through the public URL (proxy -> backend -> database).
# Usage: smoke-test.sh <base-url>, e.g. https://dev.motoflow.example.com
#
# Signing in as an account that does not exist must answer 401 with MSG09 (UC-01). That proves
# the proxy routes /api, the app is up, and it can query PostgreSQL, without needing a test account.
set -euo pipefail

BASE="${1:-}"
if [ -z "$BASE" ]; then
  echo "::error::Base URL is empty. Set the repository variable DEV_BASE_URL / PROD_BASE_URL."
  exit 1
fi
BASE="${BASE%/}"
BODY=$(mktemp)

for i in $(seq 1 24); do # up to 2 minutes (the first HTTPS certificate can take a moment)
  CODE=$(curl -sS -o "$BODY" -w '%{http_code}' --max-time 10 \
    -X POST "$BASE/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d '{"username":"smoke-test-no-such-user","password":"Smoke-test-0"}' 2>/dev/null) || CODE=000
  if [ "$CODE" = 401 ] && grep -q '"MSG09"' "$BODY"; then
    echo "Smoke test OK: $BASE/api/v1/auth/login answered 401 MSG09"
    exit 0
  fi
  echo "Attempt $i: HTTP $CODE"
  sleep 5
done

echo "::error::Smoke test failed for $BASE. Last response:"
cat "$BODY" || true
exit 1
