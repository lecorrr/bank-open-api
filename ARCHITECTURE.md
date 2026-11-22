# Architecture

Version 0.1.0, step 1 of 8. bank-open-api is the bank's side of open banking: providers call it, it calls
bank-core. This version is the walking skeleton: every piece of the path exists, the security comes next.

## Packages

| Package | Holds | May use |
|---|---|---|
| `accounts` | `AccountsController`, `AccountList`, `AccountDetails`: the contract's account resources | `bankcore`, `common` |
| `bankcore` | `BankCoreClient`, its properties and errors: everything that knows bank-core's API | `common` |
| `common` | `RequestIdFilter`, `ApiExceptionHandler`, `ErrorCode`, `ApiException`, `StartupLogger` | nothing of ours |

The contract's types stay in `accounts`; bank-core's JSON stays in `bankcore`. When bank-core's API changes, only
`bankcore` changes. `LayeringTest` fails the build when an import goes against the table, when a package has no
line in it, or when an HTTP client appears outside `bankcore`.

## One request, end to end

```
provider ── GET localhost:8480/v2/accounts, X-Request-ID
  └─ k3d load balancer :80 ── Traefik, entry point web (:8000)
       └─ Gateway bank-gateway, listener web ── HTTPRoute bank-open-api, path /v2
            └─ Service bank-open-api:8090 ── pod: RequestIdFilter ── AccountsController
                 └─ BankCoreClient ── Service bank-core:8080 ── GET /api/v1/accounts
                      └─ bank-core ── Service postgres:5432, database bank
```

`RequestIdFilter` puts the provider's `X-Request-ID` into the logging MDC and echoes it. `BankCoreClient` sends it
on as `X-Request-Id`, the header bank-core logs under, and the RestClient from Spring Boot's builder sends the
`traceparent`. bank-core's log lines about the request then carry the same request id and trace id.

## The cluster

| Where | What | Made by |
|---|---|---|
| `kube-system` | Traefik with the Gateway API provider, CoreDNS, local-path storage | k3s, `cluster/traefik-config.yaml` |
| `bank` | namespace with the "restricted" Pod Security Standard | `scripts/cluster-up.sh` |
| `bank` | Gateway `bank-gateway`, PostgreSQL (`postgres`), Secrets, account `jenkins-deployer` | `scripts/cluster-up.sh` |
| `bank` | `bank-core` and `bank-open-api`: Deployment, Service, HTTPRoute | Jenkins, with Helm |
| Docker | registry `k3d-bank-registry`, on this machine `localhost:5050` | k3d |

Ports on this machine, all on 127.0.0.1: 6550 the Kubernetes API, 8480 HTTP and 8443 HTTPS into Traefik, 5050
the registry.

## Rules

- **The contract leads**: paths, headers and the JSON shape follow GD Next's Open Banking Ukraine YAML
  (`/v2/...`, `X-Request-ID`, `accountList`), even before step 2 generates the code from it.
- **Errors** are RFC 7807 problem details with a Berlin Group `code`. Code throws an `ApiException` with an
  `ErrorCode`; `ApiExceptionHandler.statusOf` is the only place a code meets an HTTP status, and its `switch` has
  no default, so a new code does not compile without one. The provider gets the code's fixed text: the
  exception's message may name the bank's systems and goes to the log only. Neither repeats what the caller
  sent, and log lines carry no account data.
- **Configuration** comes from environment variables (`BANK_CORE_URL`, `LOGGING_STRUCTURED_FORMAT_CONSOLE`),
  which the Helm chart sets.
- **Nothing in the cluster is changed by hand**: the platform comes from `cluster-up.sh`, the services from
  Jenkins. Running either again is safe.

## Decisions

- **Gateway API, not Ingress.** ingress-nginx stopped getting fixes in March 2026 and Ingress is frozen; the
  Gateway API is its successor, and the Traefik that k3s ships implements it. A listener's port must match one of
  Traefik's entry points: 8000 is `web`.
- **The Gateway belongs to the platform, the routes to the services.** `cluster-up.sh` makes `bank-gateway`; each
  chart brings an HTTPRoute that attaches to it. That is the split the Gateway API is built around: a platform
  team runs the entry point, application teams their routes. Traefik's own default Gateway is switched off.
- **A registry in the cluster's Docker network.** The host's Docker pushes to `localhost:5050`; a mirror in
  `cluster/k3d.yaml` makes the nodes pull the same names from the registry container. Images are tagged with the
  commit, never `latest`, so `kubectl get pods -o wide` shows which code runs.
- **Jenkins deploys with its own account.** `jenkins-deployer` may change Deployments, Services, ServiceAccounts,
  ConfigMaps, Secrets and HTTPRoutes in `bank`, and read pods and events. It cannot touch the cluster, other
  namespaces or PostgreSQL. Helm keeps each release in a Secret, so the account can read the namespace's Secrets
  too: on a shared cluster the database passwords would live in a vault instead.
- **PostgreSQL from the official image, with a small chart of our own.** The Bitnami images moved to an
  unpatched archive in August 2025. One instance serves every service; `cluster-up.sh` gives each its own
  database and role. The passwords are made once, kept in Secrets, and never appear on a command line.
- **The IBAN is the resourceId for now.** The contract wants an id for the account paths; step 2 brings
  consents, and with them ids that point to an account inside one consent.
- **No security yet.** Step 1 proves the path through the cluster. Steps 2 to 4 add consents, Keycloak with
  FAPI 2.0 and mTLS-bound tokens, and the check on every call; until then the API serves demo data only.

## Extending it

- **Endpoint**: a controller method in its package, following the contract; tests with `@WebMvcTest` and, for
  the whole path, `BankOpenApiIT`.
- **Call to bank-core**: a method in `BankCoreClient` that turns every failure into `BankCoreUnavailableException`.
- **Error**: a value in `ErrorCode` with its status and text in `ApiExceptionHandler`, then a test.
- **Package**: a line in `MAY_DEPEND_ON` of `LayeringTest` and in the table above.
- **Service in the cluster**: a chart like `charts/bank-open-api`, a Jenkinsfile with the same stages, and its
  database line in `cluster-up.sh` (`ensure_secret`, `ensure_database`).
