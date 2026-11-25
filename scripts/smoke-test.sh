#!/usr/bin/env bash
# smoke-test.sh: signs in as the test provider "smoke-test" and takes consents and payments through the API the way
# a provider would, with the test customer deciding on the bank's page as a browser would. It reads Keycloak's
# discovery document, gets a token with the client credentials grant, signing in with a JWT it signs with its key
# (private_key_jwt, PS256), over mutual TLS with its certificate, and checks the token is bound to that
# certificate. Then three consents: one the provider deletes before any decision; one the customer approves on the
# bank's page (signed in at Keycloak's login form), which the provider then reads under, and which the customer
# withdraws; one that names another customer's account, which the customer can only reject. Then three payments:
# one the provider cancels; one of 1.00 UAH the customer approves, which bank-core makes within the bank at once;
# one from another customer's account, which the customer can only reject. The API has a host of its own: the
# test checks that it takes no connection without the provider's certificate or with one the bank did not issue,
# that the bank's address does not serve the API, not even when a call names the API's host, that a call without a
# token is refused, and, last, that a burst of calls over the provider's rate limit gets 429 with Retry-After. It
# also checks that a decision without the page's CSRF token is refused. Stops at the first answer that differs.
# Jenkins runs it after each deploy; it works the same from here.
#
#   bash scripts/smoke-test.sh                               through Traefik on this machine
#   bash scripts/smoke-test.sh https://k3d-bank-serverlb       from Jenkins, on k3d's Docker network
#   bash scripts/smoke-test.sh --token                       only sign in, and print the access token
#
# PKI_DIR (default ~/.config/bank-open-api/pki, written by scripts/cluster-up.sh) holds the bank's test CA
# certificate (ca.crt), the provider's certificate and key (smoke-test.crt, smoke-test.key) and the test
# customer's password (test-customer.password).
set -euo pipefail

readonly SCRIPT_NAME="smoke-test.sh"
readonly PROJECT_VERSION="0.6.1"
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
# The first of bank-core's demo accounts, the test customer's (C1001), and one of another customer (C1002). Each
# run pays 1.00 UAH from the first to the second; the demo data gives the first 10 000.00.
readonly IBAN="UA843999990000000000000000001"
readonly OTHERS_IBAN="UA573999990000000000000000002"
# The test customer in the realm bank; cluster-up.sh writes the password of the Secret test-customer into PKI_DIR.
readonly CUSTOMER="artem.demo"
# Where the provider wants the customer's browser back: never opened here, only compared.
readonly TPP_REDIRECT="https://tpp.example/smoke-test/done"
# The bank's public address, which the links to its pages and to Keycloak's login start with.
readonly PUBLIC_URL="https://localhost:8443"
# The API's own host, where Traefik requires the provider's client certificate in the TLS handshake.
readonly API_URL="https://api.localhost:8443"
# The bank's one payment product: credit transfers in UAH to accounts at banks in Ukraine.
readonly PAYMENTS="/v2/payments/domestic-credit-transfers"

echo "== ${SCRIPT_NAME} (bank-open-api ${PROJECT_VERSION}) against ${BASE_URL} =="

for tool in curl openssl base64; do
  if ! command -v "$tool" > /dev/null 2>&1; then
    echo "FAIL ${tool} is missing"
    exit 1
  fi
done
for file in ca.crt smoke-test.crt smoke-test.key test-customer.password; do
  if [[ ! -s "${PKI_DIR}/${file}" ]]; then
    echo "FAIL ${PKI_DIR}/${file} is missing or empty: run scripts/cluster-up.sh (docs/cluster.md)"
    exit 1
  fi
done
readonly CA="${PKI_DIR}/ca.crt"
readonly CERTIFICATE="${PKI_DIR}/smoke-test.crt"
readonly KEY="${PKI_DIR}/smoke-test.key"

# A browser from Jenkins reaches the public address at BASE_URL: curl connects there but keeps localhost:8443 as
# the name, for Traefik, the certificate and the cookies.
base_authority="${BASE_URL#https://}"
base_authority="${base_authority%%/*}"
base_host="${base_authority%%:*}"
base_port="443"
if [[ "$base_authority" == *:* ]]; then base_port="${base_authority##*:}"; fi
browser_route=()
if [[ "${base_host}:${base_port}" != "localhost:8443" ]]; then
  browser_route=(--connect-to "localhost:8443:${base_host}:${base_port}")
fi
# Calls to the API keep its host as the name, for Traefik's TLS options and route and for the certificate, and
# connect where BASE_URL points: no lookup of the name is needed, here or in Jenkins.
api_route=(--connect-to "${API_URL#https://}:${base_host}:${base_port}")

answer="$(mktemp)"
headers="$(mktemp)"
cookies="$(mktemp)"
# The password goes to curl from a file, never on a command line; a line break at its end is not part of it.
password="$(mktemp)"
# A key and certificate the bank's CA did not sign, made for this run.
stranger="$(mktemp -d)"
trap 'rm -f "$answer" "$headers" "$cookies" "$password"; rm -rf "$stranger"' EXIT
tr -d '\n' < "${PKI_DIR}/test-customer.password" > "$password"
status=""

# call METHOD URL [curl options]: sets $status and keeps the body in $answer. Always TLS with the bank's test CA.
call() {
  local method=$1 url=$2
  shift 2
  status="$(curl -sS -o "$answer" -w '%{http_code}' --cacert "$CA" -X "$method" "$@" "$url")"
}

# api METHOD PATH [curl options]: a call to the API as the provider, on the API's host, with its token and its
# certificate. Every call has its own request id.
api() {
  local method=$1 path=$2
  shift 2
  call "$method" "${API_URL}${path}" "${api_route[@]}" --cert "$CERTIFICATE" --key "$KEY" \
    -H "Authorization: Bearer ${access_token}" -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)" "$@"
}

# no_connection WHAT [curl options]: a call to the API's host that Traefik must end in the TLS handshake, before
# any HTTP: no status, and curl stopped in TLS (35) or on the connection Traefik closed after its alert (52, 55, 56),
# not before it reached Traefik (6, 7, 28).
no_connection() {
  local what=$1 code curl_exit=0 error
  shift
  error="$(mktemp)"
  code="$(curl -sS -o /dev/null -w '%{http_code}' --cacert "$CA" "${api_route[@]}" "$@" \
    "${API_URL}/v2/accounts" 2> "$error")" || curl_exit=$?
  if [[ "$code" != "000" || ! "$curl_exit" =~ ^(35|52|55|56)$ ]]; then
    echo "FAIL ${what}: HTTP ${code}, curl exit ${curl_exit}, expected no connection: $(head -c 300 "$error")"
    rm -f "$error"
    exit 1
  fi
  rm -f "$error"
  echo "ok   ${what}"
}

# field NAME: a top-level string field of the last answer; Keycloak and the API answer with compact JSON.
field() {
  sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" "$answer"
}

# browser METHOD URL [curl options]: a request as the customer's browser makes it: cookies of this sign-in, no
# client certificate, no redirects followed. Sets $status, the body in $answer and $location.
browser() {
  local method=$1 url=$2
  shift 2
  status="$(curl -sS -o "$answer" -D "$headers" -w '%{http_code}' --cacert "$CA" "${browser_route[@]}" \
    -b "$cookies" -c "$cookies" -X "$method" "$@" "$url")"
  location="$(sed -n 's/^[Ll]ocation: *\(.*\)\r$/\1/p' "$headers" | tail -1)"
  # A relative Location is relative to the bank's public address.
  if [[ "$location" == /* ]]; then location="${PUBLIC_URL}${location}"; fi
}

# expect_redirect WHAT PREFIX: the last browser answer sends the browser on to an address starting with PREFIX.
expect_redirect() {
  local what=$1 prefix=$2
  if [[ "$status" != "302" || "$location" != "${prefix}"* ]]; then
    echo "FAIL ${what}: HTTP ${status} to '${location}', expected a redirect to ${prefix}: $(head -c 300 "$answer")"
    exit 1
  fi
  echo "ok   ${what}"
}

# page_has WHAT TEXT: the last page the browser got shows TEXT.
page_has() {
  if ! grep -qF -- "$2" "$answer"; then
    echo "FAIL $1: the page does not show '$2'"
    exit 1
  fi
  echo "ok   $1"
}

# no_hsts WHAT: the last answer does not pin the browser to HTTPS. HSTS for localhost would apply to every port of
# it, Jenkins' http://localhost:8081 included.
no_hsts() {
  if grep -qi '^strict-transport-security:' "$headers"; then
    echo "FAIL $1 sends Strict-Transport-Security, which browsers apply to every localhost port"
    exit 1
  fi
}

# sign_in PAGE: the customer opens one of the bank's pages and signs in at Keycloak's login form, which asks for the
# password every time (prompt=login). Ends on the page, with the page's CSRF token in $csrf.
sign_in() {
  local page=$1 form_action
  : > "$cookies"
  browser GET "${PUBLIC_URL}${page}"
  expect_redirect "  the bank sends a customer who is not signed in to sign in" \
    "${PUBLIC_URL}/oauth2/authorization/keycloak"
  browser GET "$location"
  expect_redirect "  to Keycloak's login, with PKCE" "${PUBLIC_URL}/realms/bank/protocol/openid-connect/auth?"
  if [[ "$location" != *"code_challenge_method=S256"* || "$location" != *"prompt=login"* ]]; then
    echo "FAIL the sign-in asks for no S256 PKCE or no fresh login: ${location}"
    exit 1
  fi
  browser GET "$location"
  expect "  Keycloak's login form" 200
  no_hsts "Keycloak's login form"
  form_action="$(tr '\n' ' ' < "$answer" | grep -o '<form[^>]*id="kc-form-login"[^>]*>' \
    | sed -n 's/.*action="\([^"]*\)".*/\1/p' | sed 's/&amp;/\&/g')"
  if [[ -z "$form_action" ]]; then
    echo "FAIL no login form on Keycloak's page: $(head -c 300 "$answer")"
    exit 1
  fi
  browser POST "$form_action" --data-urlencode "username=${CUSTOMER}" \
    --data-urlencode "password@${password}" --data-urlencode "credentialId="
  expect_redirect "  ${CUSTOMER} signed in at Keycloak" "${PUBLIC_URL}/login/oauth2/code/keycloak?"
  browser GET "$location"
  expect_redirect "  the bank redeems the code and sends the customer back" "${PUBLIC_URL}${page}"
  browser GET "$location"
  expect "  the page ${page}" 200
  no_hsts "the bank's page"
  csrf="$(sed -n 's/.*name="_csrf" value="\([^"]*\)".*/\1/p' "$answer" | head -1)"
}

# create_consent IBAN: a consent for balances of one account, with the provider's redirect address. Sets $consent_id
# and $page, the bank's page for the customer (the scaRedirect link without the public address).
create_consent() {
  api POST /v2/consents/account-access -H 'Content-Type: application/json' -H 'PSU-IP-Address: 192.0.2.10' \
    -H "TPP-Redirect-URI: ${TPP_REDIRECT}" \
    --data "{\"access\": {\"payments\": [{\"account\": {\"iban\": \"$1\"}, \"rights\": [\"balances\"]}]},
             \"consentType\": \"detailed\", \"recurringIndicator\": true, \"validTo\": \"9999-12-31\",
             \"frequencyPerDay\": 4}"
  expect "consent created" 201 consentStatus received
  consent_id="$(field consentId)"
  local link
  link="$(sed -n 's/.*"scaRedirect":{"href":"\([^"]*\)".*/\1/p' "$answer")"
  if [[ "$link" != "${PUBLIC_URL}/customer/consents/${consent_id}" ]]; then
    echo "FAIL the consent's scaRedirect link is '${link}': $(cat "$answer")"
    exit 1
  fi
  page="/customer/consents/${consent_id}"
}

# create_payment DEBTOR_IBAN CREDITOR_IBAN: a payment of 1.00 UAH, with the provider's redirect address. Sets
# $payment_id, $payment (its path in the API) and $page, the bank's page for the customer.
create_payment() {
  api POST "$PAYMENTS" -H 'Content-Type: application/json' -H 'PSU-IP-Address: 192.0.2.10' \
    -H "TPP-Redirect-URI: ${TPP_REDIRECT}" \
    --data "{\"debtorAccount\": {\"iban\": \"$1\"}, \"instructedAmount\": {\"currency\": \"UAH\", \"amount\": \"1.00\"},
             \"creditorAccount\": {\"iban\": \"$2\"}, \"creditor\": {\"name\": \"Olena Kovalenko\"},
             \"remittanceInformationUnstructured\": \"Smoke test payment\"}"
  expect "payment initiated" 201 transactionStatus RCVD
  payment_id="$(field paymentId)"
  local link
  link="$(sed -n 's/.*"scaRedirect":{"href":"\([^"]*\)".*/\1/p' "$answer")"
  if [[ "$link" != "${PUBLIC_URL}/customer/payments/${payment_id}" ]]; then
    echo "FAIL the payment's scaRedirect link is '${link}': $(cat "$answer")"
    exit 1
  fi
  payment="${PAYMENTS}/${payment_id}"
  page="/customer/payments/${payment_id}"
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

# RFC 9728: where a provider learns which authorization server issues tokens for the API. Open to any provider, on
# the API's host.
call GET "${API_URL}/.well-known/oauth-protected-resource" "${api_route[@]}" --cert "$CERTIFICATE" --key "$KEY"
expect "the API's protected resource metadata, on its own host" 200 resource "$API_URL"
if ! grep -qF "\"authorization_servers\":[\"${issuer}\"]" "$answer"; then
  echo "FAIL the metadata names another authorization server than ${issuer}: $(cat "$answer")"
  exit 1
fi
if ! grep -q '"scopes_supported":\[[^]]*"payments"' "$answer"; then
  echo "FAIL the metadata lists no scope payments: $(cat "$answer")"
  exit 1
fi

# --- a consent the provider deletes before the customer decides ------------------------------------------------

create_consent "$IBAN"

api GET "/v2/consents/account-access/${consent_id}/status"
expect "consent ${consent_id} is received" 200 consentStatus received

api GET /v2/accounts -H "Consent-ID: ${consent_id}"
expect "account reads refused before the customer approves" 401 code CONSENT_INVALID

# The token alone is not enough: the API's host takes no connection without a certificate from the bank's CA.
no_connection "the API's host refuses a connection without a client certificate" \
  -H "Authorization: Bearer ${access_token}"
openssl req -x509 -newkey rsa:2048 -noenc -days 1 -subj "/CN=not a provider of the bank" \
  -keyout "${stranger}/key.pem" -out "${stranger}/certificate.pem" 2> /dev/null
no_connection "the API's host refuses a certificate the bank did not issue" \
  --cert "${stranger}/certificate.pem" --key "${stranger}/key.pem" -H "Authorization: Bearer ${access_token}"

# The API is on its own host only; the bank's address has no route for it.
call GET "${BASE_URL}/v2/consents/account-access/${consent_id}/status" --cert "$CERTIFICATE" --key "$KEY" \
  -H "Authorization: Bearer ${access_token}"
expect "the bank's address does not serve the API" 404
# Nor does a connection made to the bank's address, where no certificate is required, reach the API by naming its
# host: Traefik sees the handshake had other TLS options than the API's route and answers 421.
call GET "${BASE_URL}/v2/consents/account-access/${consent_id}/status" -H "Host: ${API_URL#https://}" \
  -H "Authorization: Bearer ${access_token}"
expect "the API's host named on a connection to the bank's address" 421

call GET "${API_URL}/v2/consents/account-access/${consent_id}/status" "${api_route[@]}" -D "$headers" \
  --cert "$CERTIFICATE" --key "$KEY" -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)"
expect "certificate without a token refused" 401 code TOKEN_UNKNOWN
if ! grep -qiF "www-authenticate: Bearer resource_metadata=\"${API_URL}/.well-known/oauth-protected-resource\"" \
  "$headers"; then
  echo "FAIL the challenge does not point to the metadata on the API's host: $(grep -i '^www-authenticate' "$headers")"
  exit 1
fi

api DELETE "/v2/consents/account-access/${consent_id}"
expect "consent deleted" 204

api GET "/v2/consents/account-access/${consent_id}/status"
expect "consent is terminatedByTpp" 200 consentStatus terminatedByTpp

# --- a consent the customer approves on the bank's page, and later withdraws ------------------------------------

create_consent "$IBAN"
sign_in "$page"
page_has "the page shows the account the provider asks for" "$IBAN"
# A form another site posts carries no CSRF token: the bank must refuse it, though the customer may approve.
browser POST "${PUBLIC_URL}${page}" --data-urlencode decision=approve
expect "a decision without the page's CSRF token refused" 403
browser POST "${PUBLIC_URL}${page}" --data-urlencode decision=approve --data-urlencode "_csrf=${csrf}"
expect_redirect "approved, and the browser goes back to the provider" "$TPP_REDIRECT"

api GET "/v2/consents/account-access/${consent_id}/status"
expect "consent ${consent_id} is valid" 200 consentStatus valid
api GET /v2/accounts -H "Consent-ID: ${consent_id}"
expect "the provider lists the account under the consent" 200
if ! grep -qF "\"iban\":\"${IBAN}\"" "$answer"; then
  echo "FAIL the account list lacks ${IBAN}: $(cat "$answer")"
  exit 1
fi
resource_id="$(field resourceId)"
api GET "/v2/accounts/${resource_id}/balances" -H "Consent-ID: ${consent_id}"
expect "the provider reads the balance" 200

sign_in /customer/consents
page_has "the customer's consents list it" "$consent_id"
browser POST "${PUBLIC_URL}${page}/withdraw" --data-urlencode "_csrf=${csrf}"
expect_redirect "withdrawn on the bank's page" "${PUBLIC_URL}/customer/consents"
api GET "/v2/consents/account-access/${consent_id}/status"
expect "consent ${consent_id} is revokedByPsu" 200 consentStatus revokedByPsu
api GET /v2/accounts -H "Consent-ID: ${consent_id}"
expect "account reads refused after the withdrawal" 401 code CONSENT_INVALID

# --- a consent for another customer's account: the customer can only reject it -------------------------------

create_consent "$OTHERS_IBAN"
sign_in "$page"
page_has "the page says the account is not the customer's" "not yours"
browser POST "${PUBLIC_URL}${page}" --data-urlencode decision=approve --data-urlencode "_csrf=${csrf}"
expect "approval refused: the account is not the customer's" 403
browser POST "${PUBLIC_URL}${page}" --data-urlencode decision=reject --data-urlencode "_csrf=${csrf}"
expect_redirect "rejected, and the browser goes back to the provider" "$TPP_REDIRECT"
api GET "/v2/consents/account-access/${consent_id}/status"
expect "consent ${consent_id} is rejected" 200 consentStatus rejected

# --- a payment the provider cancels before the customer decides ------------------------------------------------

create_payment "$IBAN" "$OTHERS_IBAN"
api GET "${payment}/status"
expect "payment ${payment_id} is RCVD" 200 transactionStatus RCVD
api DELETE "$payment"
expect "payment cancelled" 204
api GET "${payment}/status"
expect "payment ${payment_id} is CANC" 200 transactionStatus CANC

# --- a payment the customer approves: bank-core makes it within the bank at once -------------------------------

create_payment "$IBAN" "$OTHERS_IBAN"
sign_in "$page"
page_has "the page shows the amount" "1.00 UAH"
page_has "the page shows the payee's account" "$OTHERS_IBAN"
browser POST "${PUBLIC_URL}${page}" --data-urlencode decision=approve --data-urlencode "_csrf=${csrf}"
expect_redirect "payment approved, and the browser goes back to the provider" "$TPP_REDIRECT"
api GET "${payment}/status"
expect "payment ${payment_id} is ACCC: paid within the bank" 200 transactionStatus ACCC

# --- a payment from another customer's account: the customer can only reject it -------------------------------

create_payment "$OTHERS_IBAN" "$IBAN"
sign_in "$page"
page_has "the page says the payment's account is not the customer's" "not yours"
browser POST "${PUBLIC_URL}${page}" --data-urlencode decision=approve --data-urlencode "_csrf=${csrf}"
expect "approval refused: the payment's account is not the customer's" 403
browser POST "${PUBLIC_URL}${page}" --data-urlencode decision=reject --data-urlencode "_csrf=${csrf}"
expect_redirect "payment rejected, and the browser goes back to the provider" "$TPP_REDIRECT"
api GET "${payment}/status"
expect "payment ${payment_id} is RJCT" 200 transactionStatus RJCT

# --- the provider's rate limit at the edge: last, so its throttling slows nothing above ------------------------

# 60 calls at once, more than the burst of 20 Traefik allows one provider: the rest come back 429 with
# Retry-After. The metadata is the cheapest answer on the API's host.
burst=()
for _ in $(seq 60); do burst+=(-o /dev/null "${API_URL}/.well-known/oauth-protected-resource"); done
curl -sS --parallel --parallel-max 60 --cacert "$CA" "${api_route[@]}" --cert "$CERTIFICATE" --key "$KEY" \
  -w '%{http_code} %header{retry-after}\n' "${burst[@]}" > "$answer" 2>&1 || true
limited="$(grep -c '^429 [0-9][0-9]*$' "$answer" || true)"
passed="$(grep -c '^200 $' "$answer" || true)"
if ((limited == 0 || passed == 0 || limited + passed != 60)); then
  echo "FAIL a burst of 60 calls: ${passed} passed and ${limited} got 429 with Retry-After, expected some of each:"
  sort "$answer" | uniq -c | head -5
  exit 1
fi
echo "ok   a burst of 60 calls: ${passed} passed, ${limited} got 429 with Retry-After"

echo "== smoke test passed =="
