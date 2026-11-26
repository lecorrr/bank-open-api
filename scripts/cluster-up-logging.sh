# shellcheck shell=bash
# shellcheck disable=SC2153,SC2154  # mode, NAMESPACE, CONTEXT and PKI_DIR are cluster-up.sh's
# cluster-up-logging.sh: the logs and traces part of scripts/cluster-up.sh, which sources it and runs logging_up
# last. It installs the ECK operator and the logging stack (cluster/charts/logging: Elasticsearch, Kibana, Logstash,
# Filebeat, APM Server and the trace collector) with their Secrets, gives Kibana a saved search, has Elasticsearch
# keep the logs and traces 7 days, failure stores included, sends a canary line and a canary span that must arrive
# masked, and checks that no pod of the bank reaches Elasticsearch or APM Server, nor the collector unless it sends
# spans. Kibana it calls through the bank's Gateway, Elasticsearch and the collector through port-forwards. It uses
# cluster-up.sh's settings and helpers (k, h, ok, fail, note, step, wait_within, ensure_secret, the folder work);
# it is not run on its own.
: "${mode:?}" "${NAMESPACE:?}" "${CONTEXT:?}" "${PKI_DIR:?}"

readonly ECK_VERSION="3.5.0"
readonly ECK_CHART_REPO="https://helm.elastic.co"
readonly OPERATOR_NAMESPACE="elastic-system"
readonly LOGGING_NAMESPACE="logging"
readonly COLLECTOR_NAMESPACE="log-collector"
readonly LOGGING_RELEASE="bank-logs"
# The traces' part of the stack, named in the chart: ECK's APM Server and the OpenTelemetry Collector before it.
readonly APM_SERVER="bank-traces"
readonly TRACE_COLLECTOR="trace-collector"
readonly LOGGING_CHART="cluster/charts/logging"
readonly KIBANA_HOST="kibana.localhost"
readonly KIBANA_URL="https://${KIBANA_HOST}:8443"
# The first start pulls about 3 GB of images, and Elasticsearch and Kibana take a few minutes more.
readonly LOGGING_WAIT_SECONDS="${LOGGING_WAIT_SECONDS:-900}"
# The canaries' way through Filebeat, Logstash and Elasticsearch, or the collector, APM Server and Elasticsearch,
# takes seconds; 40 tries 3 seconds apart.
readonly CANARY_ATTEMPTS=40
readonly LOGS_SETUP="${LOGGING_CHART}/setup"

elastic_password=""

# health KIND NAMESPACE [NAME]: the health ECK reports for the stack's resource of that kind, named after the release
# unless NAME says otherwise; "unknown" when there is none.
health() {
  local value
  value="$(k -n "$2" get "$1" "${3:-$LOGGING_RELEASE}" -o jsonpath='{.status.health}' 2> /dev/null || true)"
  printf '%s' "${value:-unknown}"
}

# The trace collector runs this version's configuration once its Deployment's last change is rolled out and every
# replica of it is ready: its readiness probe asks the collector's health check.
collector_current() {
  local fields resource generation observed replicas updated available
  fields='{.metadata.generation}|{.status.observedGeneration}|{.spec.replicas}'
  fields+='|{.status.updatedReplicas}|{.status.availableReplicas}'
  resource="$(k -n "$LOGGING_NAMESPACE" get deployment "$TRACE_COLLECTOR" -o jsonpath="$fields" 2> /dev/null || true)"
  IFS='|' read -r generation observed replicas updated available <<< "$resource"
  [[ -n "$generation" && "$generation" == "$observed" && -n "$replicas" && "$replicas" == "$updated" \
    && "$replicas" == "$available" ]]
}

# Logstash's health lags a change to it: right after an upgrade that changes its pipeline or the masking script it
# is still green from the pod about to be replaced, and the canary would race Logstash's restart. Logstash runs what
# the chart says once ECK has seen the resource's last change, the StatefulSet ECK made for it has seen its own, and
# every pod of the StatefulSet is of its latest revision; Logstash's health then says whether they are ready.
logstash_current() {
  local resource generation observed set_generation set_observed replicas updated
  resource="$(k -n "$LOGGING_NAMESPACE" get logstash "$LOGGING_RELEASE" \
    -o jsonpath='{.metadata.generation}|{.status.observedGeneration}' 2> /dev/null || true)"
  IFS='|' read -r generation observed <<< "$resource"
  [[ -n "$generation" && "$generation" == "$observed" ]] || return 1
  resource="$(k -n "$LOGGING_NAMESPACE" get statefulset "${LOGGING_RELEASE}-ls" \
    -o jsonpath='{.metadata.generation}|{.status.observedGeneration}|{.spec.replicas}|{.status.updatedReplicas}' \
    2> /dev/null || true)"
  IFS='|' read -r set_generation set_observed replicas updated <<< "$resource"
  [[ -n "$set_generation" && "$set_generation" == "$set_observed" && -n "$replicas" && "$replicas" == "$updated" ]]
}

# shellcheck disable=SC2329  # called through wait_within
logging_stack_ready() {
  [[ "$(health elasticsearch "$LOGGING_NAMESPACE")" =~ ^(green|yellow)$ ]] \
    && [[ "$(health kibana "$LOGGING_NAMESPACE")" == green ]] \
    && [[ "$(health logstash "$LOGGING_NAMESPACE")" == green ]] && logstash_current \
    && [[ "$(health beat "$COLLECTOR_NAMESPACE")" == green ]] \
    && [[ "$(health apmserver "$LOGGING_NAMESPACE" "$APM_SERVER")" == green ]] && collector_current
}

# What is left to read when the stack does not come up: each resource's health and the pods that are not running.
logging_report() {
  local namespace
  note "Elasticsearch $(health elasticsearch "$LOGGING_NAMESPACE"), Kibana $(health kibana "$LOGGING_NAMESPACE"),"
  note "Logstash $(health logstash "$LOGGING_NAMESPACE"), Filebeat $(health beat "$COLLECTOR_NAMESPACE"),"
  note "APM Server $(health apmserver "$LOGGING_NAMESPACE" "$APM_SERVER")"
  logstash_current || note "Logstash does not run this version's pipeline and masking script yet"
  collector_current || note "the trace collector does not run this version's configuration, or is not ready, yet"
  echo "   the stack's pods:"
  for namespace in "$LOGGING_NAMESPACE" "$COLLECTOR_NAMESPACE"; do
    k -n "$namespace" get pods --no-headers 2>&1 | sed 's/^/     /' || true
  done
  note "a pod that waits: kubectl --context ${CONTEXT} -n ${LOGGING_NAMESPACE} describe pod <name> (Events, at the end)"
  note "Logstash's log, where a failed test of mask.rb shows:"
  note "kubectl --context ${CONTEXT} -n ${LOGGING_NAMESPACE} logs ${LOGGING_RELEASE}-ls-0"
  note "the collector's, where a configuration it refuses shows:"
  note "kubectl --context ${CONTEXT} -n ${LOGGING_NAMESPACE} logs deployment/${TRACE_COLLECTOR}"
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

# Elasticsearch's own API and the collector's OTLP port are reached through port-forwards: the Kubernetes API carries
# the connection into the pod, so the network policies that keep the bank's pods out do not apply, and no port is
# opened. Kibana's console proxy would need no port either, but it is an internal API, which Kibana 9 refuses to
# outside clients. ECK's CA for Elasticsearch checks that connection; the certificate names the service.
readonly ES_SERVICE="${LOGGING_RELEASE}-es-http"
declare -A tunnel_pid=() tunnel_port=()

# tunnel_open NAME SERVICE PORT WHAT: a port-forward to the service's port in the logging namespace, on a free local
# port kubectl picks, which goes into tunnel_port[NAME]. Says why, naming WHAT, and returns 1 when kubectl names no
# local port.
tunnel_open() {
  local name=$1 service=$2 port=$3 what=$4 log="${work:?}/${1}-port-forward.log" tries
  # kubectl itself rather than k, so that the PID is kubectl's and closing the port-forward ends it.
  kubectl --context "$CONTEXT" -n "$LOGGING_NAMESPACE" port-forward "svc/${service}" ":${port}" > "$log" 2>&1 &
  tunnel_pid[$name]=$!
  tunnel_port[$name]=""
  for ((tries = 0; tries < 50; tries++)); do
    tunnel_port[$name]="$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9][0-9]*\) -> .*/\1/p' "$log" | head -n 1)"
    if [[ -n "${tunnel_port[$name]}" ]]; then return 0; fi
    sleep 0.2
  done
  fail "kubectl could not forward a port to ${what}: $(head -c 300 "$log")"
  tunnel_close "$name"
  return 1
}

# tunnel_close NAME: ends that port-forward, if it runs.
tunnel_close() {
  local pid="${tunnel_pid[$1]:-}"
  if [[ -n "$pid" ]]; then
    kill "$pid" 2> /dev/null || true
    wait "$pid" 2> /dev/null || true
  fi
  unset "tunnel_pid[$1]" "tunnel_port[$1]"
}

# logging_tunnels_close: ends every port-forward. cluster-up.sh calls it on exit too, so Ctrl-C leaves none running.
logging_tunnels_close() {
  local name
  for name in "${!tunnel_pid[@]}"; do tunnel_close "$name"; done
}

# es_tunnel_open: the port-forward to Elasticsearch, and a first call through it.
es_tunnel_open() {
  step "Elasticsearch's API"
  k -n "$LOGGING_NAMESPACE" get secret "${ES_SERVICE}-certs-public" -o jsonpath='{.data.ca\.crt}' 2> /dev/null \
    | base64 -d > "${work}/es-ca.crt" 2> /dev/null || true
  if [[ ! -s "${work}/es-ca.crt" ]]; then
    fail "ECK's Secret ${ES_SERVICE}-certs-public in ${LOGGING_NAMESPACE} has no CA for Elasticsearch"
    return 1
  fi
  tunnel_open es "$ES_SERVICE" 9200 Elasticsearch || return 1
  if [[ "$(status_of "$(es_call GET /)")" != 200 ]]; then
    fail "Elasticsearch does not answer through the port-forward to ${ES_SERVICE}, or not with ECK's certificate"
    tunnel_close es
    return 1
  fi
  ok "Elasticsearch answers through a port-forward, its certificate checked against ECK's CA"
}

# es_call METHOD PATH [CURL ARGUMENTS...]: Elasticsearch's API through the port-forward, as the user elastic.
# Prints as kibana_call does.
es_call() {
  local method=$1 path=$2 port="${tunnel_port[es]:-}"
  shift 2
  curl -sS --max-time 30 --cacert "${work}/es-ca.crt" --resolve "${ES_SERVICE}:${port}:127.0.0.1" \
    -K <(printf 'user = "elastic:%s"\n' "$elastic_password") -X "$method" -w '\n%{http_code}' "$@" \
    "https://${ES_SERVICE}:${port}/${path#/}" 2> /dev/null || true
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
    echo "   waiting for Elasticsearch, Kibana, Logstash, Filebeat, APM Server and the trace collector; the first time"
    echo "   pulls about 3 GB of images"
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
  ok "Elasticsearch $(health elasticsearch "$LOGGING_NAMESPACE"), Kibana, Logstash, Filebeat and APM Server green, the"
  note "trace collector ready"
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

# --- how long the logs and traces stay -----------------------------------------------------------------------

# custom_template TYPE FILE: the component template TYPE@custom with the settings in FILE, which every TYPE-*-* data
# stream of Elasticsearch composes, the APM ones included; the same settings on the data streams already there, which
# a template no longer changes. Says what was refused and returns 1.
custom_template() {
  local type=$1 file=$2 settings answer
  settings="$(cat "$file")"
  answer="$(es_call PUT "_component_template/${type}@custom" -H 'Content-Type: application/json' \
    --data-binary "{\"template\": {\"settings\": ${settings}}, \"_meta\": {\"description\": \"bank-open-api\"}}")"
  if [[ "$(status_of "$answer")" != 200 ]]; then
    fail "Elasticsearch refused the component template ${type}@custom: $(body_of "$answer" | head -c 300)"
    return 1
  fi
  # A pattern that matches no data stream yet changes nothing; Elasticsearch answers 200 all the same.
  answer="$(es_call PUT "${type}-*-*/_settings?allow_no_indices=true" -H 'Content-Type: application/json' \
    --data-binary "$settings")"
  if [[ "$(status_of "$answer")" != 200 ]]; then
    fail "Elasticsearch refused the settings of the ${type}-*-* data streams: $(body_of "$answer" | head -c 300)"
    return 1
  fi
}

logging_retention() {
  local answer policy type
  step "how long Elasticsearch keeps the logs and traces"
  # One lifecycle under two names: an index a day, deleted 7 days later. The logs' data streams take bank-logs,
  # the traces' bank-traces (the files logs-settings.json and traces-settings.json); APM Server's metrics keep the
  # retention Elasticsearch's APM templates give them. Every failure store, where Elasticsearch keeps documents it
  # could not index, takes 7 days from one cluster setting instead of its default 30.
  if [[ "$mode" == "fix" ]]; then
    for policy in bank-logs bank-traces; do
      answer="$(es_call PUT "_ilm/policy/${policy}" -H 'Content-Type: application/json' \
        --data-binary "@${LOGS_SETUP}/ilm-policy.json")"
      if [[ "$(status_of "$answer")" != 200 ]]; then
        fail "Elasticsearch refused the lifecycle policy ${policy}: $(body_of "$answer" | head -c 300)"
        return 1
      fi
    done
    for type in logs traces metrics; do
      custom_template "$type" "${LOGS_SETUP}/${type}-settings.json" || return 1
    done
    answer="$(es_call PUT _cluster/settings -H 'Content-Type: application/json' \
      --data-binary "@${LOGS_SETUP}/cluster-settings.json")"
    if [[ "$(status_of "$answer")" != 200 ]]; then
      fail "Elasticsearch refused the failure stores' retention: $(body_of "$answer" | head -c 300)"
      return 1
    fi
  fi
  for policy in bank-logs bank-traces; do
    answer="$(es_call GET "_ilm/policy/${policy}")"
    if [[ "$(status_of "$answer")" != 200 ]] || ! grep -q '"delete":{"min_age":"7d"' <<< "$answer"; then
      fail "the lifecycle policy ${policy} is missing or deletes at another age; run this script without --check"
      return 1
    fi
  done
  answer="$(es_call GET _component_template/logs@custom)"
  if [[ "$(status_of "$answer")" != 200 ]] || ! grep -q '"lifecycle":{"name":"bank-logs"}' <<< "$answer" \
    || ! grep -q '"number_of_replicas":"0"' <<< "$answer"; then
    fail "the component template logs@custom does not name the policy bank-logs; run this script without --check"
    return 1
  fi
  answer="$(es_call GET _component_template/traces@custom)"
  if [[ "$(status_of "$answer")" != 200 ]] || ! grep -q '"lifecycle":{"name":"bank-traces"}' <<< "$answer" \
    || ! grep -q '"number_of_replicas":"0"' <<< "$answer"; then
    fail "the component template traces@custom does not name the policy bank-traces; run this script without --check"
    return 1
  fi
  answer="$(es_call GET _component_template/metrics@custom)"
  if [[ "$(status_of "$answer")" != 200 ]] || ! grep -q '"number_of_replicas":"0"' <<< "$answer"; then
    fail "the component template metrics@custom does not leave out replicas; run this script without --check"
    return 1
  fi
  answer="$(es_call GET '_cluster/settings?flat_settings=true&filter_path=persistent')"
  if [[ "$(status_of "$answer")" != 200 ]] \
    || ! grep -q '"data_streams.lifecycle.retention.failures_default":"7d"' <<< "$answer"; then
    fail "the failure stores keep their documents longer than 7 days; run this script without --check"
    return 1
  fi
  ok "logs and traces kept 7 days: lifecycle policies bank-logs and bank-traces (an index a day) through"
  note "the component templates logs@custom and traces@custom; failure stores 7 days too"
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
  elif grep -qF '"original":' <<< "$canary_answer"; then
    fail "the canary line was stored with a copy of itself as it came (event.original): Logstash keeps it"
  elif ! grep -qF "$masked_iban" <<< "$canary_answer" \
    || ! grep -qF 'Key (iban)=(masked) already exists' <<< "$canary_answer" \
    || ! grep -qE '"user\.name":\["masked-[0-9a-f]{12}"\]' <<< "$canary_answer"; then
    fail "the canary line was stored, but not masked as mask.rb masks it"
  else
    ok "a line with an IBAN and a username arrived masked: ${masked_iban}, a pseudonym, the database detail gone"
  fi
  k -n "$NAMESPACE" delete pod "$pod" --wait=false > /dev/null 2>&1 || true
}

# --- the trace canary ---------------------------------------------------------------------------------------

trace_answer=""
trace_stored() {
  trace_answer="$(es_call POST 'traces-apm*,logs-apm.error*/_search' -H 'Content-Type: application/json' \
    --data-binary "{\"query\": {\"term\": {\"trace.id\": \"${trace_id}\"}},
                    \"fields\": [\"url.path\", \"error.exception.message\"]}")"
  [[ "$(status_of "$trace_answer")" == 200 ]] && grep -q '"total":{"value":[2-9]' <<< "$trace_answer"
}

# canary_span: one server span as a service sends it over OTLP/HTTP, with what must not reach Kibana: a made-up IBAN
# (check digits 00, which no real IBAN has) in its path and in an exception, a session id, a query string, a user
# agent, a secret on the command line, and in the exception's stack trace the values PostgreSQL quotes and an email.
# All but the IBAN carry this run's id.
canary_span() {
  local now trace
  now="$(date +%s%N)"
  # The stack trace as JSON text: its \n and \t stay escapes.
  trace="TraceCanaryException: no account\n\tat cluster.Up.run(Up.java:1)"
  trace+="\nCaused by: org.postgresql.util.PSQLException: ERROR: duplicate key"
  trace+="\n  Detail: Key (email)=(canary-key-${canary}) already exists."
  trace+="\nCaused by: org.postgresql.util.PSQLException: ERROR: check"
  trace+="\n  Detail: Failing row contains (7, canary-row-${canary})."
  trace+="\nCaused by: java.io.IOException: no mailbox canary-${canary}@example.com"
  cat << SPAN
{"resourceSpans": [{
  "resource": {"attributes": [
    {"key": "service.name", "value": {"stringValue": "trace-canary"}},
    {"key": "process.command_line", "value": {"stringValue": "java -Dcanary.password=secret-${canary} -jar a.jar"}}]},
  "scopeSpans": [{"scope": {"name": "cluster-up"}, "spans": [{
    "traceId": "${trace_id}", "spanId": "${trace_id:0:16}", "name": "GET /api/v1/accounts/{iban}", "kind": 2,
    "startTimeUnixNano": "$((now - 5000000))", "endTimeUnixNano": "${now}",
    "attributes": [
      {"key": "http.request.method", "value": {"stringValue": "GET"}},
      {"key": "http.response.status_code", "value": {"intValue": "404"}},
      {"key": "http.route", "value": {"stringValue": "/api/v1/accounts/{iban}"}},
      {"key": "url.path", "value": {"stringValue": "/api/v1/accounts/${canary_iban}"}},
      {"key": "url.query", "value": {"stringValue": "code=canary-code-${canary}"}},
      {"key": "user_agent.original", "value": {"stringValue": "trace-canary/${canary}"}},
      {"key": "kc.sessionId", "value": {"stringValue": "canary-session-${canary}"}}],
    "events": [{"name": "exception", "timeUnixNano": "${now}", "attributes": [
      {"key": "exception.type", "value": {"stringValue": "TraceCanaryException"}},
      {"key": "exception.message", "value": {"stringValue": "no account ${canary_iban}"}},
      {"key": "exception.stacktrace", "value": {"stringValue": "${trace}"}}]}]}]}]}]}
SPAN
}

# The span goes the whole way: the collector, APM Server, Elasticsearch. APM Server makes a transaction of it and an
# error of its exception; both must arrive with the IBAN masked and the rest gone. Kibana's APM app then lists the
# service trace-canary, as Discover lists the log canary's line.
logging_trace_canary() {
  local answer leaked value
  step "a canary span through the traces"
  if [[ "$mode" == "check" ]]; then
    note "the canary span runs without --check"
    return 0
  fi
  trace_id="$(openssl rand -hex 16)"
  canary="${trace_id:16:12}"
  canary_iban="UA00322313$(printf '%019d' "$((16#${trace_id:16:15}))")"
  tunnel_open collector "$TRACE_COLLECTOR" 4318 "the trace collector" || return 1
  answer="$(canary_span | curl -sS --max-time 10 -o /dev/null -w '%{http_code}' \
    -H 'Content-Type: application/json' --data-binary @- \
    "http://127.0.0.1:${tunnel_port[collector]}/v1/traces" 2> /dev/null || true)"
  tunnel_close collector
  if [[ "$answer" != 200 ]]; then
    fail "the trace collector did not take the canary span (HTTP ${answer:-none})"
    note "kubectl --context ${CONTEXT} -n ${LOGGING_NAMESPACE} logs deployment/${TRACE_COLLECTOR}"
    return 1
  fi
  local attempts="$CANARY_ATTEMPTS"
  until trace_stored; do
    attempts=$((attempts - 1))
    if ((attempts <= 0)); then
      fail "the canary span did not arrive as a transaction and an error within $((CANARY_ATTEMPTS * 3)) seconds"
      note "the collector's log: kubectl --context ${CONTEXT} -n ${LOGGING_NAMESPACE} logs deploy/${TRACE_COLLECTOR}"
      note "APM Server's: kubectl --context ${CONTEXT} -n ${LOGGING_NAMESPACE} logs deploy/${APM_SERVER}-apm-server"
      return 1
    fi
    sleep 3
  done
  leaked=""
  for value in "$canary_iban" "canary-code-${canary}" "trace-canary/${canary}" "canary-session-${canary}" \
    "secret-${canary}" "canary-key-${canary}" "canary-row-${canary}" "canary-${canary}@"; do
    if grep -qF "$value" <<< "$trace_answer"; then leaked+=" ${value}"; fi
  done
  if [[ -n "$leaked" ]]; then
    fail "the canary span was stored with what the collector must remove or mask:${leaked}"
  elif ! grep -qF '"url.path":["/api/v1/accounts/masked"]' <<< "$trace_answer" \
    || ! grep -qF '"error.exception.message":["no account masked"]' <<< "$trace_answer" \
    || ! grep -qF 'Detail: masked' <<< "$trace_answer" || ! grep -qF 'no mailbox masked' <<< "$trace_answer"; then
    fail "the canary span was stored, but not masked as the collector masks it"
  else
    ok "a span with an IBAN, a session id, a query string and a database's details arrived as APM Server's"
    note "transaction and error: the IBAN, the quoted values and the email masked, the rest removed"
  fi
}

# --- the network policies -------------------------------------------------------------------------------------

# probe_from_postgres URL: busybox's wget in the PostgreSQL pod, which no policy of the logging namespace lets in.
# Prints refused (the connection was refused or timed out), reached (any answer, an HTTP error too: the connection
# was made) or unknown (the probe could not run), each but refused followed by wget's message.
probe_from_postgres() {
  local probe
  if probe="$(k -n "$NAMESPACE" exec postgres-0 -- wget -q -T 5 -O /dev/null "$1" 2>&1)"; then
    printf 'reached'
  elif [[ "$probe" == *"can't connect to remote host"* || "$probe" == *"download timed out"* ]]; then
    printf 'refused'
  elif [[ "$probe" == *"bad address"* || "$probe" == *"executable file not found"* ]]; then
    printf 'unknown %s' "$probe"
  else
    printf 'reached %s' "$probe"
  fi
}

logging_policies() {
  local target result what refused=0
  step "the logging stack's network policies"
  # Elasticsearch, APM Server and the collector run and answer their own clients, so a connection one of them does
  # not take is the policy. busybox's wget tells a refused connection from one made: a plain request to
  # Elasticsearch's TLS port fails otherwise. PostgreSQL sends no spans, so the collector refuses it too.
  for target in "Elasticsearch|http://${LOGGING_RELEASE}-es-http.${LOGGING_NAMESPACE}:9200/" \
    "APM Server|http://${APM_SERVER}-apm-http.${LOGGING_NAMESPACE}:8200/" \
    "the trace collector|http://${TRACE_COLLECTOR}.${LOGGING_NAMESPACE}:4318/v1/traces"; do
    what="${target%%|*}"
    result="$(probe_from_postgres "${target#*|}")"
    case "$result" in
      refused) refused=$((refused + 1)) ;;
      unknown*)
        fail "the logging stack's network policies could not be checked from the PostgreSQL pod: ${result#unknown }"
        ;;
      *)
        fail "a pod of the bank reaches ${what}: the logging stack's network policies are not enforced"
        if [[ "$result" != reached ]]; then note "the probe from postgres-0 said: ${result#reached }"; fi
        ;;
    esac
  done
  if ((refused == 3)); then
    ok "Elasticsearch, APM Server and the trace collector refuse a pod of the bank that sends no spans;"
    note "Kibana, Logstash, Filebeat and the canaries did their part (above)"
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
      logging_trace_canary || true
    fi
    tunnel_close es
  fi
  logging_policies || true
}
