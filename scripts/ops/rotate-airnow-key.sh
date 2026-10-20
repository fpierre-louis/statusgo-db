#!/bin/zsh
# Rotate the AirNow API key on Heroku without the value ever reaching the
# terminal, shell history, logs or git.
#
#   1. Sign in to https://docs.airnowapi.org with the SitPrep account and
#      request a new API key.
#   2. scripts/ops/rotate-airnow-key.sh        # paste the key at the hidden prompt
#   3. Retire the old key in the AirNow account if it offers that.
#
# `heroku config:set` prints the value it sets, so its output is discarded.
# Verification reads one production AQI value through the API as the agent-a
# test account and reports only whether a reading came back.
set -eu
APP="${HEROKU_APP:-statusgo-db}"
FE="${SITPREP_FE:-/Users/dione/Desktop/MS Bootcamp/SitPrep FE}"

printf 'New AirNow API key (input hidden): '
IFS= read -rs KEY
printf '\n'
[ -n "$KEY" ] || { echo "No key entered; nothing changed."; exit 1; }

heroku config:set AIRNOW_API_KEY="$KEY" -a "$APP" >/dev/null 2>&1 \
  || { unset KEY; echo "heroku config:set failed (output suppressed so the key is not shown)."; exit 1; }
unset KEY
echo "Set AIRNOW_API_KEY on $APP (value not shown). Heroku restarts the dynos."

for i in {1..30}; do
  heroku ps -a "$APP" 2>/dev/null | grep -q "web.1: up" && break
  sleep 10
done
sleep 20

TOKEN=$(cd "$FE" && PATH=/opt/homebrew/bin:$PATH node scripts/test-accounts/token.mjs agent-a)
PRESENT=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.sitprep.app/api/community/conditions?lat=40.7608&lng=-111.891" \
  | python3 -I -c 'import json,sys; n=((json.load(sys.stdin).get("data") or {}).get("now") or {}); print("yes" if n.get("aqi") is not None else "no")')
echo "Production AQI reading after rotation: $PRESENT"
[ "$PRESENT" = yes ] || echo "No AQI value: check the key in the AirNow account, then re-run." >&2
