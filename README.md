# bank-open-api

The bank's open banking API: licensed providers (budget apps, payment services) read account data and start
payments, only with the customer's consent. It follows the Berlin Group NextGenPSD2 model that Ukraine's
open banking specifications use, and sits in front of [bank-core](https://github.com/lecorrr/bank-core). Project 3
of three, with bank-core and [bank-integration](https://github.com/lecorrr/bank-integration).

**Status: 0.8.1, all eight steps done.** A provider's site of its own, the provider app (`provider-app/`), takes
a customer through the whole flow at `https://provider.localhost:8443`: it signs in at Keycloak with its own key
and certificate, asks the bank for a consent, sends the customer to the bank's page and shows the balances once
they approve, then starts a payment the same way. Every service on the way (the provider app, Traefik, Keycloak,
bank-open-api, bank-core) sends its spans to one OpenTelemetry Collector, which keeps only the attributes on its
allow-list and masks IBANs, and Kibana's APM app shows each trace across them with its logs. The API, consents,
payments, the edge and the logs work as in 0.7.3. In 0.8.1 the trace collector listens on every address of its
pod, so the canary span `cluster-up.sh` sends through a port-forward reaches it, and `cluster-up.sh` waits for the
collector's old pod to go first.

- **A provider outside the bank**: the provider app runs in a namespace of its own (`provider`) and reaches the
  bank only through Traefik, as any provider's server would. It signs in with `private_key_jwt` (PS256) over mutual
  TLS and gets a token bound to its certificate; its key never leaves its namespace, and Keycloak's realm holds only
  the certificate. The bank's return carries a random `state` that the provider app compares in constant time, and
  what the customer decided is read from the bank, never from the return.
- **Traces in Kibana**: OTLP from the five services to the collector in the `logging` namespace, on to Elastic
  APM Server (run by ECK) and Kibana's APM app. One trace runs from the provider app's page through Traefik and
  bank-open-api to bank-core, or through Traefik to Keycloak, and the bank's spans show the log lines written
  under them.
- **Masking for traces too, closed by default**: the collector's redaction processor drops every attribute that
  is not on its allow-list (`cluster/charts/logging/tracing/collector.yaml`), replaces an IBAN anywhere in a kept
  value with `masked`, and puts `?` for the values in SQL statements. `cluster-up.sh` sends a canary span full of
  things that must not be stored, a process command line, a query string, a user agent and an IBAN among them, and
  checks what Elasticsearch keeps.
- **Kept 7 days, failures too**: the traces get a lifecycle policy of their own, the same as the logs', and the
  data streams' failure stores, 30 days by default, keep 7.
- **Network policies for the traces**: only the five senders reach the collector, only the collector reaches APM
  Server, and only APM Server joins those who reach Elasticsearch.

- **Masking before storage**: Logstash's ruby filter (`cluster/charts/logging/masking/mask.rb`) goes through
  every field of every line. IBANs keep their country code and last four characters; usernames, Keycloak's user
  ids, emails and people's names become pseudonyms (`masked-` and 12 characters of an HMAC with a key from a
  Secret), so one user's events still line up; the values PostgreSQL quotes in its error details and passwords in
  SQL are removed. A value that is a whole JSON document is masked field by field, keys included (one too deep to
  read is removed); text is read with escaped quotes, dotted keys and keys after a prefix (`customer_email`,
  `DB_PASSWORD`). Logstash keeps no copy of a line as it came (`event.original`), and a line the masking cannot
  handle is dropped. The script's own tests run each time Logstash loads it, and Logstash does not start if one
  fails.
- **ECK on k3d**: Elasticsearch, Kibana, Logstash and Filebeat 9.5.7, run by ECK 3.5. Filebeat reads the
  containers' log files on the node, so the services send nothing over the network; it alone runs as root, in a
  namespace of its own, and the rest meet the "restricted" Pod Security Standard.
- **Kept 7 days**: a new index each day, deleted 7 days after it closes (an ILM policy, through Elasticsearch's
  `logs@custom`).
- **Kibana on the bank's Gateway**: a host of its own, a reader who can change nothing, and a saved search with
  each request's `http.request.id` and `trace.id`.
- **Network policies for the logs too**: Kibana takes Traefik, Elasticsearch takes Kibana, Logstash and the
  operator, Logstash takes Filebeat, and no pod of the bank reaches any of them.

- **Mutual TLS required on the API's host**: Traefik's TLS options for `api.localhost` require a certificate from
  the bank's test CA in the handshake, so a caller without one, or with one the bank did not issue, never reaches
  HTTP, and naming the API's host on a connection to the bank's address gets `421`. On the bank's address a
  certificate is checked when one is sent: Keycloak binds providers' tokens to it. Traefik 3.7 gives every Gateway
  listener the default TLS options, so the API's route is an IngressRoute, and the pages stay on the Gateway.
  Traefik passes the certificate on in a header; one a client sends itself never gets through.
- **A rate limit per provider**: 10 requests a second on average and bursts of 20 for each client certificate, on
  the API and, counted apart, on Keycloak's token and PAR endpoints. Over the limit: `429` with `Retry-After`.
- **Network policies**: nothing comes into a pod of the `bank` namespace unless a policy lets it: Traefik reaches
  bank-open-api and Keycloak, bank-open-api reaches bank-core, Keycloak and PostgreSQL, Keycloak and bank-core reach
  PostgreSQL. `cluster-up.sh` checks k3s enforces them.
- **Payments that become bank-core transfers**: a provider initiates a credit transfer in hryvnias to any account
  at a bank in Ukraine and sends the customer's browser to the bank's page (`_links.scaRedirect`); the customer
  signs in at Keycloak, sees the amount, the payee and the purpose, and approves or rejects. Approving makes the
  transfer at bank-core at once.
- **One payment product of the bank's own**: `domestic-credit-transfers`, single payments in JSON, with the
  purpose and the payee's name a Ukrainian credit transfer needs. GD Next's contract leaves the products to each
  bank; SEPA products are answered `PRODUCT_UNKNOWN`, fields the bank does not offer `PARAMETER_NOT_SUPPORTED`.
- **Paid once, whatever fails**: the payment's id is the transfer's `Idempotency-Key` at bank-core and its order
  never changes, so a repeat after a timeout gets the transfer made the first time. When bank-core cannot be
  asked, the payment stays `ACTC` and a scheduled job tries again later; each attempt claims the payment in one
  SQL statement, so two instances never make the same transfer at once.
- **Only the account holder approves**: bank-core says who holds the debtor account, and the check sits right
  before the transfer. Anyone the provider sent the link to may reject.
- **ISO 20022 statuses**: `RCVD`, `CANC` (cancelled by the provider before the decision), `RJCT` (`AM04`
  insufficient funds, `DS04` refused by a bank, none when the customer rejected), `ACTC`, `ACCC`, `ACSP`, `ACSC`.
  A payment sent to another bank learns that bank's answer from bank-core when the provider reads its status.

- **The customer's sign-in**: the bank's pages are an OAuth 2.0 client of Keycloak (authorization code with PKCE,
  even for a confidential client, as OAuth 2.1 asks), with a new password check for every approval
  (`prompt=login`), so a Keycloak session left in a shared browser cannot approve for someone else. Keycloak puts
  the customer's number (`customer_id`, a user attribute only an administrator may set) into the ID token.
- **Who holds the account is bank-core's to say**: each account there carries its customer's number (bank-core
  0.8.0). Approval needs every account the consent names to be the customer's; reads leave out an account that
  changed hands since.
- **Pages a browser can trust**: server-rendered Thymeleaf without JavaScript, CSRF tokens on every form, a
  Content-Security-Policy that loads nothing but the stylesheet, no framing, a session cookie that is `Secure`,
  `HttpOnly` and `SameSite=Lax`, and the session ended once the customer decides.
- **RFC 9728 protected resource metadata**: `/.well-known/oauth-protected-resource` on the API's host names the
  host as the resource, Keycloak's realm and the scopes, and every `WWW-Authenticate` challenge links to it.
- **Provider sign-in as FAPI 2.0 asks**: Keycloak's realm `bank` puts every client with the scope `accounts` or
  `payments` under its built-in FAPI 2.0 Security Profile: `private_key_jwt` with PS256, tokens bound to the
  client certificate (RFC 8705), PS256 signatures on the tokens.
- **The API as an OAuth 2.0 resource server**: Spring Security checks the token's signature against Keycloak's
  keys, its type, issuer, audience and expiry, then that it is bound to the certificate of this request, then the
  scope. Each refusal has the contract's code: `TOKEN_UNKNOWN`, `TOKEN_EXPIRED`, `CERTIFICATE_MISSING`,
  `ROLE_INVALID` and the rest.

- **The contract as code**: the Spring interfaces and models are generated from GD Next's Open Banking Ukraine
  YAML, so a path, header or field that disagrees with the contract does not compile.
- **Consents in PostgreSQL** with their rules: what may be read, of which accounts, until which day, and how
  often a day without the customer (`frequencyPerDay`, counted in one SQL statement). Expiry follows from
  `validTo`; each account gets its own `resourceId`, so no IBAN appears in a path.
- **The contract's errors**: RFC 7807 problem details with codes such as `CONSENT_INVALID` (401) or
  `ACCESS_EXCEEDED` (429); the catalogue is in [ARCHITECTURE.md](ARCHITECTURE.md#errors).
- **Kubernetes on one machine**: k3d runs k3s 1.36 in Docker with its own image registry. Traefik routes through
  the Gateway API, HTTPS only: the Gateway belongs to the `bank` namespace, each service's chart brings its route
  (the API's own host through a Traefik IngressRoute).
- **Helm and Jenkins**: one chart per service, this one and bank-core's, plus PostgreSQL's and Keycloak's for the
  platform and the provider app's. Jenkins tests, builds the images, pushes them and runs `helm upgrade --install
  --rollback-on-failure` with an account that may deploy in the `bank` and `provider` namespaces and nothing else,
  waits until only pods of the new image are left, then signs in as the test provider and takes consents and
  payments through the API via Traefik; then it deploys the provider app, whose key it cannot read, and goes
  through it as the customer.
- **Locked-down pods**: the namespace enforces the "restricted" Pod Security Standard. Pods run as user 10001 on a
  read-only file system, without a service account token, with probes on Spring Boot's liveness and readiness.
- **One trace and one request id across services**: a provider's `traceparent` continues into bank-core, and its
  `X-Request-ID` goes along as bank-core's `X-Request-Id`; its `baggage` stops at the bank. Every read of account
  data is logged under the consent id; logs are ECS JSON in the cluster, searchable in Kibana by either id.

Stack: Java 21, Spring Boot 4.1 (RestClient, JdbcClient, Thymeleaf, scheduling), Spring Security 7.1 (OAuth 2.0
resource server and login), Keycloak 26.8, openapi-generator, PostgreSQL 17 with Flyway, Micrometer Tracing with
OpenTelemetry and OTLP, the OpenTelemetry Collector 0.166, k3d with k3s 1.36, Traefik 3.7 with the Gateway API,
Helm 4, Jenkins, Elasticsearch, Kibana, Logstash, Filebeat and APM Server 9.5 on ECK 3.5, JUnit, Testcontainers,
WireMock, Maven. The design is in
[ARCHITECTURE.md](ARCHITECTURE.md).

## Build and test

Needs JDK 21 or newer. Copy bank-core's Maven wrapper once:

```
cp -r ~/projects/bank-core/mvnw ~/projects/bank-core/.mvn .
```

```
./mvnw verify
```

Unit tests (`*Test`) run first, then the integration tests (`*IT`) with PostgreSQL in Testcontainers (Docker must
be running), WireMock in place of bank-core and of Keycloak (its keys and its token endpoint), tokens the tests sign
themselves, and a customer whose sign-in and decisions the tests walk through as a browser would; then the 80 %
line-coverage gate (report in `target/site/jacoco/index.html`). The API code is generated into
`target/generated-sources` on every build.

The provider app is a project of its own, built with the same wrapper; its tests need no Docker:

```
./mvnw -f provider-app/pom.xml verify
```

## Run on this machine

PostgreSQL in Docker, on localhost port 5437 (`OPENBANKING_DB_PORT` picks another):

```
docker compose up -d
```

```
./mvnw spring-boot:run
```

The log shows the running version (`bank-open-api 0.8.1 started`), and the contract this version serves is at
`localhost:8090/openapi/bank-open-api.yaml`. Every call to the API needs a provider's token from Keycloak and the
client certificate it is bound to, and the customer pages need Keycloak's login, which only the cluster has, so on
this machine the API answers `401 TOKEN_UNKNOWN`; the integration tests cover both.

## Run in the k3d cluster

[docs/cluster.md](docs/cluster.md) has the steps: the tools (`scripts/install-tools.sh`), the cluster with the
test CA, Traefik's HTTPS Gateway and TLS options, PostgreSQL, Keycloak, the network policies, Jenkins' deploy
account, the logs and the traces (`scripts/cluster-up.sh`), Jenkins, Kibana, and calls by hand. Once both
services are deployed, the smoke test signs in as the test provider, takes consents and payments through Traefik
on the API's host, has the test customer decide on them on the bank's pages, and checks the edge's refusals and
the provider's rate limit:

```
bash scripts/smoke-test.sh
```

Jenkins deploys the provider app next. Open <https://provider.localhost:8443>, ask for access with the filled-in
IBAN and sign in at the bank as the test customer (docs/cluster.md has the password). The same journey by script:

```
bash scripts/smoke-test.sh --provider-app
```

Then Kibana's APM app (<https://kibana.localhost:8443/app/apm/services>) lists the five services, and a
transaction of the provider app opens its trace through Traefik, Keycloak, bank-open-api and bank-core.

## Layout

```
src/main/java/dev/tkachuk/openbanking/
  security/      the security chains: provider tokens, certificate binding, refusals in the contract's codes;
                 the customers' sign-in at Keycloak
  consents/      consents: rules, SQL, the consent endpoints
  payments/      payments: rules, SQL, the payment endpoints, the transfer at bank-core and its retries
  approval/      the customer pages: decide on consents and payments, withdraw consents
  accounts/      the account endpoints, read under a consent
  bankcore/      the client for bank-core's REST API
  common/        request id filter, error codes and handler, IBAN and id checks, clock, startup log, span names
src/main/resources/
  static/openapi/         the contract, from GD Next's Open Banking Ukraine YAML
  templates/customer/     the customer pages (Thymeleaf), static/customer/ their stylesheet
  db/migration/           Flyway migrations
charts/bank-open-api/     Helm chart: Deployment, Service, HTTPRoute (pages), IngressRoute (the API's host)
provider-app/             the provider app, a Spring Boot project of its own: bank/ signs in at Keycloak and calls
                          the API, web/ its pages and the customer's journey; chart/ its Helm chart
cluster/
  k3d.yaml                the cluster: k3s, registry, ports
  traefik-config.yaml     Traefik with the Gateway API
  eck-operator.yaml       the settings of Elastic's ECK operator chart
  platform/               namespaces (the provider app's in provider.yaml), HTTPS Gateway, Traefik's TLS
                          options and middleware, network policies, Jenkins' deploy account
  charts/postgres/        PostgreSQL from the official image
  charts/keycloak/        Keycloak from the official image, with the realm bank and its test customer
  charts/logging/         Elasticsearch, Kibana, Logstash with the masking (masking/mask.rb), Filebeat and APM
                          Server on ECK, the trace collector (tracing/collector.yaml), their network policies;
                          setup/ the lifecycle of logs and traces and Kibana's saved search
scripts/
  install-tools.sh        k3d, kubectl and helm into ~/.local/bin
  cluster-up.sh           the cluster and its platform, certificates included, safe to run again
  cluster-up-logging.sh   its part for logs and traces: ECK, the stack, the lifecycle, Kibana, the canaries
  smoke-test.sh           the test provider's consents and payments, the test customer's decisions, the edge;
                          with --provider-app the customer's journey through the provider app
  smoke-test-files.sh     the smoke test's certificates and password from the cluster's Secrets, for Jenkins
  wait-for-version.sh     Jenkins waits until only pods of the image it deployed are left
compose.yaml              PostgreSQL for running on this machine
Jenkinsfile               test, images, deploy to k3d, smoke tests of the API and of the provider app
```

## The steps

1. Walking skeleton: the cluster, Traefik, Helm, Jenkins, one unsecured endpoint.
2. The contract from GD Next's Ukrainian YAML; consents in PostgreSQL.
3. Keycloak: the bank realm, providers with private-key JWT and certificate-bound tokens, FAPI 2.0.
4. Customer approval of a consent, and the check on every call: token, certificate, consent, account.
5. Payments that become bank-core transfers, with ISO 20022 statuses.
6. Edge rules: client certificates and per-provider rate limits in Traefik, network policies.
7. Logs in Elasticsearch and Kibana through ECK, with a Logstash step that masks IBANs and names.
8. A small provider app and OpenTelemetry traces in Kibana. **(this version)**
