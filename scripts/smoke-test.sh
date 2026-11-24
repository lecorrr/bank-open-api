#!/usr/bin/env bash
# smoke-test.sh: signs in as the test provider "smoke-test" and takes a consent through what the API offers so
# far, the way a provider would. It reads Keycloak's discovery document, gets a token with the client credentials
# grant, signing in with a JWT it signs with its key (private_key_jwt, PS256), over mutual TLS with its
# certificate, and checks the token is bound to that certificate. Then the consent: create it, read its status,
# see account reads refused until the customer approves it (the approval page comes in step 4), delete it. It
# also checks that calls without the certificate or without a token are refused. Stops at the first answer that
# differs. Jenkins runs it after each deploy; it works the same from this machine.
#
#   bash scripts/smoke-test.sh                               through Traefik on this machine
#   bash scripts/smoke-test.sh https://k3d-bank-serverlb       from Jenkins, on k3d's Docker network
#   bash scripts/smoke-test.sh --token                       only sign in, and print the access token
#
# PKI_DIR (default ~/.config/bank-open-api/pki, written by scripts/cluster-up.sh) holds the bank's test CA
# certificate (ca.crt) and the provider's certificate and key (smoke-test.crt, smoke-test.key).
set -euo pipefail

readonly SCRIPT_NAME="smoke-test.sh"
readonly PROJECT_VERSION="0.3.0"
token_only=0
if [[ "${1:-}" == "--token" ]]; then
  token_only=1
  shift
  # Everything else goes to stderr, so the token alone reaches stdout: TOKEN=$(bash scripts/smoke-test.sh --token)
  exec 3>&1 1>&2
fi
readonly BASE_URL="${1:-https://localhost:8443}"
readonly PKI_DIR="${PKI_DIR:-${HOME}/.config/bank-open-api/pki}"
readonly CLIENT_ID="smoke-test"
readonly REALM_URL="${BASE_URL}/realms/bank"
# The first of bank-core's demo accounts.
readonly IBAN="UA843999990000000000000000001"

echo "== ${SCRIPT_NAME} (bank-open-api ${PROJECT_VERSION}) against ${BASE_URL} =="

for tool in curl openssl base64; do
  if ! command -v "$tool" > /dev/null 2>&1; then
    echo "FAIL ${tool} is missing"
    exit 1
  fi
done
for file in ca.crt smoke-test.crt smoke-test.key; do
  if [[ ! -s "${PKI_DIR}/${file}" ]]; then
    echo "FAIL ${PKI_DIR}/${file} is missing or empty: run scripts/cluster-up.sh (docs/cluster.md)"
    exit 1
  fi
done
readonly CA="${PKI_DIR}/ca.crt"
readonly CERTIFICATE="${PKI_DIR}/smoke-test.crt"
readonly KEY="${PKI_DIR}/smoke-test.key"

answer="$(mktemp)"
trap 'rm -f "$answer"' EXIT
status=""

# call METHOD URL [curl options]: sets $status and keeps the body in $answer. Always TLS with the bank's test CA.
call() {
  local method=$1 url=$2
  shift 2
  status="$(curl -sS -o "$answer" -w '%{http_code}' --cacert "$CA" -X "$method" "$@" "$url")"
}

# api METHOD PATH [curl options]: a call to the API as the provider, with its token and its certificate. Every call
# has its own request id.
api() {
  local method=$1 path=$2
  shift 2
  call "$method" "${BASE_URL}${path}" --cert "$CERTIFICATE" --key "$KEY" \
    -H "Authorization: Bearer ${access_token}" -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)" "$@"
}

# field NAME: a top-level string field of the last answer; Keycloak and the API answer with compact JSON.
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

base64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# The client assertion of RFC 7523: who the provider is, for whom (the issuer), once (jti), for a minute. Signed
# with RSASSA-PSS, SHA-256 and a 32-byte salt: PS256, the only algorithm FAPI 2.0 and the realm accept from RSA.
client_assertion() {
  local now header payload signing_input signature
  now="$(date +%s)"
  header="$(printf '{"alg":"PS256","typ":"JWT"}' | base64url)"
  payload="$(printf '{"iss":"%s","sub":"%s","aud":"%s","jti":"%s","iat":%d,"exp":%d}' "$CLIENT_ID" "$CLIENT_ID" \
    "$issuer" "$(cat /proc/sys/kernel/random/uuid)" "$now" "$((now + 60))" | base64url)"
  signing_input="${header}.${payload}"
  signature="$(printf '%s' "$signing_input" | openssl dgst -sha256 -sign "$KEY" \
    -sigopt rsa_padding_mode:pss -sigopt rsa_pss_saltlen:32 | base64url)"
  printf '%s.%s' "$signing_input" "$signature"
}

# token_request [curl options]: the client credentials grant at Keycloak's token endpoint.
token_request() {
  call POST "${REALM_URL}/protocol/openid-connect/token" "$@" \
    --data-urlencode grant_type=client_credentials \
    --data-urlencode client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer \
    --data-urlencode "client_assertion=$(client_assertion)"
}

# --- signing in ----------------------------------------------------------------------------------------------

call GET "${REALM_URL}/.well-known/openid-configuration"
expect "Keycloak's realm bank answers through Traefik with the test CA's certificate" 200
issuer="$(field issuer)"
if ! grep -q '"tls_client_certificate_bound_access_tokens":true' "$answer"; then
  echo "FAIL Keycloak does not announce certificate-bound tokens: $(cat "$answer")"
  exit 1
fi
echo "ok   issuer ${issuer}, certificate-bound tokens"

token_request --cert "$CERTIFICATE" --key "$KEY"
expect "token for ${CLIENT_ID}" 200 token_type Bearer
access_token="$(field access_token)"

token_request
expect "no token without the certificate the token is to be bound to" 400 error invalid_request

# RFC 8705: the token carries the SHA-256 thumbprint of the certificate it is bound to.
token_claims="$(printf '%s' "$access_token" | cut -d. -f2 | tr '_-' '/+')"
while ((${#token_claims} % 4)); do token_claims+="="; done
token_claims="$(printf '%s' "$token_claims" | openssl base64 -d -A)"
thumbprint="$(openssl x509 -in "$CERTIFICATE" -outform DER | openssl dgst -sha256 -binary | base64url)"
if [[ "$token_claims" != *"\"x5t#S256\":\"${thumbprint}\""* ]]; then
  echo "FAIL the token is not bound to the smoke-test certificate: ${token_claims}"
  exit 1
fi
echo "ok   token bound to the certificate (x5t#S256 ${thumbprint})"
if ((token_only)); then
  printf '%s\n' "$access_token" >&3
  exit 0
fi

# --- the consent ---------------------------------------------------------------------------------------------

api POST /v2/consents/account-access -H 'Content-Type: application/json' -H 'PSU-IP-Address: 192.0.2.10' \
  --data "{\"access\": {\"payments\": [{\"account\": {\"iban\": \"${IBAN}\"}, \"rights\": [\"balances\"]}]},
           \"consentType\": \"detailed\", \"recurringIndicator\": true, \"validTo\": \"9999-12-31\",
           \"frequencyPerDay\": 4}"
expect "consent created" 201 consentStatus received
consent_id="$(field consentId)"

api GET "/v2/consents/account-access/${consent_id}/status"
expect "consent ${consent_id} is received" 200 consentStatus received

api GET /v2/accounts -H "Consent-ID: ${consent_id}"
expect "account reads refused before the customer approves" 401 code CONSENT_INVALID

# The token alone is not enough: without the certificate it is bound to, the API refuses it.
call GET "${BASE_URL}/v2/consents/account-access/${consent_id}/status" -H "Authorization: Bearer ${access_token}" \
  -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)"
expect "token without its certificate refused" 401 code CERTIFICATE_MISSING

call GET "${BASE_URL}/v2/consents/account-access/${consent_id}/status" --cert "$CERTIFICATE" --key "$KEY" \
  -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)"
expect "certificate without a token refused" 401 code TOKEN_UNKNOWN

api DELETE "/v2/consents/account-access/${consent_id}"
expect "consent deleted" 204

api GET "/v2/consents/account-access/${consent_id}/status"
expect "consent is terminatedByTpp" 200 consentStatus terminatedByTpp

echo "== smoke test passed =="
