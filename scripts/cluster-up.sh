#!/usr/bin/env bash
# cluster-up.sh: creates the k3d cluster "bank" and its platform: the bank namespace, the Gateway, PostgreSQL with
# a database for bank-core and one for bank-open-api, and the account Jenkins deploys with. Safe to run again: it
# creates or fixes only what is missing, and starts the cluster if it was stopped. The services themselves come
# from Jenkins (docs/cluster.md).
#
#   bash scripts/cluster-up.sh --check   report, change nothing
#   bash scripts/cluster-up.sh           create or fix what is missing
set -euo pipefail

readonly SCRIPT_NAME="cluster-up.sh"
readonly PROJECT_VERSION="0.2.0"
readonly CLUSTER="bank"
readonly CONTEXT="k3d-bank"
readonly NAMESPACE="bank"
# API, HTTP, HTTPS and registry, as in cluster/k3d.yaml
readonly HOST_PORTS=(6550 8480 8443 5050)
readonly JENKINS_KUBECONFIG="${HOME}/.config/bank-open-api/jenkins-kubeconfig.yaml"
readonly WAIT_SECONDS=300

echo "== ${SCRIPT_NAME} (bank-open-api ${PROJECT_VERSION}): k3d cluster '${CLUSTER}' and its platform =="

mode="fix"
for arg in "$@"; do
  case "$arg" in
    --check) mode="check" ;;
    -h | --help)
      sed -n '2,9p' "$0"
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
if [[ ! -f cluster/k3d.yaml || ! -d cluster/charts/postgres ]]; then
  echo "FAIL  ${project_dir} is not the unpacked bank-open-api project: cluster/ is missing" >&2
  exit 1
fi

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
for tool in docker k3d kubectl helm openssl ss base64; do
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

step "Traefik with the Gateway API"
if ! k -n kube-system get helmchartconfig traefik > /dev/null 2>&1; then
  if [[ "$mode" == "check" ]]; then
    fail "Traefik's settings (cluster/traefik-config.yaml) are not in the cluster"
  else
    k apply -f cluster/traefik-config.yaml > /dev/null
    ok "applied cluster/traefik-config.yaml"
  fi
fi
# k3s installs Traefik with a Helm job after start; the GatewayClass appears once the provider is on.
if wait_for "Traefik" k -n kube-system rollout status deployment/traefik --timeout=10s \
  && wait_for "the traefik GatewayClass" k wait --for=condition=Accepted gatewayclass/traefik --timeout=10s; then
  ok "Traefik is running and its GatewayClass is accepted"
else
  fail "Traefik or its GatewayClass is not ready"
  note "kubectl --context ${CONTEXT} -n kube-system get pods,helmcharts,helmchartconfigs"
  finish
fi

# --- namespace and Gateway ---------------------------------------------------------------------------------

step "namespace ${NAMESPACE} and its Gateway"
for manifest in cluster/platform/namespace.yaml cluster/platform/gateway.yaml; do
  if [[ "$mode" == "fix" ]]; then
    k apply -f "$manifest" > /dev/null
  fi
done
if k get namespace "$NAMESPACE" > /dev/null 2>&1; then
  ok "namespace ${NAMESPACE} (Pod Security: $(k get namespace "$NAMESPACE" \
    -o jsonpath='{.metadata.labels.pod-security\.kubernetes\.io/enforce}'))"
else
  fail "namespace ${NAMESPACE} is missing"
  finish
fi
if k -n "$NAMESPACE" wait --for=condition=Programmed gateway/bank-gateway --timeout=120s > /dev/null 2>&1; then
  ok "Gateway bank-gateway is programmed: http://localhost:8480"
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
    mkdir -p "$(dirname "$JENKINS_KUBECONFIG")"
    chmod 0700 "$(dirname "$JENKINS_KUBECONFIG")"
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
