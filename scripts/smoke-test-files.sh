#!/usr/bin/env bash
# smoke-test-files.sh: writes what scripts/smoke-test.sh reads into a folder, from the cluster's Secrets: the bank's
# test CA, the test provider's key and certificate, and the test customer's password. Jenkins runs it with a folder
# only its build sees; on this machine scripts/cluster-up.sh writes the same files to ~/.config/bank-open-api/pki.
#
#   bash scripts/smoke-test-files.sh DIR
set -euo pipefail

readonly SCRIPT_NAME="smoke-test-files.sh"
readonly PROJECT_VERSION="0.8.0"
if (($# != 1)) || [[ ! -d "$1" ]]; then
  echo "usage: ${SCRIPT_NAME} DIR, an existing folder" >&2
  exit 2
fi
readonly DIR=$1
echo "== ${SCRIPT_NAME} (bank-open-api ${PROJECT_VERSION}) =="

# secret_to SECRET KEY FILE: one key of a Secret of the bank's namespace into DIR/FILE, readable by this user only.
secret_to() {
  local secret=$1 key=$2 file=$3
  (
    umask 077
    kubectl --namespace bank get secret "$secret" -o "jsonpath={.data.${key//./\\.}}" | base64 -d > "${DIR}/${file}"
  ) || true
  if [[ ! -s "${DIR}/${file}" ]]; then
    echo "FAIL the Secret ${secret} has no ${key}: run scripts/cluster-up.sh (docs/cluster.md)"
    exit 1
  fi
}

secret_to provider-ca ca.crt ca.crt
secret_to smoke-test-provider tls.crt smoke-test.crt
secret_to smoke-test-provider tls.key smoke-test.key
secret_to test-customer password test-customer.password
echo "ok   the test CA, the test provider's key and certificate and the test customer's password in ${DIR}"
