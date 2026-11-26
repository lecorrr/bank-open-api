# The k3d cluster

bank-core and bank-open-api run in a one-node Kubernetes cluster in Docker: k3d with k3s 1.36, Traefik in front
with HTTPS, mutual TLS and providers' rate limits, Keycloak for the providers' and the customers' sign-in, network
policies between them. Customers' browsers use the bank's address, `https://localhost:8443`; providers call the API
on its own host, `https://api.localhost:8443`, where Traefik requires their client certificate. The provider app, a
provider's site in a namespace of its own, is at `https://provider.localhost:8443`. curl and systemd's resolver take
any name under `localhost` to 127.0.0.1, so nothing goes into `/etc/hosts`. The bank needs about 4 GB of memory,
the provider app 0.4 GB, and the logs and traces about 5.3 GB more (Elasticsearch 2, Kibana 1, Logstash 1, APM
Server 0.5, the trace collector 0.25, Filebeat and the ECK operator the rest). Run the commands from
`~/projects/bank-open-api` unless a step says otherwise.

## 1. The tools

k3d 5.9.0, kubectl 1.36.6 and Helm 4.3.1, installed into `~/.local/bin` after a checksum check. It changes
nothing outside that folder and needs no sudo:

```
bash scripts/install-tools.sh
```

`--check` only shows what is installed. Open a new terminal afterwards if it says `~/.local/bin` is not in
`PATH`. The other scripts also use `openssl` and `curl`, which Ubuntu has.

## 2. The cluster and its platform

```
bash scripts/cluster-up.sh
```

The first run downloads the k3s and Keycloak images, and about 3 GB for the logging stack, and takes ten minutes
or more. It creates the cluster `bank` from `cluster/k3d.yaml` and switches kubectl to it, sets up Traefik, and
makes:

- the `bank` namespace, and `provider` for the provider app, with its network policies: only Traefik reaches the
  provider app (`cluster/platform/provider.yaml`);
- the bank's test certificate authority, in `~/.config/bank-open-api/pki` (`ca.key` never leaves this machine),
  and the certificates it signs: Traefik's server certificate for `localhost`, `api.localhost`, `kibana.localhost`,
  `provider.localhost` and `k3d-bank-serverlb`, the client certificate of the test provider `smoke-test` (Secrets
  `bank-gateway-tls`, `smoke-test-provider`, `provider-ca`, and a copy of the provider's key and certificate in the
  same folder), and the provider app's (its key only in the Secret `provider-app-tls` of `provider`, its
  certificate alone for Keycloak in `provider-app-certificate` of `bank`);
- the HTTPS Gateway with Traefik's TLS options, a client certificate required on the API's host, and the
  middleware that passes it on and holds each provider to its rate limit (`cluster/platform/edge.yaml`);
- PostgreSQL with a database for each service and for Keycloak;
- Keycloak with the realm `bank` (its first start builds it and creates its tables: two or three minutes): the
  test provider `smoke-test` and the provider app `provider-app`, with the scopes `accounts` and `payments`,
  bank-open-api's client for the customer pages `bank-approval` (its secret in the
  Secret `bank-approval-client`), and the test customer `artem.demo`, customer number C1001 as in bank-core's demo
  data (the password in the Secret `test-customer`, and a copy in `~/.config/bank-open-api/pki` for the smoke
  test);
- the network policies of the `bank` namespace (`cluster/platform/network-policies.yaml`): a pod there takes
  connections only from the callers its policy names, Traefik for bank-open-api and Keycloak;
- the account Jenkins deploys with, in `bank` and `provider`, and its kubeconfig in
  `~/.config/bank-open-api/jenkins-kubeconfig.yaml`;
- the logs and traces: the ECK operator 3.5.0 in `elastic-system` (Elastic's chart with
  `cluster/eck-operator.yaml`), and the stack of `cluster/charts/logging`: one Elasticsearch node, Kibana, Logstash
  with the masking, APM Server and the trace collector with its masking in `logging`, Filebeat in `log-collector`,
  their network policies, the Secrets `log-masking` (the key of the masking's pseudonyms) and `log-reader`
  (Kibana's reader), the lifecycle policies `bank-logs` and `bank-traces` (7 days, failure stores too) and Kibana's
  saved search.

It fetches Keycloak's discovery document through Traefik, which proves TLS, the route and the realm, and then
checks the network policies are enforced: a connection from the PostgreSQL pod to Keycloak must be refused, while
Keycloak still answers through Traefik. Last, it waits for the logging stack to be green (up to 15 minutes, as
`LOGGING_WAIT_SECONDS=1800 bash scripts/cluster-up.sh` can make longer), sends a canary line from a pod in `bank`
with a made-up IBAN and a username and finds it in Elasticsearch masked, then a canary span to the trace collector
with an IBAN, a session id, a query string and a database's details and finds it in APM's data masked, and checks
that the PostgreSQL pod reaches none of Elasticsearch, APM Server and the collector. Elasticsearch's API and the
collector it calls through port-forwards of their own, which end with the run.
Every run checks the lot again and fixes only what is missing or about to expire, so it is also how you start the
cluster after `k3d cluster stop`. `--check` reports without changing anything.

A cluster made by an earlier version gets what is new the same way: run it once before Jenkins deploys the new
version. From 0.2.x that is Traefik's new settings (Traefik restarts), the certificates, the Gateway's HTTPS
listener in place of the HTTP one, and Keycloak with its database. From 0.3.0 it is the two new Secrets and the new
realm: Keycloak imports a realm only into a database without it, so when the realm file (or a Secret it names) is
new, `cluster-up.sh` empties Keycloak's database first and Keycloak imports the realm afresh. Anything changed by
hand in the admin console is gone then, as it should be: the realm file is the realm. From 0.4.0 it is the realm
again, with the new scope `payments`; the summary says `Keycloak's database emptied (the realm file changed)`. From
0.5.0 it is Traefik's certificate issued again with the API's host name, the new TLS options and middleware, the
network policies, and the right for Jenkins' account to deploy the API's IngressRoute. From 0.6.1 it is the
logging stack, Traefik's certificate issued again with Kibana's host, the Gateway taking Kibana's route from
`logging`, and Keycloak restarted to write ECS JSON. From 0.7.0 nothing in the cluster changes: the run finishes
what 0.7.0's stopped at, the lifecycle policy, the saved search, the canary and the logging stack's probe; 0.7.1's
own run stopped earlier, at Helm's conflict with ECK, which 0.7.2 settles. From 0.7.2 it is Logstash restarted
with the pipeline that drops each line's copy as it came and with the new `mask.rb`; the logs stored before it hold
those copies, usernames unmasked in them, so delete them once after this run (section 5). It also puts this
version's Traefik settings into the node, where k3s reads them again at every start. From 0.7.3 it is the
namespace `provider` with its policies and the provider app's certificate, Traefik's certificate issued again with
the provider app's host, the Gateway taking the provider app's route, the realm with the client `provider-app`
(Keycloak's database emptied, the realm imported afresh), Traefik and Keycloak restarted to send their spans,
Jenkins' right to deploy in `provider`, and in `logging` APM Server, the trace collector, the traces' lifecycle and
the failure stores' 7 days.

It uses these ports on 127.0.0.1 and stops if one is taken: 6550 (Kubernetes API), 8480 (HTTP, no routes since
0.3.0), 8443 (HTTPS) and 5050 (image registry).

## 3. Jenkins deploys the services

Jenkins comes from bank-core (`ci/jenkins/`, its `docs/jenkins.md`). In `~/projects/bank-core`, with the id of
your `docker` group:

```
export DOCKER_GID=$(getent group docker | cut -d: -f3)
```

```
docker compose -f ci/jenkins/compose.yaml -f ci/jenkins/compose.k3d.yaml up -d --build
```

The second file adds k3d's Docker network, the deploy account's kubeconfig and this repository
(`/repos/bank-open-api`), and switches on the deploy stages. Jenkins builds what is committed, so commit first.
Then build the job `bank-core`, and after it a job `bank-open-api` (Pipeline from SCM, repository
`file:///repos/bank-open-api`, branch `*/main`, script path `Jenkinsfile`). Each deploy stage ends once only pods
of the new image are left: the old pod answers for a few seconds after the new one is ready, and a test then could
meet either. bank-open-api's smoke test reads the test provider's key and certificate and the test customer's
password from the cluster's Secrets (`scripts/smoke-test-files.sh`) and runs `scripts/smoke-test.sh` through
Traefik. Then the same job builds and deploys the provider app into `provider` and goes through it as its customer
(`scripts/smoke-test.sh --provider-app`).

Run `cluster-up.sh` of a new version before Jenkins deploys it: bank-open-api 0.6.0 and later need the TLS options
and middleware for the API's host and the right to deploy its IngressRoute, 0.8.0 the namespace `provider` and the
right to deploy there, and bank-core 0.8.0 or later (the accounts' customer numbers), so build `bank-core` first.
bank-core sends its spans from 0.9.0 on; with an older one, a trace stops at bank-open-api's call to it.

## 4. Check it

```
kubectl -n bank get pods,svc,gateway,httproute,ingressroute,networkpolicy
```

```
kubectl -n provider get pods,svc,httproute,networkpolicy
```

The smoke test does what Jenkins' last stage does. It signs in at Keycloak as the test provider with a JWT
signed by its key, over mutual TLS, and checks the token is bound to its certificate. Then three consents: one the
provider deletes while it waits for the customer (with account reads refused; the API's host refusing a connection
without the certificate or with one the bank did not issue; the bank's address answering `404` for the API, and
`421` when a call there names the API's host; a call without a token refused); one the test customer approves on
the bank's page, signing in at Keycloak's login form as a browser would, which the provider then reads under, and
which the customer withdraws; and one that names
another customer's account, which the customer can only reject. Then three payments: one the provider cancels;
one of 1.00 UAH from the test customer's account to another customer's at the bank, which the customer approves
and bank-core makes at once (`ACCC`); and one from another customer's account, which the customer can only reject.
Last, 60 calls at once, of which those over the provider's burst of 20 come back `429` with `Retry-After`. Each
run moves 1.00 UAH between bank-core's demo accounts; the first has 10 000.00 to begin with:

```
bash scripts/smoke-test.sh
```

With `--provider-app` it goes through the provider app instead, as its customer: asks for access, signs in and
approves on the bank's page, comes back to the provider app's balances, pays 1.00 UAH the same way and sees `ACCC`,
then checks that a return with another visit's state and a form without its CSRF token change nothing, and ends the
provider app's access:

```
bash scripts/smoke-test.sh --provider-app
```

### Calls by hand

`--token` makes the smoke test sign in only and print the access token. It is good for five minutes, and only
together with the test provider's certificate:

```
TOKEN=$(bash scripts/smoke-test.sh --token)
```

```
PKI=~/.config/bank-open-api/pki
```

Ask for a consent to bank-core's first demo account and keep its id:

```
CONSENT_ID=$(curl -sS --cacert $PKI/ca.crt --cert $PKI/smoke-test.crt --key $PKI/smoke-test.key -H "Authorization: Bearer $TOKEN" -X POST https://api.localhost:8443/v2/consents/account-access -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)" -H 'PSU-IP-Address: 192.0.2.10' -H 'Content-Type: application/json' -d '{"access": {"payments": [{"account": {"iban": "UA843999990000000000000000001"}, "rights": ["accountDetails", "balances"]}]}, "consentType": "detailed", "recurringIndicator": true, "validTo": "9999-12-31", "frequencyPerDay": 4}' | sed -n 's/.*"consentId":"\([^"]*\)".*/\1/p')
```

The customer approves it on the bank's page. Open it in a browser (it warns about the test CA's certificate; let
it through, or import `~/.config/bank-open-api/pki/ca.crt` as an authority):

```
echo "https://localhost:8443/customer/consents/$CONSENT_ID"
```

Keycloak asks for the test customer: `artem.demo`, with this password:

```
cat ~/.config/bank-open-api/pki/test-customer.password; echo
```

The page lists the account and what the provider wants to read. Approve; without a `TPP-Redirect-URI` the bank's
own page says it is done. Then read as the provider (a token older than five minutes answers `TOKEN_EXPIRED`: run
the `TOKEN=` line again first):

```
curl -sS -w '\n' --cacert $PKI/ca.crt --cert $PKI/smoke-test.crt --key $PKI/smoke-test.key -H "Authorization: Bearer $TOKEN" -H 'X-Request-ID: 7f3e8a52-5d1b-4c6e-9a2f-0b8c4d6e1f3a' -H "Consent-ID: $CONSENT_ID" 'https://api.localhost:8443/v2/accounts?withBalance=true'
```

The answer lists the account under its `resourceId`, with its balance. The same call without `--cert` and
`--key` gets no connection: Traefik ends the TLS handshake, and curl stops with exit code 35 or 56. Without the
token it answers `401` with `TOKEN_UNKNOWN`. Every read of account data leaves a log line under the consent id and
the provider's request id, next to the trace id that also went on to bank-core:

```
kubectl -n bank logs deploy/bank-open-api | grep 7f3e8a52-5d1b-4c6e-9a2f-0b8c4d6e1f3a
```

`https://localhost:8443/customer/consents` lists the customer's valid consents, each with a button to withdraw it.

A payment works the same way. Initiate one of 25.00 UAH from the test customer's account to another customer's
at the bank, and keep its id:

```
PAYMENT_ID=$(curl -sS --cacert $PKI/ca.crt --cert $PKI/smoke-test.crt --key $PKI/smoke-test.key -H "Authorization: Bearer $TOKEN" -X POST https://api.localhost:8443/v2/payments/domestic-credit-transfers -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)" -H 'PSU-IP-Address: 192.0.2.10' -H 'Content-Type: application/json' -d '{"debtorAccount": {"iban": "UA843999990000000000000000001"}, "instructedAmount": {"currency": "UAH", "amount": "25.00"}, "creditorAccount": {"iban": "UA573999990000000000000000002"}, "creditor": {"name": "Olena Kovalenko"}, "remittanceInformationUnstructured": "Payment by hand"}' | sed -n 's/.*"paymentId":"\([^"]*\)".*/\1/p')
```

Open its page, sign in as `artem.demo` again and approve:

```
echo "https://localhost:8443/customer/payments/$PAYMENT_ID"
```

Then its status, as the provider reads it:

```
curl -sS -w '\n' --cacert $PKI/ca.crt --cert $PKI/smoke-test.crt --key $PKI/smoke-test.key -H "Authorization: Bearer $TOKEN" -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)" https://api.localhost:8443/v2/payments/domestic-credit-transfers/$PAYMENT_ID/status
```

It is `ACCC`: bank-core made the transfer within the bank. To another bank's IBAN, such as
`UA213223130000026007233566001`, it is `ACSP` instead, and stays so in this cluster, where bank-core's Kafka is off.

The API describes itself for providers (RFC 9728), with no token needed but, on its host, the certificate:

```
curl -sS -w '\n' --cacert $PKI/ca.crt --cert $PKI/smoke-test.crt --key $PKI/smoke-test.key https://api.localhost:8443/.well-known/oauth-protected-resource
```

Traefik's rate limit shows in its answers once it bites: `429`, with `Retry-After` in whole seconds and
`X-Retry-In` as an exact duration such as `1.8s`.

### Keycloak's admin console

The console is not routed through Traefik. Forward a port to it (not 8081, which is Jenkins'):

```
kubectl -n bank port-forward svc/keycloak 8082:8080
```

Then open `http://localhost:8082/admin` and sign in as `admin` with this password:

```
kubectl -n bank get secret keycloak-admin -o jsonpath='{.data.password}' | base64 -d; echo
```

The realm `bank` holds the clients `smoke-test` and `bank-approval`, the client scopes `accounts` and `payments`,
the user `artem.demo` (its Details tab shows Customer number C1001) and, under Realm settings, Client policies, the
policy `fapi-2-for-providers`. Changes made here last only until the realm file changes: `cluster-up.sh` then
imports it afresh.

## 5. The logs in Kibana

Open `https://kibana.localhost:8443` (the browser warns about the test CA's certificate, as on the customer page;
let it through, or import `~/.config/bank-open-api/pki/ca.crt` as an authority) and sign in as `log-reader`, who
reads and changes nothing, with this password:

```
kubectl -n logging get secret log-reader -o jsonpath='{.data.password}' | base64 -d; echo
```

In Discover, open the saved search "Bank: requests by id": the bank's logs with the service, the level, the
request's `http.request.id` and `trace.id`, and the message. To follow one request through bank-open-api and
bank-core, put its `X-Request-ID` in the query bar, `http.request.id : "7f3e8a52-5d1b-4c6e-9a2f-0b8c4d6e1f3a"`, or
its trace, `trace.id : "..."`. `service.name : "Keycloak"` shows Keycloak's events, with each user a pseudonym such
as `username="masked-3f2a9c1e0b7d"`; `kubernetes.container.name : "postgres"` shows PostgreSQL's lines;
`service.name : "log-canary"` shows `cluster-up.sh`'s canary lines, with their IBAN masked to the country code and
the last four characters.

The user `elastic` may change everything (the lifecycle policy, the data views); its password:

```
kubectl -n logging get secret bank-logs-es-elastic-user -o jsonpath='{.data.elastic}' | base64 -d; echo
```

Logs stored before 0.7.3 keep each line's copy as it came, usernames unmasked in it. To delete them once
`cluster-up.sh` 0.7.3 has run, sign in to Kibana as `elastic`, open Dev Tools (menu, Management) and run the line
below; new lines start the data stream again, with the lifecycle policy and the template as `cluster-up.sh` set
them.

```
DELETE _data_stream/logs-bank-default
```

The stack's health, as ECK sees it:

```
kubectl -n logging get elasticsearch,kibana,logstash
```

```
kubectl -n log-collector get beat
```

## 6. The provider app

Open `https://provider.localhost:8443` (the browser warns about the test CA's certificate, as on the bank's pages).
The form has bank-core's first demo account filled in: **Ask the bank for access** sends you to the bank's page,
where you sign in as `artem.demo` (the password as in section 4) and approve. The bank sends you back, and the
provider app shows the account's balances under the consent; **Pay at the bank** starts a payment of 1.00 UAH to
another customer's account, which you approve on the bank's page the same way, and the provider app shows its
status. **End the provider's access** deletes the consent at the bank. The provider app's own log, where
`provider-app 0.8.0 started` names the version that runs:

```
kubectl -n provider logs deploy/provider-app
```

The provider app reaches the bank only through Traefik, with its own key and certificate: the token request, the
consent and the reads go through the same TLS options, rate limit and routes as any provider's calls. Its key is
only in its own namespace:

```
kubectl -n provider get secret provider-app-tls
```

## 7. The traces in Kibana

Sign in to Kibana as `log-reader`, as in section 5, and open the APM app (menu, Observability, Applications, or
`https://kibana.localhost:8443/app/apm/services`): it lists `provider-app`, `traefik`, `keycloak`, `bank-open-api` and
`bank-core`. Open `provider-app`, a transaction such as `GET /accounts`, and its trace sample: the waterfall shows
the provider app's call through Traefik to bank-open-api, and bank-open-api's call to bank-core, each span with its
timing; the token request goes through Traefik to Keycloak and its database. Errors list a trace's exceptions, and
the trace's logs tab shows the bank's log lines of the same `trace.id`. Kibana's service map needs Elastic's
Platinum license and stays off on ECK's free one; the waterfall shows the same calls for one request.

`service.name : "trace-canary"` in the APM app's query bar shows `cluster-up.sh`'s canary span, its path ending in
`masked`. What the collector keeps is in ARCHITECTURE.md (The traces); its log names what it refused:

```
kubectl -n logging logs deploy/trace-collector
```

## Deploy by hand

What Jenkins does, for when you want to try a chart change without a commit. In this repository, after
`./mvnw package`:

```
docker build -t localhost:5050/bank-open-api:dev .
```

```
docker push localhost:5050/bank-open-api:dev
```

```
helm upgrade --install bank-open-api charts/bank-open-api --namespace bank --set-string image.tag=dev --rollback-on-failure --timeout 5m
```

The provider app the same way, after `./mvnw -f provider-app/pom.xml package`:

```
docker build -t localhost:5050/provider-app:dev provider-app
```

```
docker push localhost:5050/provider-app:dev
```

Jenkins keeps the provider app's release in ConfigMaps, as its account may not read Secrets in `provider`; a hand
deploy has to say so too:

```
HELM_DRIVER=configmap helm upgrade --install provider-app provider-app/chart --namespace provider --set-string image.tag=dev --rollback-on-failure --timeout 5m
```

## Stop, start, delete

The cluster keeps its data while stopped:

```
k3d cluster stop bank
```

```
bash scripts/cluster-up.sh
```

Delete it with its registry and volumes (the logs go with Elasticsearch's volume); `cluster-up.sh` builds a new
one, and Jenkins then needs to be recreated as in bank-core's `docs/jenkins.md`:

```
k3d cluster delete bank
```

The test CA in `~/.config/bank-open-api/pki` outlives the cluster, so a new cluster's certificates come from the
same CA. To start the CA over too, delete that folder together with the cluster, never on its own: a cluster's
certificates then no longer match the CA, and Keycloak knows the old provider certificate.

## When something goes wrong

- `the node started without DNS servers`, or a pod in `ImagePullBackOff` with `lookup quay.io: Try again`: the
  cluster's node started before this machine had a network (after a power cut, say), and Docker gave it DNS
  without upstream servers. `cluster-up.sh` restarts the cluster for you; by hand it is `k3d cluster stop bank`,
  then `k3d cluster start bank`. If this machine has no DNS either, connect it to the network first. Jenkins has no
  restart policy, for the same reason: started at boot, it would hit the same race; start it again as bank-core's
  `docs/jenkins.md` says.
- `Keycloak did not become ready`: `cluster-up.sh` prints the pod's state and the end of its log. A slow first
  start needs more time (`KEYCLOAK_WAIT_SECONDS=900 bash scripts/cluster-up.sh`); `ImagePullBackOff` is the DNS
  case above.
- The sign-in on the customer page ends on "Sign-in did not finish": the link was old, or the browser went back
  at Keycloak. Start again from the consent's link. `kubectl -n bank logs deploy/bank-open-api | grep sign-in`
  names the OAuth 2.0 error.
- The customer page says "Not your accounts": the consent names an account bank-core keeps under another
  customer number than the signed-in customer's (or bank-core is older than 0.8.0, whose accounts have none).
  "Not your account" on a payment's page is the same for the account it is to be made from.
- A payment stays `ACTC`: bank-core could not be asked when the customer approved. The retry job makes the
  transfer half a minute after bank-core answers again; `kubectl -n bank logs deploy/bank-open-api | grep waits`
  shows each attempt. An `ERROR` with "different transfer under this Idempotency-Key" needs a person: bank-core
  keeps another transfer under the payment's id.
- A payment answers `RJCT` with `AM04`: the account has not enough money; `DS04`: bank-core refused it for another
  reason, which bank-open-api's log names with bank-core's code.
- The smoke test's payment says `401` with `ROLE_INVALID`: the token has no scope `payments`, as with a realm from
  before 0.5.0. Run `bash scripts/cluster-up.sh`, which imports the realm afresh.
- A call to `https://api.localhost:8443` says `SSL: no alternative certificate subject name matches` (60):
  Traefik's certificate is from before 0.6.0. Run `bash scripts/cluster-up.sh`, which issues it again with the
  API's host name. `404 page not found` from Traefik on the API's host means bank-open-api's chart (its
  IngressRoute) is older than 0.6.0: build the job `bank-open-api`.
- `the network policies are not enforced`: k3s runs with its network policy controller off
  (`--disable-network-policy`), so any pod could call the services directly. A cluster from `cluster/k3d.yaml`
  has it on.
- A pod's readiness probe fails right after the network policies: the kubelet's probes come from the node, which
  kube-router always lets through, so look for another cause first. `kubectl -n bank describe networkpolicy`
  shows who may call what.
- A provider's calls answer `429`: its rate limit (10 a second, bursts of 20) is used up; the answer's
  `Retry-After` says when to try again.

- `Helm could not install the logging stack`: Helm's own words come just above the summary. `conflict with
  "elastic-operator"` is ECK owning lists of its resources as server-side apply counts; versions before 0.7.2
  upgraded without `--force-conflicts` and stopped there. Otherwise `helm --kube-context k3d-bank -n logging
  history bank-logs` shows the failed revision and its reason.
- `the logging stack is not ready`: most often memory, a pod `Pending` with `Insufficient memory` (`kubectl -n
  logging describe pod bank-logs-es-default-0`, Events at the end): stop what else takes memory, or give Docker
  more. On the first run the images may still be coming: `LOGGING_WAIT_SECONDS=1800 bash scripts/cluster-up.sh`.
  With `Logstash does not run this version's pipeline and masking script yet` under it, ECK has not replaced
  Logstash's pod after the upgrade (`kubectl -n logging get logstash,statefulset`); with Logstash red, its new pod
  does not start, and its log says why.
- Logstash restarts and its log says the masking script had failing tests (`kubectl -n logging logs
  bank-logs-ls-0`): its own tests failed, and Logstash refuses to run a masking it cannot trust. The ConfigMap
  `bank-logs-masking` holds another script than this version's `mask.rb`; run `bash scripts/cluster-up.sh`.
- `the canary line did not reach Elasticsearch`: follow it. `kubectl -n bank logs log-canary-...` (the pod stays
  for this) shows the line, `kubectl -n log-collector logs daemonset/bank-logs-beat-filebeat` whether Filebeat read
  the file, `kubectl -n logging logs bank-logs-ls-0` whether Logstash sent it on.
- `the canary line was stored with a copy of itself as it came (event.original)`: Logstash runs a pipeline that
  keeps the codec's copy of each line; `kubectl -n logging get logstash bank-logs -o yaml` shows what it runs, and
  `bash scripts/cluster-up.sh` puts this version's pipeline back.
- `the canary line was stored with its IBAN or username unmasked`: the pipeline runs without the masking, and
  until it is back the logs Elasticsearch takes in are not masked. `kubectl -n logging get logstash bank-logs -o
  yaml` shows what Logstash runs; `bash scripts/cluster-up.sh` puts this version's pipeline back.
- `Kibana does not answer on https://kibana.localhost:8443`: Traefik's certificate is from before 0.7.0 (run
  `bash scripts/cluster-up.sh`, which issues it again), or Kibana is not green yet.
- `kubectl could not forward a port to Elasticsearch` (or `to the trace collector`): kubectl's own words follow.
  Most often the pod is not running (`kubectl -n logging get pods`); the port-forward goes to the Service
  `bank-logs-es-http` (or `trace-collector`).
- `ECK's Secret bank-logs-es-http-certs-public in logging has no CA for Elasticsearch`, or `Elasticsearch does not
  answer through the port-forward`: ECK makes that Secret with Elasticsearch's certificate, so the operator did
  not get that far (`kubectl -n elastic-system logs statefulset/elastic-operator`), or the Secret
  `bank-logs-es-elastic-user` no longer holds the password Elasticsearch has.
- `a pod of the bank reaches Elasticsearch`: the logging stack's network policies are not enforced, as with the
  bank's (`--disable-network-policy`).
- `these ports it needs are taken`: another program or container has one of the ports. `ss -ltnp` shows which;
  change the port in `cluster/k3d.yaml` (and in the curl commands) or stop the other one.
- A pod stays in `ImagePullBackOff`: the image is not in the registry. `curl -sS localhost:5050/v2/_catalog`
  lists what is there; push it again.
- `violates PodSecurity "restricted"` in `kubectl -n bank get events`: a pod asks for more than the namespace
  allows (root, privilege escalation, capabilities). The charts here meet the standard; compare with them.
- The Gateway is not programmed: `kubectl -n bank describe gateway bank-gateway` shows why. A missing Secret
  `bank-gateway-tls` means `cluster-up.sh` has not run; `kubectl -n kube-system get helmchartconfig traefik`
  must exist.
- curl says `SSL certificate problem` (60): it was not given the test CA, `--cacert ~/.config/bank-open-api/pki/ca.crt`.
  `alert unknown ca` or `bad certificate` (35, 56): the client certificate is not from the test CA; `alert
  certificate required` on the API's host: curl was given none.
- Keycloak's pod restarts during its first start: it builds itself and creates its tables, which takes up to
  five minutes on a slow disk. `kubectl -n bank logs deploy/keycloak` shows where it is; a wrong database
  password means the Secret `keycloak-db` and the database disagree, and `cluster-up.sh` sets it again.
- The token request answers `invalid_client`: Keycloak did not accept the signed JWT, because the clock is off,
  or the provider's key no longer matches the certificate Keycloak imported (the Secret `smoke-test-provider` was
  made again by hand while Keycloak kept its database). `kubectl -n bank logs deploy/keycloak | grep -i smoke-test`
  names the reason. `bash scripts/cluster-up.sh` has Keycloak import the realm afresh whenever it makes a provider's
  key anew; for a Secret made by hand, delete it and run the script.
- The token request answers `invalid_request` with `Client Certification missing`: curl was not given
  `--cert` and `--key`.
- The API answers `503` with every token: bank-open-api cannot read Keycloak's keys.
  `kubectl -n bank logs deploy/bank-open-api` shows why; Keycloak must be ready.
- bank-core or bank-open-api restarts: `kubectl -n bank logs deploy/bank-core --previous` (or `bank-open-api`)
  shows the last start. A wrong database password means the Secret (`bank-core-db`, `bank-open-api-db`) and the
  database disagree; `cluster-up.sh` sets the password again.
- bank-open-api's pod shows `CreateContainerConfigError` with `secret "bank-open-api-db" not found` (older than
  0.2.0) or `secret "bank-approval-client" not found` (older than 0.4.0): the cluster is older than this version.
  Run `bash scripts/cluster-up.sh`, then build the job `bank-open-api` again.

- The provider app's page does not load: `SSL: no alternative certificate subject name matches` means Traefik's
  certificate is from before 0.8.0 (run `bash scripts/cluster-up.sh`, which issues it again with the provider
  app's host); `404 page not found` means the provider app is not deployed yet (build the job `bank-open-api`) or
  the Gateway takes no routes from `provider` (`kubectl -n provider describe httproute provider-app`).
- The provider app says "The bank did not help": the bank refused or did not answer, and the page shows the
  status and the contract's code. `503` without a code: the provider app could not reach Traefik or Keycloak
  (`kubectl -n provider logs deploy/provider-app`). `invalid_client` from the bank's sign-in: Keycloak holds
  another certificate for `provider-app` than the one in `provider-app-tls`; run `bash scripts/cluster-up.sh`.
- The provider app's pod shows `CreateContainerConfigError` with `secret "provider-app-tls" not found`: the
  cluster is older than 0.8.0. Run `bash scripts/cluster-up.sh`; it restarts the provider app once the Secret is
  there.
- The smoke test's provider journey says `a return to the provider app with another state is refused: HTTP 302`
  or similar: the provider app took a return that did not belong to its visit. Look at `Journey` and
  `ProviderController`; nothing should change on such a return.
- `the trace collector did not take the canary span`: the collector is not running or refused the span; its log
  says which (`kubectl -n logging logs deploy/trace-collector`). A `memory_limiter` message means it is short of
  memory: give `traceCollector.resources` in the logging chart's values more.
- `the canary span did not arrive as a transaction and an error`: follow it. The collector's log shows whether it
  sent the span on, APM Server's (`kubectl -n logging logs deploy/bank-traces-apm-server`) whether it took it and
  what Elasticsearch answered.
- `the canary span was stored with what the collector must remove or mask`: the collector runs another
  configuration than this version's `collector.yaml`, and the spans Elasticsearch takes in are not masked until it
  is back. `kubectl -n logging get configmap trace-collector -o yaml` shows what it runs; `bash
  scripts/cluster-up.sh` puts this version's back.
- No traces from a service: its chart is older than bank-open-api 0.8.0 or bank-core 0.9.0 and sets no
  `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`; build its job again. No traces from Traefik or Keycloak: `cluster-up.sh`
  has not run since 0.8.0.
- `a pod of the bank reaches APM Server` or `reaches the trace collector`: the logging stack's network policies are
  not enforced, as with the bank's (`--disable-network-policy`).
- Kibana's APM app asks for a license on the service map: the map is a Platinum feature; the stack runs on the
  free Basic license. Everything else in the APM app works.
