#!/usr/bin/env bash
# smoke-test.sh: takes a consent through what the API offers so far, the way a provider would: create it, read its
# status, see account reads refused until the customer approves it (the approval page comes in step 4), delete it.
# Stops at the first answer that differs. Jenkins runs it after each deploy; it works the same from this machine.
#
#   bash scripts/smoke-test.sh                          through Traefik on this machine
#   bash scripts/smoke-test.sh http://k3d-bank-serverlb   from Jenkins, on k3d's Docker network
set -euo pipefail

readonly SCRIPT_NAME="smoke-test.sh"
readonly PROJECT_VERSION="0.2.1"
readonly BASE_URL="${1:-http://localhost:8480}"
# The first of bank-core's demo accounts.
readonly IBAN="UA843999990000000000000000001"

echo "== ${SCRIPT_NAME} (bank-open-api ${PROJECT_VERSION}) against ${BASE_URL} =="

answer="$(mktemp)"
trap 'rm -f "$answer"' EXIT
status=""

# call METHOD PATH [curl options]: sets $status and keeps the body in $answer. Every call has its own request id.
call() {
  local method=$1 path=$2
  shift 2
  status="$(curl -sS -o "$answer" -w '%{http_code}' -X "$method" \
    -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)" "$@" "${BASE_URL}${path}")"
}

# field NAME: a top-level string field of the last answer; the API answers with compact JSON.
field() {
  sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" "$answer"
}

# expect WHAT STATUS [FIELD VALUE]
expect() {
  local what=$1 want_status=$2 name=${3:-} want=${4:-}
  if [[ "$status" != "$want_status" ]]; then
    echo "FAIL ${what}: HTTP ${status}, expected ${want_status}: $(cat "$answer")"
    exit 1
  fi
  if [[ -n "$name" && "$(field "$name")" != "$want" ]]; then
    echo "FAIL ${what}: ${name} is '$(field "$name")', expected '${want}': $(cat "$answer")"
    exit 1
  fi
  echo "ok   ${what}"
}

call POST /v2/consents/account-access -H 'Content-Type: application/json' -H 'PSU-IP-Address: 192.0.2.10' \
  --data "{\"access\": {\"payments\": [{\"account\": {\"iban\": \"${IBAN}\"}, \"rights\": [\"balances\"]}]},
           \"consentType\": \"detailed\", \"recurringIndicator\": true, \"validTo\": \"9999-12-31\",
           \"frequencyPerDay\": 4}"
expect "consent created" 201 consentStatus received
consent_id="$(field consentId)"

call GET "/v2/consents/account-access/${consent_id}/status"
expect "consent ${consent_id} is received" 200 consentStatus received

call GET /v2/accounts -H "Consent-ID: ${consent_id}"
expect "account reads refused before the customer approves" 401 code CONSENT_INVALID

call DELETE "/v2/consents/account-access/${consent_id}"
expect "consent deleted" 204

call GET "/v2/consents/account-access/${consent_id}/status"
expect "consent is terminatedByTpp" 200 consentStatus terminatedByTpp

echo "== smoke test passed =="
