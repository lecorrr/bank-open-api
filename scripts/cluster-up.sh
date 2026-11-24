#!/usr/bin/env bash
# cluster-up.sh: creates the k3d cluster "bank" and its platform: the bank namespace, the bank's test certificate
# authority with the certificates it signs, Traefik's HTTPS Gateway with mutual TLS, PostgreSQL with a database
# for bank-core, bank-open-api and Keycloak, Keycloak with the realm "bank", and the account Jenkins deploys with.
# Safe to run again: it creates or fixes only what is missing, and starts the cluster if it was stopped. The
# services themselves come from Jenkins (docs/cluster.md).
#
#   bash scripts/cluster-up.sh --check   report, change nothing
#   bash scripts/cluster-up.sh           create or fix what is missing
set -euo pipefail

readonly SCRIPT_NAME="cluster-up.sh"
readonly PROJECT_VERSION="0.3.0"
readonly CLUSTER="bank"
readonly CONTEXT="k3d-bank"
readonly NAMESPACE="bank"
# API, HTTP, HTTPS and registry, as in cluster/k3d.yaml
readonly HOST_PORTS=(6550 8480 8443 5050)
readonly CONFIG_DIR="${HOME}/.config/bank-open-api"
readonly JENKINS_KUBECONFIG="${CONFIG_DIR}/jenkins-kubeconfig.yaml"
# The test CA's key and certificate, and a copy of the smoke-test provider's for scripts/smoke-test.sh.
readonly PKI_DIR="${CONFIG_DIR}/pki"
readonly CERTIFICATE_DAYS=397
readonly RENEW_DAYS=30
readonly WAIT_SECONDS=300

echo "== ${SCRIPT_NAME} (bank-open-api ${PROJECT_VERSION}): k3d cluster '${CLUSTER}' and its platform =="

mode="fix"
for arg in "$@"; do
  case "$arg" in
    --check) mode="check" ;;
    -h | --help)
      sed -n '2,10p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option '$arg'. Use --check or --help." >&2
      exit 2
      ;;
  esac
done

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
if [[ ! -f cluster/k3d.yaml || ! -d cluster/charts/postgres || ! -d cluster/charts/keycloak ]]; then
  echo "FAIL  ${project_dir} is not the unpacked bank-open-api project: cluster/ is missing" >&2
  exit 1
fi

# Keys and certificates are made here, in a folder only this run sees.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

results=()
failures=0
ok() { results+=("OK    $1"); }
warn() { results+=("WARN  $1"); }
fail() {
  results+=("FAIL  $1")
  failures=$((failures + 1))
}
note() { results+=("      $1"); }
step() { echo "-- $1"; }

finish() {
  echo
  echo "== summary: ${SCRIPT_NAME} (${mode}) =="
  printf '%s\n' "${results[@]}"
  if ((failures > 0)); then
    echo "== ${failures} problem(s)"
    exit 1
  fi
  echo "== the cluster and its platform are ready"
  exit 0
}

# Every call names the cluster's context, so no other cluster in ~/.kube/config is ever touched.
k() { kubectl --context "$CONTEXT" "$@"; }
h() { helm --kube-context "$CONTEXT" "$@"; }

# Runs a command until it succeeds or WAIT_SECONDS pass.
wait_for() {
  local what=$1 deadline=$((SECONDS + WAIT_SECONDS))
  shift
  until "$@" > /dev/null 2>&1; do
    if ((SECONDS >= deadline)); then
      echo "   gave up waiting for ${what} after ${WAIT_SECONDS} s" >&2
      return 1
    fi
    sleep 3
  done
}

port_in_use() { ss -Hltn "sport = :$1" 2> /dev/null | grep -q .; }

# --- tools -------------------------------------------------------------------------------------------------

step "tools"
missing=0
for tool in docker k3d kubectl helm openssl curl ss base64; do
  if ! command -v "$tool" > /dev/null 2>&1; then
    fail "${tool} is missing"
    missing=1
  fi
done
if ((missing)); then
  note "k3d, kubectl and helm: bash scripts/install-tools.sh"
  finish
fi
helm_version="$(helm version --short 2> /dev/null || true)"
if [[ "$helm_version" != v4.* ]]; then
  fail "helm ${helm_version:-unknown} found; the Jenkinsfiles and this script use Helm 4 (--rollback-on-failure)"
  note "bash scripts/install-tools.sh"
  finish
fi
ok "tools: $(k3d version | head -n 1), helm ${helm_version}"
# kubectl supports one minor version of difference from the cluster's v1.36.
kubectl_minor="$(kubectl version --client 2> /dev/null | sed -n 's/^Client Version: v1\.\([0-9]*\)\..*/\1/p')"
if [[ -z "$kubectl_minor" ]] || ((kubectl_minor < 35 || kubectl_minor > 37)); then
  warn "kubectl $(kubectl version --client 2> /dev/null | head -n 1) is far from the cluster's v1.36"
  note "bash scripts/install-tools.sh"
fi

if ! docker info > /dev/null 2>&1; then
  fail "Docker does not answer: is it running, and is ${USER} in the docker group?"
  finish
fi

# --- cluster -----------------------------------------------------------------------------------------------

step "cluster"
if k3d cluster get "$CLUSTER" > /dev/null 2>&1; then
  if [[ "$(docker inspect -f '{{.State.Running}}' "k3d-${CLUSTER}-server-0" 2> /dev/null)" == "true" ]]; then
    ok "cluster ${CLUSTER} is running"
  elif [[ "$mode" == "check" ]]; then
    fail "cluster ${CLUSTER} exists but is stopped; run this script without --check to start it"
    finish
  else
    echo "   starting cluster ${CLUSTER}"
    k3d cluster start "$CLUSTER"
    ok "cluster ${CLUSTER} started"
  fi
else
  busy=()
  for port in "${HOST_PORTS[@]}"; do
    if port_in_use "$port"; then busy+=("$port"); fi
  done
  if ((${#busy[@]} > 0)); then
    fail "cluster ${CLUSTER} is missing, and these ports it needs are taken: ${busy[*]}"
    note "see what holds one: ss -ltnp 'sport = :${busy[0]}'; or change the port in cluster/k3d.yaml"
    finish
  fi
  if [[ "$mode" == "check" ]]; then
    fail "cluster ${CLUSTER} is missing; run this script without --check to create it"
    finish
  fi
  echo "   creating cluster ${CLUSTER}; the first time downloads the k3s images, a few minutes"
  k3d cluster create --config cluster/k3d.yaml
  ok "cluster ${CLUSTER} created"
fi

if ! kubectl config get-contexts "$CONTEXT" > /dev/null 2>&1; then
  if [[ "$mode" == "check" ]]; then
    fail "kubectl has no context ${CONTEXT}; run this script without --check"
    finish
  fi
  k3d kubeconfig merge "$CLUSTER" --kubeconfig-merge-default > /dev/null
fi
if ! wait_for "the node" k wait --for=condition=Ready node --all --timeout=10s; then
  fail "the cluster's node is not Ready: kubectl --context ${CONTEXT} get nodes"
  finish
fi
ok "node ready, kubectl context ${CONTEXT}"

# --- Traefik and the Gateway API ---------------------------------------------------------------------------

# k3s turns a changed HelmChartConfig into a new Traefik Deployment; this one is the setting of this version.
# shellcheck disable=SC2329  # called through wait_for
traefik_has_settings() {
  k -n kube-system get deployment traefik -o jsonpath='{.spec.template.spec.containers[0].args}' 2> /dev/null \
    | grep -q 'aliasHeadersStrategy=delete'
}

step "Traefik with the Gateway API"
if [[ "$mode" == "fix" ]]; then
  # Applied every time, so a cluster made by an earlier version gets this version's settings.
  k apply -f cluster/traefik-config.yaml > /dev/null
fi
if ! k -n kube-system get helmchartconfig traefik > /dev/null 2>&1; then
  fail "Traefik's settings (cluster/traefik-config.yaml) are not in the cluster"
  finish
fi
if [[ "$mode" == "check" ]] && ! traefik_has_settings; then
  fail "Traefik runs without this version's settings; run this script without --check"
  finish
fi
# k3s installs Traefik with a Helm job after start; the GatewayClass appears once the provider is on.
if wait_for "Traefik's settings" traefik_has_settings \
  && wait_for "Traefik" k -n kube-system rollout status deployment/traefik --timeout=10s \
  && wait_for "the traefik GatewayClass" k wait --for=condition=Accepted gatewayclass/traefik --timeout=10s; then
  ok "Traefik is running with this version's settings and its GatewayClass is accepted"
else
  fail "Traefik, its settings or its GatewayClass are not ready"
  note "kubectl --context ${CONTEXT} -n kube-system get pods,helmcharts,helmchartconfigs"
  finish
fi

# --- namespace ---------------------------------------------------------------------------------------------

step "namespace ${NAMESPACE}"
if [[ "$mode" == "fix" ]]; then
  k apply -f cluster/platform/namespace.yaml > /dev/null
fi
if k get namespace "$NAMESPACE" > /dev/null 2>&1; then
  ok "namespace ${NAMESPACE} (Pod Security: $(k get namespace "$NAMESPACE" \
    -o jsonpath='{.metadata.labels.pod-security\.kubernetes\.io/enforce}'))"
else
  fail "namespace ${NAMESPACE} is missing"
  finish
fi

# --- certificates ------------------------------------------------------------------------------------------

# The bank's test certificate authority. It signs the Gateway's server certificate and the providers' client
# certificates; its key stays in PKI_DIR on this machine, never in the cluster.
ensure_ca() {
  if [[ -s "${PKI_DIR}/ca.key" && -s "${PKI_DIR}/ca.crt" ]]; then
    if openssl x509 -in "${PKI_DIR}/ca.crt" -noout -checkend $((RENEW_DAYS * 86400)) > /dev/null 2>&1; then
      ok "test CA ${PKI_DIR}/ca.crt"
    else
      fail "the test CA expires within ${RENEW_DAYS} days: delete ${PKI_DIR} and the cluster, then run this again"
    fi
    return
  fi
  if [[ "$mode" == "check" ]]; then
    fail "the test CA is missing: ${PKI_DIR}/ca.crt"
    return
  fi
  mkdir -p "$PKI_DIR"
  chmod 0700 "$CONFIG_DIR" "$PKI_DIR"
  (
    umask 077
    openssl req -x509 -new -newkey rsa:3072 -noenc -sha256 -days 3650 \
      -subj "/O=bank-open-api test/CN=bank-open-api test CA" \
      -addext "basicConstraints=critical,CA:TRUE,pathlen:0" -addext "keyUsage=critical,keyCertSign,cRLSign" \
      -keyout "${PKI_DIR}/ca.key" -out "${PKI_DIR}/ca.crt" 2> /dev/null
  )
  ok "test CA created: ${PKI_DIR}/ca.crt, valid 10 years"
}

# secret_file SECRET KEY FILE: one key of a Secret into a file; an empty file when there is none.
secret_file() {
  (umask 077 && k -n "$NAMESPACE" get secret "$1" -o "jsonpath={.data.${2//./\\.}}" 2> /dev/null | base64 -d > "$3") \
    || : > "$3"
}

# A certificate is current when the test CA signed it and it runs for RENEW_DAYS more at least.
certificate_current() {
  openssl verify -CAfile "${PKI_DIR}/ca.crt" "$1" > /dev/null 2>&1 \
    && openssl x509 -in "$1" -noout -checkend $((RENEW_DAYS * 86400)) > /dev/null 2>&1
}

# issue_certificate NAME SUBJECT EXTENSIONS: a certificate from the test CA for the key in $work/NAME.key, made
# first if there is none. A renewed provider certificate keeps its key, so the key Keycloak knows still fits.
issue_certificate() {
  local name=$1 subject=$2 extensions=$3
  if [[ ! -s "${work}/${name}.key" ]]; then
    (umask 077 && openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "${work}/${name}.key" 2> /dev/null)
  fi
  openssl req -new -key "${work}/${name}.key" -subj "$subject" -out "${work}/${name}.csr" 2> /dev/null
  openssl x509 -req -in "${work}/${name}.csr" -CA "${PKI_DIR}/ca.crt" -CAkey "${PKI_DIR}/ca.key" -sha256 \
    -days "$CERTIFICATE_DAYS" -set_serial "0x$(openssl rand -hex 16)" -extfile <(printf '%s\n' "$extensions") \
    -out "${work}/${name}.crt" 2> /dev/null
}

# ensure_certificate_secret NAME SUBJECT EXTENSIONS: a kubernetes.io/tls Secret with a key and a certificate from
# the test CA, renewed when it nears its end or another CA signed it. The Secret also holds the certificate as
# one line of base64 DER under certificate-base64, the form Keycloak's realm file takes.
ensure_certificate_secret() {
  local name=$1 subject=$2 extensions=$3
  secret_file "$name" tls.crt "${work}/${name}.crt"
  if [[ -s "${work}/${name}.crt" ]] && certificate_current "${work}/${name}.crt"; then
    ok "secret ${name}: certificate until $(openssl x509 -in "${work}/${name}.crt" -noout -enddate | cut -d= -f2)"
    return
  fi
  if [[ "$mode" == "check" ]]; then
    fail "secret ${name} is missing, ends within ${RENEW_DAYS} days or is not from the test CA"
    return
  fi
  secret_file "$name" tls.key "${work}/${name}.key"
  issue_certificate "$name" "$subject" "$extensions"
  openssl x509 -in "${work}/${name}.crt" -outform DER | base64 -w 0 > "${work}/${name}.der.b64"
  k -n "$NAMESPACE" create secret generic "$name" --type=kubernetes.io/tls \
    --from-file=tls.crt="${work}/${name}.crt" --from-file=tls.key="${work}/${name}.key" \
    --from-file=certificate-base64="${work}/${name}.der.b64" --dry-run=client -o yaml | k apply -f - > /dev/null
  ok "secret ${name}: certificate issued, valid ${CERTIFICATE_DAYS} days"
}

step "certificates"
ensure_ca
if [[ -s "${PKI_DIR}/ca.crt" ]]; then
  # Traefik checks providers' certificates against it.
  secret_file provider-ca ca.crt "${work}/provider-ca.crt"
  if cmp -s "${work}/provider-ca.crt" "${PKI_DIR}/ca.crt"; then
    ok "secret provider-ca holds the test CA"
  elif [[ "$mode" == "check" ]]; then
    fail "secret provider-ca is missing or holds another CA"
  else
    k -n "$NAMESPACE" create secret generic provider-ca --from-file=ca.crt="${PKI_DIR}/ca.crt" \
      --dry-run=client -o yaml | k apply -f - > /dev/null
    ok "secret provider-ca set to the test CA"
  fi
  # The names Traefik's certificate answers to: this machine, and k3d's load balancer for Jenkins.
  ensure_certificate_secret bank-gateway-tls "/O=bank-open-api test/CN=localhost" \
    "$(printf '%s\n' 'basicConstraints=critical,CA:FALSE' 'keyUsage=critical,digitalSignature,keyEncipherment' \
      'extendedKeyUsage=serverAuth' 'subjectAltName=DNS:localhost,DNS:k3d-bank-serverlb,IP:127.0.0.1')"
  # The test provider "smoke-test": Keycloak's client of that name and scripts/smoke-test.sh.
  ensure_certificate_secret smoke-test-provider "/O=bank-open-api test/CN=smoke-test" \
    "$(printf '%s\n' 'basicConstraints=critical,CA:FALSE' 'keyUsage=critical,digitalSignature' \
      'extendedKeyUsage=clientAuth')"
  if [[ "$mode" == "fix" ]]; then
    # A copy for the smoke test on this machine; Jenkins reads the Secret itself.
    secret_file smoke-test-provider tls.crt "${PKI_DIR}/smoke-test.crt"
    secret_file smoke-test-provider tls.key "${PKI_DIR}/smoke-test.key"
  fi
  if [[ -s "${PKI_DIR}/smoke-test.crt" && -s "${PKI_DIR}/smoke-test.key" ]]; then
    ok "smoke-test provider's key and certificate in ${PKI_DIR}"
  else
    fail "the smoke-test provider's key and certificate are not in ${PKI_DIR}; run this script without --check"
  fi
fi

# --- the edge: TLS and the Gateway -------------------------------------------------------------------------

step "Traefik's TLS and the Gateway"
if [[ "$mode" == "fix" ]]; then
  for manifest in cluster/platform/edge.yaml cluster/platform/gateway.yaml; do
    k apply -f "$manifest" > /dev/null
  done
fi
if k -n "$NAMESPACE" get tlsoption default > /dev/null 2>&1 \
  && k -n "$NAMESPACE" get middleware pass-client-cert > /dev/null 2>&1; then
  ok "TLS options (client certificates from the test CA) and the middleware pass-client-cert"
else
  fail "Traefik's TLS options or middleware are missing (cluster/platform/edge.yaml)"
fi
if k -n "$NAMESPACE" wait --for=condition=Programmed gateway/bank-gateway --timeout=120s > /dev/null 2>&1; then
  ok "Gateway bank-gateway is programmed: https://localhost:8443"
else
  fail "Gateway bank-gateway is not programmed: kubectl --context ${CONTEXT} -n ${NAMESPACE} describe gateway"
fi

# --- secrets -----------------------------------------------------------------------------------------------

# Creates a Secret from key=value pairs if it does not exist yet; "random" becomes a new password. The values
# go to kubectl through a file descriptor, never on a command line.
ensure_secret() {
  local name=$1 pair key value lines=""
  shift
  if k -n "$NAMESPACE" get secret "$name" > /dev/null 2>&1; then
    ok "secret ${name} exists"
    return
  fi
  if [[ "$mode" == "check" ]]; then
    fail "secret ${name} is missing"
    return
  fi
  for pair in "$@"; do
    key="${pair%%=*}"
    value="${pair#*=}"
    if [[ "$value" == "random" ]]; then value="$(openssl rand -hex 24)"; fi
    lines+="${key}=${value}"$'\n'
  done
  k -n "$NAMESPACE" create secret generic "$name" --from-env-file=<(printf '%s' "$lines") > /dev/null
  ok "secret ${name} created"
}

step "secrets"
ensure_secret postgres-admin password=random
ensure_secret bank-core-db username=bank password=random
ensure_secret bank-open-api-db username=openbanking password=random
ensure_secret keycloak-db username=keycloak password=random
ensure_secret keycloak-admin username=admin password=random

# --- PostgreSQL --------------------------------------------------------------------------------------------

postgres_ready() {
  [[ "$(k -n "$NAMESPACE" get statefulset postgres -o jsonpath='{.status.readyReplicas}' 2> /dev/null)" == "1" ]]
}

step "PostgreSQL"
if [[ "$mode" == "fix" ]]; then
  if h upgrade --install postgres cluster/charts/postgres --namespace "$NAMESPACE" \
    --rollback-on-failure --timeout 5m > /dev/null; then
    ok "PostgreSQL deployed (Helm release postgres)"
  else
    fail "PostgreSQL did not become ready: kubectl --context ${CONTEXT} -n ${NAMESPACE} describe pod postgres-0"
    finish
  fi
elif postgres_ready; then
  ok "PostgreSQL is ready"
else
  fail "PostgreSQL is not ready"
  finish
fi

# Creates or updates a login role and its own database, with the password from a Secret. The SQL goes in on
# stdin, so the password is never on a command line.
ensure_database() {
  local name=$1 secret=$2 exists password
  exists="$(k -n "$NAMESPACE" exec postgres-0 -- psql -U postgres -tAc \
    "SELECT 1 FROM pg_database WHERE datname = '${name}'" 2> /dev/null || true)"
  if [[ "$mode" == "check" ]]; then
    if [[ "$exists" == "1" ]]; then ok "database ${name} exists"; else fail "database ${name} is missing"; fi
    return
  fi
  password="$(k -n "$NAMESPACE" get secret "$secret" -o jsonpath='{.data.password}' | base64 -d)"
  if k -n "$NAMESPACE" exec -i postgres-0 -- psql -v ON_ERROR_STOP=1 -q -U postgres -d postgres > /dev/null <<SQL; then
SELECT 'CREATE ROLE ${name} LOGIN'
  WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${name}')\gexec
ALTER ROLE ${name} WITH LOGIN PASSWORD '${password}';
SELECT 'CREATE DATABASE ${name} OWNER ${name}'
  WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${name}')\gexec
SQL
    ok "database ${name} with its role, password from secret ${secret}"
  else
    fail "could not create database ${name}"
  fi
}

step "databases"
ensure_database bank bank-core-db
ensure_database openbanking bank-open-api-db
ensure_database keycloak keycloak-db

# --- Keycloak ----------------------------------------------------------------------------------------------

keycloak_ready() {
  [[ "$(k -n "$NAMESPACE" get deployment keycloak -o jsonpath='{.status.readyReplicas}' 2> /dev/null)" == "1" ]]
}

step "Keycloak"
if [[ "$mode" == "fix" ]]; then
  echo "   deploying Keycloak; a first start builds it and creates its tables, a few minutes"
  if h upgrade --install keycloak cluster/charts/keycloak --namespace "$NAMESPACE" \
    --rollback-on-failure --timeout 10m > /dev/null; then
    ok "Keycloak deployed (Helm release keycloak), realm bank"
  else
    fail "Keycloak did not become ready: kubectl --context ${CONTEXT} -n ${NAMESPACE} logs deploy/keycloak"
    finish
  fi
elif keycloak_ready; then
  ok "Keycloak is ready"
else
  fail "Keycloak is not ready"
fi

# The whole way a provider comes in, short of a token: TLS with the test CA's certificate, Traefik, the route,
# Keycloak and its realm. The discovery document names the issuer every token will carry.
realm_answers() {
  curl -sS --max-time 10 --cacert "${PKI_DIR}/ca.crt" \
    https://localhost:8443/realms/bank/.well-known/openid-configuration 2> /dev/null \
    | grep -q '"issuer":"https://localhost:8443/realms/bank"'
}

step "HTTPS through Traefik"
# Traefik takes a moment to pick up a new route.
attempts=20
if [[ "$mode" == "check" ]]; then attempts=1; fi
until realm_answers; do
  attempts=$((attempts - 1))
  if ((attempts <= 0)); then break; fi
  sleep 3
done
if ((attempts > 0)); then
  ok "https://localhost:8443/realms/bank answers through Traefik with the test CA's certificate"
else
  fail "Keycloak's realm bank does not answer on https://localhost:8443"
  note "curl -v --cacert ${PKI_DIR}/ca.crt https://localhost:8443/realms/bank/.well-known/openid-configuration"
fi

# --- the account Jenkins deploys with ----------------------------------------------------------------------

# Kubernetes writes the token into the Secret a moment after it is created.
# shellcheck disable=SC2329  # called through wait_for
token_filled_in() {
  [[ -n "$(k -n "$NAMESPACE" get secret jenkins-deployer-token -o jsonpath='{.data.token}' 2> /dev/null)" ]]
}

step "Jenkins deploy account"
if [[ "$mode" == "fix" ]]; then
  k apply -f cluster/platform/jenkins-deployer.yaml > /dev/null
  if wait_for "the deploy token" token_filled_in; then
    token="$(k -n "$NAMESPACE" get secret jenkins-deployer-token -o jsonpath='{.data.token}' | base64 -d)"
    ca="$(kubectl config view --raw --minify --context "$CONTEXT" \
      -o jsonpath='{.clusters[0].cluster.certificate-authority-data}')"
    # Jenkins runs in Docker on k3d's network, where the API is k3d's load balancer on port 6443.
    mkdir -p "$CONFIG_DIR"
    chmod 0700 "$CONFIG_DIR"
    tmp="$(mktemp "${JENKINS_KUBECONFIG}.XXXXXX")"
    cat > "$tmp" << EOF
apiVersion: v1
kind: Config
clusters:
  - name: ${CONTEXT}
    cluster:
      server: https://k3d-${CLUSTER}-serverlb:6443
      certificate-authority-data: ${ca}
users:
  - name: jenkins-deployer
    user:
      token: ${token}
contexts:
  - name: jenkins-deployer@${CONTEXT}
    context:
      cluster: ${CONTEXT}
      user: jenkins-deployer
      namespace: ${NAMESPACE}
current-context: jenkins-deployer@${CONTEXT}
EOF
    # Written over the old file rather than moved in: Jenkins mounts the file, and a mount keeps the old one.
    # Readable inside the Jenkins container (another user id); the folder keeps other users of this machine out.
    cat "$tmp" > "$JENKINS_KUBECONFIG"
    chmod 0644 "$JENKINS_KUBECONFIG"
    rm -f "$tmp"
  else
    fail "the token of jenkins-deployer did not appear"
  fi
fi

# From this machine the same account is checked through the API's local port.
can() {
  kubectl --kubeconfig "$JENKINS_KUBECONFIG" --server "https://127.0.0.1:6550" auth can-i "$@" 2> /dev/null || true
}
if [[ ! -f "$JENKINS_KUBECONFIG" ]]; then
  fail "${JENKINS_KUBECONFIG} is missing"
elif [[ "$(can create deployments -n "$NAMESPACE")" == "yes" && "$(can create statefulsets -n "$NAMESPACE")" == "no" \
  && "$(can get secrets -n kube-system)" == "no" ]]; then
  ok "Jenkins' kubeconfig ${JENKINS_KUBECONFIG}: deploys in ${NAMESPACE}, nothing else"
else
  fail "Jenkins' kubeconfig does not give exactly the deploy rights in ${NAMESPACE}: ${JENKINS_KUBECONFIG}"
fi

note "next: Jenkins with the cluster, then the bank-core and bank-open-api jobs (docs/cluster.md)"
finish
