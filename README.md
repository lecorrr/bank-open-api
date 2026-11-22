# bank-open-api

The bank's open banking API: licensed providers (budget apps, payment services) read account data and, later,
start payments, only with the customer's consent. It follows the Berlin Group NextGenPSD2 model that Ukraine's
open banking specifications use, and sits in front of [bank-core](https://github.com/lecorrr/bank-core). Project 3
of three, with bank-core and [bank-integration](https://github.com/lecorrr/bank-integration).

**Status: 0.1.0, step 1 of 8: the walking skeleton.** One endpoint, `GET /v2/accounts`, runs in a k3d cluster
behind Traefik, and Jenkins deploys it there. It has no consent or provider check yet (steps 2 to 4), so it is
for the demo data only.

- **The contract's shape from the start**: `X-Request-ID` (a UUID) is required and echoed back, the accounts come
  in the contract's `accountList` form, and errors are RFC 7807 problem details with Berlin Group codes such as
  `FORMAT_ERROR`. The contract itself, GD Next's Open Banking Ukraine YAML, arrives in step 2.
- **Kubernetes on one machine**: k3d runs k3s 1.36 in Docker with its own image registry. Traefik routes through
  the Gateway API: the Gateway belongs to the `bank` namespace, each service's chart brings its route.
- **Helm and Jenkins**: one chart per service, this one and bank-core's. Jenkins tests, builds the image, pushes
  it and runs `helm upgrade --install --rollback-on-failure` with an account that may deploy in the `bank`
  namespace and nothing else, then calls the API through Traefik.
- **Locked-down pods**: the namespace enforces the "restricted" Pod Security Standard. Pods run as user 10001 on a
  read-only file system, without a service account token, with probes on Spring Boot's liveness and readiness.
- **One trace and one request id across services**: a provider's `traceparent` continues into bank-core, and its
  `X-Request-ID` goes along as bank-core's `X-Request-Id`, so whatever bank-core logs about the call carries the
  provider's ids. Every read of account data is logged; logs are ECS JSON in the cluster.

Stack: Java 21, Spring Boot 4.1 (RestClient), Micrometer Tracing with OpenTelemetry, k3d with k3s 1.36, Traefik
3.7 with the Gateway API, Helm 4, Jenkins, PostgreSQL 17, JUnit, WireMock, Maven. The design is in
[ARCHITECTURE.md](ARCHITECTURE.md).

## Build and test

Needs JDK 21 or newer. Copy bank-core's Maven wrapper once:

```
cp -r ~/projects/bank-core/mvnw ~/projects/bank-core/.mvn .
```

```
./mvnw verify
```

Unit tests (`*Test`) run first, then the integration tests (`*IT`) with WireMock in place of bank-core, then the
80 % line-coverage gate (report in `target/site/jacoco/index.html`).

## Run on this machine

Start bank-core first (its README: `spring-boot:run` with the demo profile on port 8080), then:

```
./mvnw spring-boot:run
```

The log shows the running version (`bank-open-api 0.1.0 started`). The API is on port 8090:

```
curl -sS -w '\n' -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)" localhost:8090/v2/accounts
```

Without `X-Request-ID` the answer is `400` with `"code": "FORMAT_ERROR"`; with bank-core down it is `503`.

## Run in the k3d cluster

[docs/cluster.md](docs/cluster.md) has the steps: the tools (`scripts/install-tools.sh`), the cluster with its
Gateway, PostgreSQL and Jenkins' deploy account (`scripts/cluster-up.sh`), Jenkins, and the check. Once both
services are deployed:

```
curl -sS -w '\n' -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)" localhost:8480/v2/accounts
```

## Layout

```
src/main/java/dev/tkachuk/openbanking/
  accounts/      the account endpoints for providers
  bankcore/      the client for bank-core's REST API
  common/        request id filter, error codes and handler, startup log
charts/bank-open-api/     Helm chart: Deployment, Service, HTTPRoute
cluster/
  k3d.yaml                the cluster: k3s, registry, ports
  traefik-config.yaml     Traefik with the Gateway API
  platform/               namespace, Gateway, Jenkins' deploy account
  charts/postgres/        PostgreSQL from the official image
scripts/
  install-tools.sh        k3d, kubectl and helm into ~/.local/bin
  cluster-up.sh           the cluster and its platform, safe to run again
Jenkinsfile               test, image, deploy to k3d, smoke test
```

## The steps

1. Walking skeleton: the cluster, Traefik, Helm, Jenkins, one unsecured endpoint. **(this version)**
2. The contract from GD Next's Ukrainian YAML; consents in PostgreSQL.
3. Keycloak: the bank realm, providers with private-key JWT and certificate-bound tokens, FAPI 2.0.
4. Customer approval of a consent, and the check on every call: token, certificate, consent, account.
5. Payments that become bank-core transfers, with ISO 20022 statuses.
6. Edge rules: client certificates and per-provider rate limits in Traefik.
7. Logs in Elasticsearch and Kibana through ECK, with a Logstash step that masks IBANs and names.
8. A small provider app and OpenTelemetry traces in Kibana.
