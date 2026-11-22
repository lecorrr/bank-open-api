# Architecture

Version 0.2.0, step 2 of 8. bank-open-api is the bank's side of open banking: providers call it, it calls
bank-core. This version brings the contract as code and consents: a provider reads account data only under a
valid consent that names the account and the data.

## Packages

| Package | Holds | May use |
|---|---|---|
| `api` | `ConsentsApi`, `AccountsApi` and the models, generated at build time from the contract | - |
| `consents` | `Consent` and its rules, `ConsentService`, `ConsentRepository`, `ConsentRequests`, `ConsentsController` | `api`, `common` |
| `accounts` | `AccountsController`: the account endpoints | `api`, `bankcore`, `consents`, `common` |
| `bankcore` | `BankCoreClient`, its properties and errors: everything that knows bank-core's API | `common` |
| `common` | `RequestIdFilter`, `ApiExceptionHandler`, `ErrorCode`, `ApiException`, `TimeConfig`, `StartupLogger` | nothing of ours |

The contract's types stay in `api` and the controllers; bank-core's JSON stays in `bankcore`; SQL stays in
`ConsentRepository`. `LayeringTest` fails the build when an import goes against the table, when a package has no
line in it, when an HTTP client appears outside `bankcore` or SQL outside `consents`.

## The contract

`src/main/resources/static/openapi/bank-open-api.yaml` is GD Next's Open Banking Ukraine 2.0 YAML (Apache 2.0,
licence next to it) cut down to what this version offers: creating, reading and deleting an account access
consent, and the account list, details and balances. The service serves it at `/openapi/bank-open-api.yaml`.

openapi-generator 7.22.0 turns it into Spring interfaces and models at build time. The controllers implement the
interfaces, so a path, header or field that disagrees with the contract does not compile. The interfaces carry
`@Validated`, so Spring checks the bean validation rules of the models (required fields, patterns, minimums) before
a controller runs, and the error names the field.

## A consent's life

| From | What happens | To |
|---|---|---|
| | `POST /v2/consents/account-access` | `received` |
| `received` | the customer approves (step 4) | `valid`: account reads allowed |
| `received` | the customer refuses (step 4) | `rejected` |
| `valid` | the customer withdraws it at the bank (step 4) | `revokedByPsu` |
| `received`, `valid` | `DELETE /v2/consents/account-access/{id}` | `terminatedByTpp` |
| `received`, `valid` | the day after `validTo` | `expired`: computed on every read, never stored |

What a provider may ask for, checked in `ConsentRequests` (what the bank offers) and `Consent` (the rules):

- type `detailed` only, with `access.payments` only: the bank keeps current accounts;
- each account named by its IBAN (ISO 13616 check digits, 15 to 34 characters), once, with `accountDetails`,
  `balances` or both;
- `validTo` not in the past; a later day than 180 days ahead (`bank-open-api.consents.max-validity-days`) is cut
  back to the last allowed day, as the contract foresees;
- `frequencyPerDay` from 1 to 4, and 1 for a one-off consent.

Each account of a consent gets its own `resourceId`, a random UUID: the id in the account paths.

## Reading account data

`GET /v2/accounts`, `/v2/accounts/{resourceId}` and `/v2/accounts/{resourceId}/balances`, with `Consent-ID`:

1. `ConsentService` loads the consent and checks it is valid, names the account and includes the data asked for.
2. A read without `PSU-IP-Address` is the provider reading on its own. Those are counted per consent, per data
   read (the list, or one kind of data of one account) and per day; past `frequencyPerDay` the answer is 429.
   Reads the customer starts are not counted.
3. Only then bank-core is asked: `GET /api/v1/accounts/{iban}` for each account. An account bank-core does not
   keep, or keeps in another currency than the consent named, is left out of the list and unknown on its own.

Balances are `interimAvailable` with today's date: bank-core has no holds or overdrafts, so its balance is what
the customer can spend now.

## Errors

RFC 7807 problem details with the contract's `code`. `ErrorCode` holds each code's HTTP status and the text a
provider reads; an `ApiException` carries a code and either a detail for the provider (which field, which rule,
never a value the provider sent) or, for failures inside the bank, a message that goes to the log only.

| Code | Status | When |
|---|---|---|
| `FORMAT_ERROR` | 400 | a header, parameter or body field breaks the contract or a consent rule |
| `PARAMETER_NOT_SUPPORTED` | 400 | access beyond current accounts, an account not named by IBAN, rights the bank does not offer |
| `CONSENT_TYPE_NOT_SUPPORTED` | 400 | a consent type other than `detailed` |
| `CONSENT_UNKNOWN` | 400 | the `Consent-ID` header names no consent |
| `CONSENT_INVALID` | 401 | the consent is not valid, or does not include this data |
| `CONSENT_EXPIRED` | 401 | the consent's `validTo` has passed |
| `CONSENT_UNKNOWN` | 403 | the path names no consent |
| `RESOURCE_UNKNOWN` | 404 | the account is not part of the consent, or no API path matches |
| `SERVICE_INVALID` | 405 | the method is not offered for the path |
| `REQUESTED_FORMATS_INVALID` | 406 | `Accept` asks for something other than JSON |
| `STATUS_INVALID` | 409 | the consent's status changed at the same moment |
| `FORMAT_ERROR` | 415 | the body is not JSON (Spring's status, the contract's code) |
| `ACCESS_EXCEEDED` | 429 | today's reads without the customer are used up |
| `INTERNAL_ERROR` | 500 | a bug; the provider quotes its `X-Request-ID` |
| `SERVICE_UNAVAILABLE` | 503 | bank-core did not answer or refused; the provider may retry |

## One request, end to end

```
provider ── GET localhost:8480/v2/accounts, X-Request-ID, Consent-ID
  └─ k3d load balancer :80 ── Traefik, entry point web (:8000)
       └─ Gateway bank-gateway, listener web ── HTTPRoute bank-open-api, path /v2
            └─ Service bank-open-api:8090 ── pod: RequestIdFilter ── AccountsController
                 ├─ ConsentService ── Service postgres:5432, database openbanking
                 └─ BankCoreClient ── Service bank-core:8080 ── GET /api/v1/accounts/{iban}
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
| `bank` | Gateway `bank-gateway`, PostgreSQL (`postgres`) with the databases `bank` and `openbanking`, their Secrets, account `jenkins-deployer` | `scripts/cluster-up.sh` |
| `bank` | `bank-core` and `bank-open-api`: Deployment, Service, HTTPRoute | Jenkins, with Helm |
| Docker | registry `k3d-bank-registry`, on this machine `localhost:5050` | k3d |

Ports on this machine, all on 127.0.0.1: 6550 the Kubernetes API, 8480 HTTP and 8443 HTTPS into Traefik, 5050
the registry.

## Rules

- **The contract leads**: the code is generated from it, and what the bank does not offer is refused with the
  contract's codes rather than ignored.
- **Logs name consents, not people**: consent ids and counts, never IBANs, names or amounts.
- **Configuration** comes from environment variables (`BANK_CORE_URL`, `DB_URL`, `DB_USER`, `DB_PASSWORD`,
  `LOGGING_STRUCTURED_FORMAT_CONSOLE`), which the Helm chart sets; the database login comes from the Secret
  `bank-open-api-db`.
- **Nothing in the cluster is changed by hand**: the platform comes from `cluster-up.sh`, the services from
  Jenkins. Running either again is safe.

## Decisions

- **Generated interfaces, a subset of the contract.** GD Next's YAML describes the whole Berlin Group API; the
  copy here keeps the operations this version offers, so the generated code has no endpoints that answer
  nothing. Each step adds its operations from the same source.
- **JdbcClient and Flyway, not JPA.** Three small tables and a handful of statements, among them an upsert with a
  condition; plain SQL shows exactly what runs. `Consent` is a plain class with its rules, and the repository maps
  it by hand.
- **A resourceId for each account of a consent, not the IBAN.** No IBAN in the paths providers call, so none in
  their access logs, and the same account under two consents has two ids, so one consent's ids say nothing about
  another's.
- **Expiry is computed, not stored.** A consent is expired when today is after `validTo`; no job has to change
  rows at midnight, and the status column holds only what someone decided.
- **Days are Kyiv days.** `TimeConfig`'s clock is in Europe/Kyiv for `validTo` and the daily read count, and ticks
  in microseconds because PostgreSQL keeps no more than that.
- **The daily read count is one SQL statement**: an insert that, on conflict, adds one only while the count is
  below the limit. Two reads at the same moment cannot both take the last one, with any number of pods.
- **Status changes check the status they started from.** `UPDATE ... WHERE status = <as read>`; a change made in
  between turns into 409 `STATUS_INVALID` instead of being overwritten.
- **The customer's approval comes in step 4.** Until then a new consent stays `received` and reads are refused;
  the tests approve in the database.
- **No provider identity yet.** Any caller can create consents until step 3 brings Keycloak and step 4 ties each
  consent to the provider that made it; the cluster has demo data only.
- **Read counts are kept.** One row per consent, data read and day; a cleanup of old days can come when it
  matters.

## Extending it

- **Endpoint**: its operation from GD Next's YAML into `bank-open-api.yaml`, then the generated method in a
  controller; tests with `@WebMvcTest` and, for the whole path, `BankOpenApiIT`.
- **Consent rule**: in `Consent` (or `ConsentRequests` for what the bank offers), with a case in `ConsentTest`.
- **Table or column**: a new Flyway migration `V2__...`; the old ones never change.
- **Call to bank-core**: a method in `BankCoreClient` that turns every failure into `BankCoreUnavailableException`.
- **Error**: a value in `ErrorCode` with its status, the contract's code and its text, then a test.
- **Package**: a line in `MAY_DEPEND_ON` of `LayeringTest` and in the table above.
- **Service in the cluster**: a chart like `charts/bank-open-api`, a Jenkinsfile with the same stages, and its
  database lines in `cluster-up.sh` (`ensure_secret`, `ensure_database`).
