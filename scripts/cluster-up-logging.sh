# shellcheck shell=bash
# shellcheck disable=SC2153,SC2154  # mode, NAMESPACE, CONTEXT and PKI_DIR are cluster-up.sh's
# cluster-up-logging.sh: the logs part of scripts/cluster-up.sh, which sources it and runs logging_up last. It
# installs the ECK operator and the logging stack (cluster/charts/logging) with their Secrets, gives Kibana a saved
# search, has Elasticsearch keep the logs 7 days, sends a canary line that must arrive masked, and checks that no
# pod of the bank reaches Elasticsearch. Kibana it calls through the bank's Gateway, Elasticsearch through a
# port-forward. It uses cluster-up.sh's settings and helpers (k, h, ok, fail, note, step, wait_within,
# ensure_secret, the folder work); it is not run on its own.
: "${mode:?}" "${NAMESPACE:?}" "${CONTEXT:?}" "${PKI_DIR:?}"

readonly ECK_VERSION="3.5.0"
readonly ECK_CHART_REPO="https://helm.elastic.co"
readonly OPERATOR_NAMESPACE="elastic-system"
readonly LOGGING_NAMESPACE="logging"
readonly COLLECTOR_NAMESPACE="log-collector"
readonly LOGGING_RELEASE="bank-logs"
readonly LOGGING_CHART="cluster/charts/logging"
readonly KIBANA_HOST="kibana.localhost"
readonly KIBANA_URL="https://${KIBANA_HOST}:8443"
# The first start pulls about 3 GB of images, and Elasticsearch and Kibana take a few minutes more.
readonly LOGGING_WAIT_SECONDS="${LOGGING_WAIT_SECONDS:-900}"
# The canary's way through Filebeat, Logstash and Elasticsearch takes seconds; 40 tries 3 seconds apart.
readonly CANARY_ATTEMPTS=40
readonly LOGS_SETUP="${LOGGING_CHART}/setup"

elastic_password=""

# health KIND NAMESPACE: the health ECK reports for the stack's resource of that kind; "unknown" when none.
health() {
  local value
  value="$(k -n "$2" get "$1" "$LOGGING_RELEASE" -o jsonpath='{.status.health}' 2> /dev/null || true)"
  printf '%s' "${value:-unknown}"
}

# shellcheck disable=SC2329  # called through wait_within
logging_stack_ready() {
  [[ "$(health elasticsearch "$LOGGING_NAMESPACE")" =~ ^(green|yellow)$ ]] \
    && [[ "$(health kibana "$LOGGING_NAMESPACE")" == green ]] \
    && [[ "$(health logstash "$LOGGING_NAMESPACE")" == green ]] \
    && [[ "$(health beat "$COLLECTOR_NAMESPACE")" == green ]]
}

# What is left to read when the stack does not come up: each resource's health and the pods that are not running.
logging_report() {
  local namespace
  note "Elasticsearch $(health elasticsearch "$LOGGING_NAMESPACE"), Kibana $(health kibana "$LOGGING_NAMESPACE"),"
  note "Logstash $(health logstash "$LOGGING_NAMESPACE"), Filebeat $(health beat "$COLLECTOR_NAMESPACE")"
  echo "   the stack's pods:"
  for namespace in "$LOGGING_NAMESPACE" "$COLLECTOR_NAMESPACE"; do
    k -n "$namespace" get pods --no-headers 2>&1 | sed 's/^/     /' || true
  done
  note "a pod that waits: kubectl --context ${CONTEXT} -n ${LOGGING_NAMESPACE} describe pod <name> (Events, at the end)"
}

# kibana_call METHOD PATH [CURL ARGUMENTS...]: Kibana's API through the bank's Gateway, as the user elastic.
# Prints the answer's body and, on a line of its own at the end, its HTTP status. The password reaches curl as a
# config file on a pipe, never on a command line.
kibana_call() {
  local method=$1 path=$2
  shift 2
  curl -sS --max-time 30 --cacert "${PKI_DIR}/ca.crt" --resolve "${KIBANA_HOST}:8443:127.0.0.1" \
    -K <(printf 'user = "elastic:%s"\n' "$elastic_password") -H 'kbn-xsrf: cluster-up' -X "$method" \
    -w '\n%{http_code}' "$@" "${KIBANA_URL}${path}" 2> /dev/null || true
}

# Elasticsearch's own API goes through a port-forward: the Kubernetes API carries the connection into the pod, so
# the network policies that keep the bank's pods out of Elasticsearch do not apply, and no port is opened. Kibana's
# console proxy would need no port either, but it is an internal API, which Kibana 9 refuses to outside clients.
# ECK's CA for Elasticsearch checks the connection; the certificate names the service.
readonly ES_SERVICE="${LOGGING_RELEASE}-es-http"
es_tunnel_pid=""
es_port=""

# es_tunnel_open: the port-forward, on a free local port kubectl picks, and a first call through it.
es_tunnel_open() {
  local log="${work:?}/es-port-forward.log" tries
  step "Elasticsearch's API"
  k -n "$LOGGING_NAMESPACE" get secret "${ES_SERVICE}-certs-public" -o jsonpath='{.data.ca\.crt}' 2> /dev/null \
    | base64 -d > "${work}/es-ca.crt" 2> /dev/null || true
  if [[ ! -s "${work}/es-ca.crt" ]]; then
    fail "ECK's Secret ${ES_SERVICE}-certs-public in ${LOGGING_NAMESPACE} has no CA for Elasticsearch"
    return 1
  fi
  # kubectl itself rather than k, so that the PID is kubectl's and closing the port-forward ends it.
  kubectl --context "$CONTEXT" -n "$LOGGING_NAMESPACE" port-forward "svc/${ES_SERVICE}" :9200 > "$log" 2>&1 &
  es_tunnel_pid=$!
  for ((tries = 0; tries < 50; tries++)); do
    es_port="$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9][0-9]*\) -> .*/\1/p' "$log" | head -n 1)"
    if [[ -n "$es_port" ]]; then break; fi
    sleep 0.2
  done
  if [[ -z "$es_port" ]]; then
    fail "kubectl could not forward a port to Elasticsearch: $(head -c 300 "$log")"
    es_tunnel_close
    return 1
  fi
  if [[ "$(status_of "$(es_call GET /)")" != 200 ]]; then
    fail "Elasticsearch does not answer through the port-forward to ${ES_SERVICE}, or not with ECK's certificate"
    es_tunnel_close
    return 1
  fi
  ok "Elasticsearch answers through a port-forward, its certificate checked against ECK's CA"
}

# es_tunnel_close: ends the port-forward. cluster-up.sh calls it on exit too, so Ctrl-C leaves none running.
es_tunnel_close() {
  if [[ -n "$es_tunnel_pid" ]]; then
    kill "$es_tunnel_pid" 2> /dev/null || true
    wait "$es_tunnel_pid" 2> /dev/null || true
    es_tunnel_pid=""
  fi
}

# es_call METHOD PATH [CURL ARGUMENTS...]: Elasticsearch's API through the port-forward, as the user elastic.
# Prints as kibana_call does.
es_call() {
  local method=$1 path=$2
  shift 2
  curl -sS --max-time 30 --cacert "${work}/es-ca.crt" --resolve "${ES_SERVICE}:${es_port}:127.0.0.1" \
    -K <(printf 'user = "elastic:%s"\n' "$elastic_password") -X "$method" -w '\n%{http_code}' "$@" \
    "https://${ES_SERVICE}:${es_port}/${path#/}" 2> /dev/null || true
}

# status_of ANSWER / body_of ANSWER: the two parts of what kibana_call and es_call print.
status_of() { printf '%s' "${1##*$'\n'}"; }
body_of() { printf '%s' "${1%$'\n'*}"; }

# shellcheck disable=SC2329  # called through wait_within
kibana_answers() {
  [[ "$(status_of "$(kibana_call GET /api/status)")" == 200 ]]
}

# --- the ECK operator ----------------------------------------------------------------------------------------

logging_operator() {
  local image ready namespace
  step "the ECK operator"
  if [[ "$mode" == "fix" ]]; then
    k apply -f cluster/platform/logging-namespaces.yaml > /dev/null
    echo "   installing the ECK operator ${ECK_VERSION}; the first time pulls its image"
    if ! h upgrade --install elastic-operator eck-operator --repo "$ECK_CHART_REPO" --version "$ECK_VERSION" \
      --namespace "$OPERATOR_NAMESPACE" -f cluster/eck-operator.yaml --rollback-on-failure --timeout 5m \
      > /dev/null; then
      fail "the ECK operator did not start: kubectl --context ${CONTEXT} -n ${OPERATOR_NAMESPACE} describe pods"
      return 1
    fi
  fi
  for namespace in "$OPERATOR_NAMESPACE" "$LOGGING_NAMESPACE" "$COLLECTOR_NAMESPACE"; do
    if ! k get namespace "$namespace" > /dev/null 2>&1; then
      fail "namespace ${namespace} is missing (cluster/platform/logging-namespaces.yaml)"
      return 1
    fi
  done
  image="$(k -n "$OPERATOR_NAMESPACE" get statefulset elastic-operator \
    -o jsonpath='{.spec.template.spec.containers[0].image}' 2> /dev/null || true)"
  ready="$(k -n "$OPERATOR_NAMESPACE" get statefulset elastic-operator -o jsonpath='{.status.readyReplicas}' \
    2> /dev/null || true)"
  if [[ "$image" != *":${ECK_VERSION}" ]]; then
    fail "the ECK operator ${ECK_VERSION} is not installed (found: ${image:-none}); run this script without --check"
    return 1
  fi
  if [[ "$ready" != 1 ]]; then
    fail "the ECK operator is not ready: kubectl --context ${CONTEXT} -n ${OPERATOR_NAMESPACE} describe pods"
    return 1
  fi
  ok "ECK operator ${ECK_VERSION} runs (Helm release elastic-operator), for the namespaces logging and log-collector"
}

# --- the stack -----------------------------------------------------------------------------------------------

logging_stack() {
  step "the logging stack"
  # The key of the masking's pseudonyms, and the user Kibana's readers sign in as (ECK's file realm).
  ensure_secret "$LOGGING_NAMESPACE" log-masking pseudonym-key=random
  ensure_secret "$LOGGING_NAMESPACE" log-reader username=log-reader password=random roles=viewer
  if [[ "$mode" == "fix" ]]; then
    # ECK rewrites its resources whole when it adds its annotations, and the defaults Go adds on the way make it the
    # owner of lists the chart sets (Elasticsearch's nodeSets, Logstash's containers) as server-side apply counts.
    # What the chart sets is the chart's: Helm takes those fields back instead of stopping at the conflict.
    if ! h upgrade --install "$LOGGING_RELEASE" "$LOGGING_CHART" --namespace "$LOGGING_NAMESPACE" --force-conflicts \
      > /dev/null; then
      fail "Helm could not install the logging stack: helm --kube-context ${CONTEXT} -n logging status bank-logs"
      return 1
    fi
    echo "   waiting for Elasticsearch, Kibana, Logstash and Filebeat; the first time pulls about 3 GB of images"
  fi
  local ready=0
  if [[ "$mode" == "check" ]]; then
    logging_stack_ready && ready=1
  else
    wait_within "$LOGGING_WAIT_SECONDS" "the logging stack" logging_stack_ready && ready=1
  fi
  if ((!ready)); then
    fail "the logging stack is not ready (Helm release ${LOGGING_RELEASE})"
    logging_report
    return 1
  fi
  ok "Elasticsearch $(health elasticsearch "$LOGGING_NAMESPACE"), Kibana, Logstash and Filebeat green"
  elastic_password="$(k -n "$LOGGING_NAMESPACE" get secret "${LOGGING_RELEASE}-es-elastic-user" \
    -o jsonpath='{.data.elastic}' 2> /dev/null | base64 -d || true)"
  if [[ -z "$elastic_password" ]]; then
    fail "the Secret ${LOGGING_RELEASE}-es-elastic-user has no password for the user elastic"
    return 1
  fi
  # Traefik takes a moment to pick up Kibana's route.
  local attempts=20
  if [[ "$mode" == "check" ]]; then attempts=1; fi
  until kibana_answers; do
    attempts=$((attempts - 1))
    if ((attempts <= 0)); then
      fail "Kibana does not answer on ${KIBANA_URL}"
      note "curl -v --cacert ${PKI_DIR}/ca.crt ${KIBANA_URL}/api/status"
      return 1
    fi
    sleep 3
  done
  ok "Kibana answers on ${KIBANA_URL} through the Gateway"
}

# --- how long the logs stay ----------------------------------------------------------------------------------

logging_retention() {
  local answer settings
  step "how long Elasticsearch keeps the logs"
  # One copy of the settings: in the component template logs@custom for the indices to come, and on the indices
  # of the data stream already there.
  settings="$(cat "${LOGS_SETUP}/logs-settings.json")"
  if [[ "$mode" == "fix" ]]; then
    answer="$(es_call PUT _ilm/policy/bank-logs -H 'Content-Type: application/json' \
      --data-binary "@${LOGS_SETUP}/ilm-policy.json")"
    if [[ "$(status_of "$answer")" != 200 ]]; then
      fail "Elasticsearch refused the lifecycle policy bank-logs: $(body_of "$answer" | head -c 300)"
      return 1
    fi
    answer="$(es_call PUT _component_template/logs@custom -H 'Content-Type: application/json' \
      --data-binary "{\"template\": {\"settings\": ${settings}}, \"_meta\": {\"description\": \"bank-open-api\"}}")"
    if [[ "$(status_of "$answer")" != 200 ]]; then
      fail "Elasticsearch refused the component template logs@custom: $(body_of "$answer" | head -c 300)"
      return 1
    fi
    if [[ "$(status_of "$(es_call GET _data_stream/logs-bank-default)")" == 200 ]]; then
      answer="$(es_call PUT logs-bank-default/_settings -H 'Content-Type: application/json' --data-binary "$settings")"
      if [[ "$(status_of "$answer")" != 200 ]]; then
        fail "Elasticsearch refused the settings of logs-bank-default: $(body_of "$answer" | head -c 300)"
        return 1
      fi
    fi
  fi
  answer="$(es_call GET _ilm/policy/bank-logs)"
  if [[ "$(status_of "$answer")" != 200 ]] || ! grep -q '"delete":{"min_age":"7d"' <<< "$answer"; then
    fail "the lifecycle policy bank-logs is missing or deletes at another age; run this script without --check"
    return 1
  fi
  answer="$(es_call GET _component_template/logs@custom)"
  if [[ "$(status_of "$answer")" != 200 ]] || ! grep -q '"lifecycle":{"name":"bank-logs"}' <<< "$answer" \
    || ! grep -q '"number_of_replicas":"0"' <<< "$answer"; then
    fail "the component template logs@custom does not name the policy bank-logs; run this script without --check"
    return 1
  fi
  ok "logs kept 7 days: lifecycle policy bank-logs (an index a day) through the component template logs@custom"
}

# --- Kibana's saved search ------------------------------------------------------------------------------------

logging_kibana() {
  local answer
  step "Kibana's saved search"
  if [[ "$mode" == "fix" ]]; then
    answer="$(kibana_call POST '/api/saved_objects/_import?overwrite=true' \
      -F "file=@${LOGS_SETUP}/kibana-objects.ndjson")"
    if [[ "$(status_of "$answer")" != 200 ]] || ! grep -q '"success":true' <<< "$answer"; then
      fail "Kibana did not import ${LOGS_SETUP}/kibana-objects.ndjson: $(body_of "$answer" | head -c 300)"
      return 1
    fi
  fi
  # Kibana refuses the export when one of the objects asked for is missing.
  answer="$(kibana_call POST /api/saved_objects/_export -H 'Content-Type: application/json' --data-binary \
    '{"objects": [{"type": "index-pattern", "id": "bank-logs"}, {"type": "search", "id": "bank-logs-requests"}]}')"
  if [[ "$(status_of "$answer")" == 200 ]] && grep -q '"exportedCount":2' <<< "$answer"; then
    ok "Kibana's data view logs-bank-* and its saved search \"Bank: requests by id\""
  else
    fail "Kibana has no saved search bank-logs-requests; run this script without --check"
  fi
}

# --- the canary ---------------------------------------------------------------------------------------------

# canary_pod NAME IMAGE LINE: a pod in the bank's namespace that writes LINE to its log once, under Pod Security
# "restricted". LINE holds no single quote, so it fits YAML's single quotes as it is.
canary_pod() {
  cat << EOF
apiVersion: v1
kind: Pod
metadata:
  name: $1
  namespace: ${NAMESPACE}
  labels:
    app.kubernetes.io/name: log-canary
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext:
    runAsNonRoot: true
    runAsUser: 65534
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: canary
      image: $2
      command: ["sh", "-c", 'printf "%s\\n" "\$LINE"']
      env:
        - name: LINE
          value: '$3'
      securityContext:
        allowPrivilegeEscalation: false
        capabilities:
          drop: [ALL]
      resources:
        requests:
          cpu: 10m
          memory: 16Mi
        limits:
          memory: 16Mi
EOF
}

canary_answer=""
canary_stored() {
  canary_answer="$(es_call POST 'logs-bank-*/_search' -H 'Content-Type: application/json' --data-binary \
    "{\"query\": {\"match_phrase\": {\"message\": \"log canary ${canary_id}\"}}, \"fields\": [\"user.name\"]}")"
  [[ "$(status_of "$canary_answer")" == 200 ]] && grep -q '"total":{"value":[1-9]' <<< "$canary_answer"
}

# A line as the services write it, with a made-up IBAN (check digits 00, which no real IBAN has) and username,
# goes the whole way: the pod's log, Filebeat, Logstash, Elasticsearch. It must arrive masked.
logging_canary() {
  local image iban masked_iban username line pod
  step "a canary line through the logs"
  if [[ "$mode" == "check" ]]; then
    note "the canary line runs without --check"
    return 0
  fi
  canary_id="$(openssl rand -hex 8)"
  iban="UA00322313$(printf '%019d' "$((16#${canary_id:0:15}))")"
  masked_iban="UA***********************${iban: -4}"
  username="canary.user.${canary_id}"
  line="{\"log.level\":\"WARN\",\"service.name\":\"log-canary\",\"user.name\":\"${username}\",\"message\":\"log canary"
  line+=" ${canary_id}: transfer from ${iban} refused for username=\\\"${username}\\\"; Detail: Key (iban)=(${iban})"
  line+=" already exists.\"}"
  pod="log-canary-${canary_id}"
  image="$(k -n "$NAMESPACE" get statefulset postgres -o jsonpath='{.spec.template.spec.containers[0].image}')"
  # Canaries of earlier runs that were left behind.
  k -n "$NAMESPACE" delete pod -l app.kubernetes.io/name=log-canary --ignore-not-found > /dev/null
  if ! canary_pod "$pod" "$image" "$line" | k create -f - > /dev/null; then
    fail "could not start the canary pod ${pod}"
    return 1
  fi
  local attempts="$CANARY_ATTEMPTS"
  until canary_stored; do
    attempts=$((attempts - 1))
    if ((attempts <= 0)); then
      fail "the canary line did not reach Elasticsearch within $((CANARY_ATTEMPTS * 3)) seconds"
      note "what it wrote: kubectl --context ${CONTEXT} -n ${NAMESPACE} logs ${pod}; then Filebeat's, Logstash's"
      return 1
    fi
    sleep 3
  done
  if grep -qF "$iban" <<< "$canary_answer" || grep -qF "$username" <<< "$canary_answer"; then
    fail "the canary line was stored with its IBAN or username unmasked"
  elif ! grep -qF "$masked_iban" <<< "$canary_answer" \
    || ! grep -qF 'Key (iban)=(masked) already exists' <<< "$canary_answer" \
    || ! grep -qE '"user\.name":\["masked-[0-9a-f]{12}"\]' <<< "$canary_answer"; then
    fail "the canary line was stored, but not masked as mask.rb masks it"
  else
    ok "a line with an IBAN and a username arrived masked: ${masked_iban}, a pseudonym, the database detail gone"
  fi
  k -n "$NAMESPACE" delete pod "$pod" --wait=false > /dev/null 2>&1 || true
}

# --- the network policies -------------------------------------------------------------------------------------

logging_policies() {
  local probe
  step "the logging stack's network policies"
  # Elasticsearch runs and answers Kibana, so a connection it does not take is the policy. busybox's wget in the
  # PostgreSQL pod tells a refused connection from one made: an HTTP request to the TLS port fails otherwise.
  if probe="$(k -n "$NAMESPACE" exec postgres-0 -- wget -q -T 5 -O /dev/null \
    "http://${LOGGING_RELEASE}-es-http.${LOGGING_NAMESPACE}:9200/" 2>&1)"; then
    fail "a pod of the bank reaches Elasticsearch: the logging stack's network policies are not enforced"
  elif [[ "$probe" == *"can't connect to remote host"* || "$probe" == *"download timed out"* ]]; then
    ok "no pod of the bank reaches Elasticsearch; Kibana, Logstash and Filebeat do their part (above)"
  elif [[ "$probe" == *"bad address"* || "$probe" == *"executable file not found"* ]]; then
    fail "the logging stack's network policies could not be checked from the PostgreSQL pod: ${probe}"
  else
    fail "a pod of the bank reaches Elasticsearch: the logging stack's network policies are not enforced"
    note "the probe from postgres-0 said: ${probe}"
  fi
}

# Each step reports its own problems; a step that fails returns 1, and the steps that need it are not run.
logging_up() {
  logging_operator || return 0
  logging_stack || return 0
  logging_kibana || true
  if es_tunnel_open; then
    if logging_retention; then
      logging_canary || true
    fi
    es_tunnel_close
  fi
  logging_policies || true
}
