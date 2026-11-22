# bank-open-api

The bank's open banking API: licensed providers (budget apps, payment services) read account data and, later,
start payments, only with the customer's consent. It follows the Berlin Group NextGenPSD2 model that Ukraine's
open banking specifications use, and sits in front of [bank-core](https://github.com/lecorrr/bank-core). Project 3
of three, with bank-core and [bank-integration](https://github.com/lecorrr/bank-integration).

**Status: 0.2.0, step 2 of 8: the contract and consents.** A provider asks for a consent to read named accounts,
reads its status, and deletes it. Account data comes only under a valid consent, and the customer's approval
arrives in step 4: until then a new consent stays `received` and reads are refused (the tests approve consents in
the database). There is no provider identity yet (step 3), so the cluster holds demo data only.

- **The contract as code**: the Spring interfaces and models are generated from GD Next's Open Banking Ukraine
  YAML, so a path, header or field that disagrees with the contract does not compile.
- **Consents in PostgreSQL** with their rules: what may be read, of which accounts, until which day, and how
  often a day without the customer (`frequencyPerDay`, counted in one SQL statement). Expiry follows from
  `validTo`; each account gets its own `resourceId`, so no IBAN appears in a path.
- **The contract's errors**: RFC 7807 problem details with codes such as `CONSENT_INVALID` (401) or
  `ACCESS_EXCEEDED` (429); the catalogue is in [ARCHITECTURE.md](ARCHITECTURE.md#errors).
- **Kubernetes on one machine**: k3d runs k3s 1.36 in Docker with its own image registry. Traefik routes through
  the Gateway API: the Gateway belongs to the `bank` namespace, each service's chart brings its route.
- **Helm and Jenkins**: one chart per service, this one and bank-core's. Jenkins tests, builds the image, pushes
  it and runs `helm upgrade --install --rollback-on-failure` with an account that may deploy in the `bank`
  namespace and nothing else, then takes a consent through the API via Traefik.
- **Locked-down pods**: the namespace enforces the "restricted" Pod Security Standard. Pods run as user 10001 on a
  read-only file system, without a service account token, with probes on Spring Boot's liveness and readiness.
- **One trace and one request id across services**: a provider's `traceparent` continues into bank-core, and its
  `X-Request-ID` goes along as bank-core's `X-Request-Id`. Every read of account data is logged under the consent
  id; logs are ECS JSON in the cluster.

Stack: Java 21, Spring Boot 4.1 (RestClient, JdbcClient), openapi-generator, PostgreSQL 17 with Flyway, Micrometer
Tracing with OpenTelemetry, k3d with k3s 1.36, Traefik 3.7 with the Gateway API, Helm 4, Jenkins, JUnit,
Testcontainers, WireMock, Maven. The design is in [ARCHITECTURE.md](ARCHITECTURE.md).

## Build and test

Needs JDK 21 or newer. Copy bank-core's Maven wrapper once:

```
cp -r ~/projects/bank-core/mvnw ~/projects/bank-core/.mvn .
```

```
./mvnw verify
```

Unit tests (`*Test`) run first, then the integration tests (`*IT`) with PostgreSQL in Testcontainers (Docker must
be running) and WireMock in place of bank-core, then the 80 % line-coverage gate (report in
`target/site/jacoco/index.html`). The API code is generated into `target/generated-sources` on every build.

## Run on this machine

PostgreSQL in Docker, on localhost port 5437 (`OPENBANKING_DB_PORT` picks another):

```
docker compose up -d
```

Start bank-core too (its README: `spring-boot:run` with the demo profile on port 8080), then:

```
./mvnw spring-boot:run
```

The log shows the running version (`bank-open-api 0.2.0 started`). The API is on port 8090. Ask for a consent to
the balances of bank-core's first demo account, and keep its id:

```
CONSENT_ID=$(curl -sS -X POST localhost:8090/v2/consents/account-access -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)" -H 'PSU-IP-Address: 192.0.2.10' -H 'Content-Type: application/json' -d '{"access": {"payments": [{"account": {"iban": "UA843999990000000000000000001"}, "rights": ["balances"]}]}, "consentType": "detailed", "recurringIndicator": true, "validTo": "9999-12-31", "frequencyPerDay": 4}' | sed -n 's/.*"consentId":"\([^"]*\)".*/\1/p')
```

```
curl -sS -w '\n' -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)" -H "Consent-ID: $CONSENT_ID" localhost:8090/v2/accounts
```

The answer is `401` with `"code": "CONSENT_INVALID"`: the consent is `received`. Until step 4 brings the
customer's approval page, approve it in the database, then run the same `curl` again for the account under its
`resourceId`:

```
docker compose exec postgres psql -U openbanking -c "UPDATE consent SET status = 'valid' WHERE id = '$CONSENT_ID'"
```

The contract this version serves is at `localhost:8090/openapi/bank-open-api.yaml`.

## Run in the k3d cluster

[docs/cluster.md](docs/cluster.md) has the steps: the tools (`scripts/install-tools.sh`), the cluster with its
Gateway, PostgreSQL and Jenkins' deploy account (`scripts/cluster-up.sh`), Jenkins, and the check. Once both
services are deployed, the smoke test takes a consent through Traefik:

```
bash scripts/smoke-test.sh
```

## Layout

```
src/main/java/dev/tkachuk/openbanking/
  consents/      consents: rules, SQL, the consent endpoints
  accounts/      the account endpoints, read under a consent
  bankcore/      the client for bank-core's REST API
  common/        request id filter, error codes and handler, clock, startup log
src/main/resources/
  static/openapi/         the contract, from GD Next's Open Banking Ukraine YAML
  db/migration/           Flyway migrations
charts/bank-open-api/     Helm chart: Deployment, Service, HTTPRoute
cluster/
  k3d.yaml                the cluster: k3s, registry, ports
  traefik-config.yaml     Traefik with the Gateway API
  platform/               namespace, Gateway, Jenkins' deploy account
  charts/postgres/        PostgreSQL from the official image
scripts/
  install-tools.sh        k3d, kubectl and helm into ~/.local/bin
  cluster-up.sh           the cluster and its platform, safe to run again
  smoke-test.sh           a consent through the API, after each deploy
compose.yaml              PostgreSQL for running on this machine
Jenkinsfile               test, image, deploy to k3d, smoke test
```

## The steps

1. Walking skeleton: the cluster, Traefik, Helm, Jenkins, one unsecured endpoint.
2. The contract from GD Next's Ukrainian YAML; consents in PostgreSQL. **(this version)**
3. Keycloak: the bank realm, providers with private-key JWT and certificate-bound tokens, FAPI 2.0.
4. Customer approval of a consent, and the check on every call: token, certificate, consent, account.
5. Payments that become bank-core transfers, with ISO 20022 statuses.
6. Edge rules: client certificates and per-provider rate limits in Traefik.
7. Logs in Elasticsearch and Kibana through ECK, with a Logstash step that masks IBANs and names.
8. A small provider app and OpenTelemetry traces in Kibana.
