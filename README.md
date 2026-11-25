# bank-open-api

The bank's open banking API: licensed providers (budget apps, payment services) read account data and start
payments, only with the customer's consent. It follows the Berlin Group NextGenPSD2 model that Ukraine's
open banking specifications use, and sits in front of [bank-core](https://github.com/lecorrr/bank-core). Project 3
of three, with bank-core and [bank-integration](https://github.com/lecorrr/bank-integration).

**Status: 0.6.1, step 6 of 8: edge rules.** Providers call the API on a host of its own,
`https://api.localhost:8443`, where Traefik takes no connection without a client certificate from the bank's CA.
The customer pages and Keycloak stay on the bank's address, `https://localhost:8443`, where browsers come without
one. Each provider has a rate limit of its own at the edge, keyed by its certificate, and network policies let only
Traefik reach the services from outside, so the certificate header bank-open-api trusts can only come from Traefik.
Consents and payments work as in 0.5.0.

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
  platform. Jenkins tests, builds the image, pushes it and runs `helm upgrade --install --rollback-on-failure`
  with an account that may deploy in the `bank` namespace and nothing else, waits until only pods of the new image
  are left, then signs in as the test provider and takes consents and payments through the API via Traefik.
- **Locked-down pods**: the namespace enforces the "restricted" Pod Security Standard. Pods run as user 10001 on a
  read-only file system, without a service account token, with probes on Spring Boot's liveness and readiness.
- **One trace and one request id across services**: a provider's `traceparent` continues into bank-core, and its
  `X-Request-ID` goes along as bank-core's `X-Request-Id`. Every read of account data is logged under the consent
  id; logs are ECS JSON in the cluster.

Stack: Java 21, Spring Boot 4.1 (RestClient, JdbcClient, Thymeleaf, scheduling), Spring Security 7.1 (OAuth 2.0 resource
server and login), Keycloak 26.8, openapi-generator, PostgreSQL 17 with Flyway, Micrometer Tracing with
OpenTelemetry, k3d with k3s 1.36, Traefik 3.7 with the Gateway API, Helm 4, Jenkins, JUnit, Testcontainers,
WireMock, Maven. The design is in [ARCHITECTURE.md](ARCHITECTURE.md).

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

## Run on this machine

PostgreSQL in Docker, on localhost port 5437 (`OPENBANKING_DB_PORT` picks another):

```
docker compose up -d
```

```
./mvnw spring-boot:run
```

The log shows the running version (`bank-open-api 0.6.1 started`), and the contract this version serves is at
`localhost:8090/openapi/bank-open-api.yaml`. Every call to the API needs a provider's token from Keycloak and the
client certificate it is bound to, and the customer pages need Keycloak's login, which only the cluster has, so on
this machine the API answers `401 TOKEN_UNKNOWN`; the integration tests cover both.

## Run in the k3d cluster

[docs/cluster.md](docs/cluster.md) has the steps: the tools (`scripts/install-tools.sh`), the cluster with the
test CA, Traefik's HTTPS Gateway and TLS options, PostgreSQL, Keycloak, the network policies and Jenkins' deploy
account (`scripts/cluster-up.sh`), Jenkins, and calls by hand. Once both services are deployed, the smoke test
signs in as the test provider, takes consents and payments through Traefik on the API's host, has the test customer
decide on them on the bank's pages, and checks the edge's refusals and the provider's rate limit:

```
bash scripts/smoke-test.sh
```

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
  common/        request id filter, error codes and handler, IBAN and id checks, clock, startup log
src/main/resources/
  static/openapi/         the contract, from GD Next's Open Banking Ukraine YAML
  templates/customer/     the customer pages (Thymeleaf), static/customer/ their stylesheet
  db/migration/           Flyway migrations
charts/bank-open-api/     Helm chart: Deployment, Service, HTTPRoute (pages), IngressRoute (the API's host)
cluster/
  k3d.yaml                the cluster: k3s, registry, ports
  traefik-config.yaml     Traefik with the Gateway API
  platform/               namespace, HTTPS Gateway, Traefik's TLS options and middleware, network policies,
                          Jenkins' deploy account
  charts/postgres/        PostgreSQL from the official image
  charts/keycloak/        Keycloak from the official image, with the realm bank and its test customer
scripts/
  install-tools.sh        k3d, kubectl and helm into ~/.local/bin
  cluster-up.sh           the cluster and its platform, certificates included, safe to run again
  smoke-test.sh           the test provider's consents and payments, the test customer's decisions, the edge
compose.yaml              PostgreSQL for running on this machine
Jenkinsfile               test, image, deploy to k3d, smoke test
```

## The steps

1. Walking skeleton: the cluster, Traefik, Helm, Jenkins, one unsecured endpoint.
2. The contract from GD Next's Ukrainian YAML; consents in PostgreSQL.
3. Keycloak: the bank realm, providers with private-key JWT and certificate-bound tokens, FAPI 2.0.
4. Customer approval of a consent, and the check on every call: token, certificate, consent, account.
5. Payments that become bank-core transfers, with ISO 20022 statuses.
6. Edge rules: client certificates and per-provider rate limits in Traefik, network policies. **(this version)**
7. Logs in Elasticsearch and Kibana through ECK, with a Logstash step that masks IBANs and names.
8. A small provider app and OpenTelemetry traces in Kibana.
