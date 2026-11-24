# Architecture

Version 0.4.0, step 4 of 8. bank-open-api is the bank's side of open banking: providers call it, it calls
bank-core. A provider reads account data only as itself (an access token from the bank's Keycloak, over mutual TLS
with the client certificate the token is bound to), only under its own consents, and from this version only under a
consent the customer approved on the bank's page, for accounts that customer holds.

## Packages

| Package | Holds | May use |
|---|---|---|
| `api` | `ConsentsApi`, `AccountsApi` and the models, generated at build time from the contract | - |
| `security` | `SecurityConfig`, `ProviderTokenValidator`, `CertificateBindingFilter`, `ClientCertificates`, `SecurityRefusals`, `AuthenticatedProvider`; for customers `CustomerSignInConfig`, `IdTokenDecoders`, `SignedInCustomer` | `common` |
| `consents` | `Consent` and its rules, `ConsentService`, `ConsentRepository`, `ConsentRequests`, `ConsentsController` | `api`, `common`, `security` |
| `approval` | `CustomerConsentsController` and its pages, `ApprovalService`: the customer approves, rejects, withdraws | `bankcore`, `consents`, `common`, `security` |
| `accounts` | `AccountsController`: the account endpoints | `api`, `bankcore`, `consents`, `common`, `security` |
| `bankcore` | `BankCoreClient`, its properties and errors: everything that knows bank-core's API | `common` |
| `common` | `RequestIdFilter`, `ApiExceptionHandler`, `ErrorCode`, `ApiException`, `PublicUrlProperties`, `TimeConfig`, `StartupLogger` | nothing of ours |

The contract's types stay in `api` and the controllers; bank-core's JSON stays in `bankcore`; SQL stays in
`ConsentRepository`; Spring Security stays in `security`, and the controllers learn the provider from
`AuthenticatedProvider` and the customer from `SignedInCustomer`. `LayeringTest` fails the build when an import goes
against the table, when a package has no line in it, when an HTTP client appears outside `bankcore` (bank-core) and
`security` (Keycloak), SQL outside `consents` or Spring Security outside `security`.

## The contract

`src/main/resources/static/openapi/bank-open-api.yaml` is GD Next's Open Banking Ukraine 2.0 YAML (Apache 2.0,
licence next to it) cut down to what this version offers: creating, reading and deleting an account access
consent, and the account list, details and balances. The service serves it at `/openapi/bank-open-api.yaml`.

openapi-generator 7.27.0 turns it into Spring interfaces and models at build time. The controllers implement the
interfaces, so a path, header or field that disagrees with the contract does not compile. The interfaces carry
`@Validated`, so Spring checks the bean validation rules of the models (required fields, patterns, minimums) before
a controller runs, and the error names the field.

## Providers and their tokens

A provider is a client of Keycloak's realm `bank` with the scope `accounts`, and has a client certificate from the
bank's CA (a test CA here, made by `scripts/cluster-up.sh`). One key pair serves both: the certificate for mutual
TLS, its key for signing in.

```
provider                       Traefik (:8443)                     Keycloak, realm bank            bank-open-api
   │ TLS with client certificate ──► checks it against the CA, puts it into
   │                                 X-Forwarded-Tls-Client-Cert
   │ POST /realms/bank/.../token: client credentials, client_assertion = JWT (PS256)
   │ ───────────────────────────────────────────────────────────────► checks the JWT with the
   │                                                                  provider's certificate; FAPI 2.0
   │ ◄─────────────────────── access token: typ at+jwt, aud bank-open-api, scope accounts, azp,
   │                           cnf.x5t#S256 = SHA-256 of the client certificate
   │ GET /v2/... with the token, over TLS with the same certificate
   │ ─────────────────────────────────────────────────────────────────────────────────────────────► security chain
```

Keycloak's client policy `fapi-2-for-providers` puts every client with the default scope `accounts` under the
built-in profile `fapi-2-security-profile`: confidential clients, `private_key_jwt` or mutual TLS sign-in, PS256 or
ES256 signatures, tokens bound to the client certificate, PKCE for the authorization code flow of step 4. The realm
signs with a PS256 key only. The client scope `accounts` adds the audience `bank-open-api`, and the clients use
the RFC 9068 token type `at+jwt`.

In bank-open-api, `SecurityConfig` builds the chain each request to `/v2` passes:

1. `RequestIdFilter`, ahead of the chain, so refused requests echo their `X-Request-ID` too.
2. Spring Security's bearer token filter: the token's PS256 signature against Keycloak's keys (read from
   `http://keycloak:8080/.../certs` and cached), then `ProviderTokenValidator`'s rules in order: type `at+jwt`,
   issuer `https://localhost:8443/realms/bank`, audience `bank-open-api`, expiry (30 s of clock skew), a provider
   in `azp`, and a `cnf.x5t#S256`.
3. `CertificateBindingFilter`: the certificate in Traefik's header must be readable, valid today, and the one whose
   thumbprint the token carries (RFC 8705). A stolen token is of no use without the provider's key.
4. The scope: `/v2/**` needs `accounts`. Health, info and the contract are open; everything else is refused.

Controllers then ask `AuthenticatedProvider` for the provider (the token's `azp`), and `ConsentService` treats
another provider's consent exactly as one that does not exist.

The API also describes itself as RFC 9728 asks: `GET /.well-known/oauth-protected-resource` answers, to anyone,
with the resource (`https://localhost:8443`), the authorization server (the realm's issuer), the scope `accounts`,
the bearer header and certificate-bound tokens, and every `WWW-Authenticate` challenge links there with
`resource_metadata`. Spring Security serves the document; `SecurityConfig` fills it in.

## Customers and their approval

The Berlin Group's redirect approach: the provider sends the customer's browser to the bank, which signs the
customer in, shows the consent and sends the browser back.

```
provider             customer's browser                      bank-open-api                     Keycloak, realm bank
 │ POST /v2/consents/account-access, TPP-Redirect-URI
 │ ◄── 201, _links.scaRedirect = https://localhost:8443/customer/consents/{id}
 │ opens it ─────────► GET /customer/consents/{id} ─────────────► no session: sign in first
 │                     ◄── 302 /oauth2/authorization/keycloak ◄── state, nonce, PKCE verifier into the session
 │                     ◄── 302 .../auth?client_id=bank-approval&code_challenge=..&code_challenge_method=S256
 │                         &prompt=login ─────────────────────────────────────────────────────► login form
 │                     ── username and password ───────────────────────────────────────────────► checks them
 │                     ◄── 302 /login/oauth2/code/keycloak?code&state ◄─────────────────────────
 │                     ── code ─────────────────────────────► state checked; code, verifier and client
 │                                                            secret to keycloak:8080 ─────────► ID token (PS256)
 │                                                            with customer_id ◄───────────────
 │                     ◄── the page: what the provider asks for, each account marked as the customer's or not
 │                     ── POST approve or reject, CSRF token ► bank-core: every account the customer's?
 │ ◄── 302 TPP-Redirect-URI ◄── (session ended) ◄──────────── consent valid or rejected, customer_id stored
```

- **Sign-in**: a second security chain (`CustomerSignInConfig`, before the API's) covers `/customer/**` and Spring
  Security's two sign-in paths. Keycloak's client `bank-approval` is confidential (client secret) and still uses
  PKCE with S256, as OAuth 2.1 asks and Keycloak requires for it; the authorization request asks for a fresh login
  every time (`prompt=login`). The client registration is built in code rather than from Keycloak's discovery
  document, which is at the public address the pod cannot reach; the code is redeemed and the keys are read inside
  the cluster. `IdTokenDecoders` checks the ID token as Spring Security would (type, expiry, issuer, audience,
  authorized party), but with PS256, the realm's only algorithm. The nonce and the state are Spring Security's.
- **The customer**: Keycloak's mapper puts the user attribute `customer_id` into the ID token (only there, not into
  access tokens). The realm's user profile declares the attribute for administrators only: a customer who could
  change it could read someone else's accounts. `SignedInCustomer` gives the pages the number and the name.
- **The rule**: a customer may approve only a consent whose accounts are all theirs, in the currency named, as
  bank-core says (`customerId` of each account, bank-core 0.8.0). Rejecting needs no such check. The customer's
  number is stored with the new status (`consent.customer_id`, V3); a decided consent shows its details only to
  that customer, and only that customer may withdraw it (`revokedByPsu`). V3's check keeps every decided status
  with a customer.
- **The pages**: Thymeleaf, no JavaScript. Every form carries Spring Security's CSRF token; the
  Content-Security-Policy allows the stylesheet and nothing else, and no other site may frame the pages; the
  session cookie is `Secure`, `HttpOnly` and `SameSite=Lax`, and the session lasts ten minutes idle and ends once
  the customer decides. Refusals and failures are pages too (`No such consent`, `Not your accounts`, `Already
  decided`), and a sign-in that fails (an old link, the back button at Keycloak) ends on the bank's page, not on
  Spring Security's `/login?error`, which this chain does not serve.
- **Behind Traefik**: Tomcat takes the scheme, host and port from `X-Forwarded-*` (`server.forward-headers-strategy:
  native`), so the redirects Spring Security builds point at `https://localhost:8443`. The links the bank hands
  out (scaRedirect, the redirect URI, RFC 9728's resource) come from `bank-open-api.public-url` instead, never from
  the request.

## A consent's life

| From | What happens | To |
|---|---|---|
| | `POST /v2/consents/account-access` | `received` |
| `received` | the customer approves on the bank's page; every account is theirs | `valid`: account reads allowed |
| `received` | the customer rejects it on the bank's page | `rejected` |
| `valid` | the customer who approved withdraws it on the bank's page | `revokedByPsu` |
| `received`, `valid` | `DELETE /v2/consents/account-access/{id}` | `terminatedByTpp` |
| `received`, `valid` | the day after `validTo` | `expired`: computed on every read, never stored |

Every consent belongs to the provider that asked for it (`provider_id`). Consents made before 0.3.0 have none, and
no provider sees them. A decided consent also names the customer who decided (`customer_id`); a consent that was
`valid` before 0.4.0 names nobody, so it covers no account (V3's check leaves such rows as they are).

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
   keep, keeps in another currency than the consent named, or no longer under the customer who approved, is left
   out of the list and unknown on its own (`RESOURCE_UNKNOWN`).

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
| `CONSENT_UNKNOWN` | 400 | the `Consent-ID` header names no consent of this provider |
| `TOKEN_UNKNOWN` | 401 | no access token, or one the bank cannot read: malformed, not signed by Keycloak's key |
| `TOKEN_INVALID` | 401 | a token of the bank, but not for this: another type, issuer or audience, no provider, not bound to a certificate, or bound to another one |
| `TOKEN_EXPIRED` | 401 | the token's expiry has passed |
| `CERTIFICATE_MISSING` | 401 | the request came without the provider's client certificate |
| `CERTIFICATE_INVALID` | 401 | the certificate in Traefik's header cannot be read, or is not valid yet |
| `CERTIFICATE_EXPIRED` | 401 | the certificate's end date has passed |
| `ROLE_INVALID` | 401 | the token lacks the scope `accounts`: the provider has no role for account information |
| `CONSENT_INVALID` | 401 | the consent is not valid, or does not include this data |
| `CONSENT_EXPIRED` | 401 | the consent's `validTo` has passed |
| `CONSENT_UNKNOWN` | 403 | the path names no consent of this provider |
| `RESOURCE_UNKNOWN` | 404 | the account is not part of the consent, or no API path matches |
| `SERVICE_INVALID` | 405 | the method is not offered for the path |
| `REQUESTED_FORMATS_INVALID` | 406 | `Accept` asks for something other than JSON |
| `STATUS_INVALID` | 409 | the consent's status changed at the same moment |
| `FORMAT_ERROR` | 415 | the body is not JSON (Spring's status, the contract's code) |
| `ACCESS_EXCEEDED` | 429 | today's reads without the customer are used up |
| `INTERNAL_ERROR` | 500 | a bug; the provider quotes its `X-Request-ID` |
| `SERVICE_UNAVAILABLE` | 503 | bank-core, or Keycloak's keys, did not answer; the provider may retry |

Refusals of the token and the certificate come with RFC 6750's `WWW-Authenticate` header: `Bearer` without an
error when there is no token, `Bearer error="invalid_token"` or `error="insufficient_scope"` otherwise, each with
RFC 9728's `resource_metadata` link. They go through Spring MVC's exception handling (`SecurityRefusals`), so
`ApiExceptionHandler` writes every error in the same form. The customer pages answer with pages, not JSON.

## One request, end to end

```
provider ── GET https://localhost:8443/v2/accounts, client certificate, Authorization, X-Request-ID, Consent-ID
  └─ k3d load balancer :443 ── Traefik, entry point websecure (:8443): TLS, client certificate checked
       └─ Gateway bank-gateway, listener websecure ── HTTPRoute bank-open-api, path /v2,
          middleware pass-client-cert
            └─ Service bank-open-api:8090 ── pod: RequestIdFilter ── security chain ── AccountsController
                 ├─ (keys, cached) Service keycloak:8080 ── /realms/bank/protocol/openid-connect/certs
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
| `bank` | Gateway `bank-gateway` (HTTPS), Traefik's TLS options, default certificate and middleware (`cluster/platform/edge.yaml`), the Secrets of the test CA's certificates | `scripts/cluster-up.sh` |
| `bank` | PostgreSQL (`postgres`) with the databases `bank`, `openbanking` and `keycloak`, Keycloak (`keycloak`) with the realm `bank` (clients `smoke-test` and `bank-approval`, test customer `artem.demo`), their Secrets, account `jenkins-deployer` | `scripts/cluster-up.sh` |
| `bank` | `bank-core` and `bank-open-api`: Deployment, Service, HTTPRoute | Jenkins, with Helm |
| Docker | registry `k3d-bank-registry`, on this machine `localhost:5050` | k3d |
| this machine | the test CA's key and certificate, a copy of the smoke-test provider's and of the test customer's password, in `~/.config/bank-open-api/pki` | `scripts/cluster-up.sh` |

Ports on this machine, all on 127.0.0.1: 6550 the Kubernetes API, 8443 HTTPS into Traefik, 5050 the registry.
8480, Traefik's HTTP entry point, has no routes.

## Rules

- **The contract leads**: the code is generated from it, and what the bank does not offer is refused with the
  contract's codes rather than ignored.
- **Logs name consents, not people**: consent ids and counts, never IBANs, names or amounts.
- **Configuration** comes from environment variables (`BANK_CORE_URL`, `DB_URL`, `DB_USER`, `DB_PASSWORD`,
  `PROVIDER_TOKENS_ISSUER`, `PROVIDER_TOKENS_JWK_SET_URI`, `PUBLIC_URL`, `CUSTOMER_SIGN_IN_TOKEN_URI`,
  `CUSTOMER_SIGN_IN_CLIENT_SECRET`, `LOGGING_STRUCTURED_FORMAT_CONSOLE`), which the Helm chart sets; the database
  login comes from the Secret `bank-open-api-db`, the client secret from `bank-approval-client`.
- **No token, certificate or key in a log line**: refusals log the contract's code and a detail written for the
  provider; the provider id (a client id), the customer's number and consent ids are logged, nothing a token holds
  beyond that. A failed sign-in logs only its OAuth 2.0 error code.
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
- **bank-core says who holds an account.** The customer's number is a field of bank-core's account; Keycloak only
  carries the same number for the signed-in customer. The alternative, a list of accounts in Keycloak's user,
  would keep a fact about accounts outside the bank's books. The check runs at approval and again at every read,
  so an account that changes hands drops out of the consent.
- **A fresh login for every approval** (`prompt=login`). PSD2 wants strong customer authentication for each
  consent, and a Keycloak session left in a shared browser must not approve for the next person. Keycloak's own
  session is not ended after the decision; RP-initiated logout could come later.
- **Server-rendered pages without JavaScript.** Four small pages need no front-end build, and with no script at
  all the Content-Security-Policy can forbid every script. `form-action` is left out of it on purpose: browsers
  apply it to the redirect after a form too, and a decision redirects to the provider.
- **No HSTS on localhost.** Browsers apply Strict-Transport-Security to every port of a host, so the bank's
  pages, its API and Keycloak's login (the realm's `browserSecurityHeaders`) send none: Jenkins on
  `http://localhost:8081` and Keycloak's admin console on 8082 keep working. On the bank's own domain the edge
  would send it.
- **The decision ends the session.** The customer has done what they came for; the next page asks for a sign-in
  again, so a shared computer keeps nothing of it.
- **Mutual TLS ends at Traefik, and is checked when offered.** Traefik's TLS options apply to every HTTPS router
  alike, and step 4's approval pages are for browsers without a client certificate. So Traefik verifies a
  certificate when one comes (`VerifyClientCertIfGiven`) and the services require it where it matters: Keycloak to
  bind a token, bank-open-api on every call. Traefik's entry point drops `X-Forwarded-Tls-Client-Cert` (and headers
  whose names only look like it) when a client sends it, so the header can only come from Traefik's middleware.
  Inside the cluster any pod can still reach the services directly; step 6 adds the NetworkPolicies that leave
  only Traefik.
- **The binding check is a filter of its own.** Spring Security checks the token without seeing the request;
  `CertificateBindingFilter` runs right after it with the request in hand, and its refusals have their own codes
  (`CERTIFICATE_MISSING`, `CERTIFICATE_EXPIRED`) instead of a generic invalid token.
- **The provider is the token's `azp`.** Keycloak names the client a token was issued to there, for the client
  credentials grant as for step 4's authorization code flow. A consent stores it and is unknown to every other
  provider: 403 or 400 `CONSENT_UNKNOWN`, the same answer as for a consent that does not exist.
- **Keycloak's keys failing is a 503, not a 401.** A provider's token may well be fine when the keys cannot be
  read, so the answer says to retry. Spring Security would rethrow that failure as a 500; `SecurityConfig` hands
  it to `SecurityRefusals` instead.
- **One error format.** Spring Security's refusals go through Spring MVC's `HandlerExceptionResolver`, so
  `ApiExceptionHandler` writes and logs them as it does every other error.
- **The realm is code, imported afresh when it changes.** `cluster/charts/keycloak/realm/bank-realm.json` is
  imported when Keycloak starts and finds no realm `bank` (`start --import-realm` never updates a realm that
  exists). So when the file, or a Secret it names, is new, `cluster-up.sh` empties Keycloak's database before the
  upgrade; changes made in the admin console go with it, which is the point. The smoke-test provider's
  certificate, the approval client's secret and the test customer's password come in through environment
  variables, from Secrets `cluster-up.sh` made, so the realm file holds no secret.
- **The test CA's key never enters the cluster.** It stays in `~/.config/bank-open-api/pki` on this machine;
  the cluster gets certificates, and the keys of the Gateway and the test provider. A renewed provider
  certificate keeps its key, so Keycloak, which checks the provider's JWTs with the key it was given, need not
  change.
- **Read counts are kept.** One row per consent, data read and day; a cleanup of old days can come when it
  matters.

## Extending it

- **Endpoint**: its operation from GD Next's YAML into `bank-open-api.yaml`, then the generated method in a
  controller; tests with `@WebMvcTest` and, for the whole path, `BankOpenApiIT`.
- **Consent rule**: in `Consent` (or `ConsentRequests` for what the bank offers), with a case in `ConsentTest`.
- **Table or column**: a new Flyway migration `V4__...`; the old ones never change.
- **Call to bank-core**: a method in `BankCoreClient` that turns every failure into `BankCoreUnavailableException`.
- **Error**: a value in `ErrorCode` with its status, the contract's code and its text, then a test.
- **Provider**: a client in the realm like `smoke-test` (scope `accounts`, `client-jwt`, the PS256 and binding
  attributes), with its certificate from the bank's CA; the client policy then applies FAPI 2.0 to it.
- **Token rule**: a check in `ProviderTokenValidator` with its code, and a case in `ProviderTokenValidatorTest`.
- **Customer page**: a method in `CustomerConsentsController` and a template in `templates/customer/` that takes the
  head from `customer/layout`, forms with `th:action` (so they carry the CSRF token); tests with `oidcLogin()` in
  `CustomerConsentsControllerTest` and, through a sign-in, in `CustomerApprovalIT`.
- **Package**: a line in `MAY_DEPEND_ON` of `LayeringTest` and in the table above.
- **Service in the cluster**: a chart like `charts/bank-open-api`, a Jenkinsfile with the same stages, and its
  database lines in `cluster-up.sh` (`ensure_secret`, `ensure_database`).
