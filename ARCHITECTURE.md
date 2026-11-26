# Architecture

Version 0.8.0, step 8 of 8. bank-open-api is the bank's side of open banking: providers call it, it calls
bank-core. A provider calls only as itself: an access token from the bank's Keycloak, over mutual TLS on the
API's own host with the client certificate the token is bound to. It reads account data only under its own
consents that the customer approved on the bank's page, for accounts that customer holds, and it initiates
payments, which the customer approves on the same pages and the bank then makes as bank-core transfers. The edge
does part of the guarding: Traefik takes no connection to the API's host without a certificate from the bank's CA
and holds each provider to a rate limit, and network policies leave Traefik the only way in. The logs of the
bank's namespace go to Elasticsearch through Logstash, which masks personal data before anything is stored. From
this version a provider app outside the bank goes through the whole flow as a provider would, and the services'
traces reach Kibana's APM app through an OpenTelemetry Collector that keeps only what its allow-list names.

## Packages

| Package | Holds | May use |
|---|---|---|
| `api` | `ConsentsApi`, `AccountsApi`, `PaymentsApi` and the models, generated at build time from the contract | - |
| `security` | `SecurityConfig`, `ProviderTokenValidator`, `CertificateBindingFilter`, `ClientCertificates`, `SecurityRefusals`, `AuthenticatedProvider`; for customers `CustomerSignInConfig`, `IdTokenDecoders`, `SignedInCustomer` | `common` |
| `consents` | `Consent` and its rules, `ConsentService`, `ConsentRepository`, `ConsentRequests`, `ConsentsController` | `api`, `common`, `security` |
| `payments` | `Payment` and its rules, `PaymentService`, `PaymentRepository`, `PaymentRequests`, `PaymentsController`, `PaymentRetryJob` | `api`, `bankcore`, `common`, `security` |
| `approval` | `CustomerConsentsController`, `CustomerPaymentsController` and their pages, `CustomerPageErrors`, `ApprovalService`: the customer decides on consents and payments, withdraws consents | `bankcore`, `consents`, `payments`, `common`, `security` |
| `accounts` | `AccountsController`: the account endpoints | `api`, `bankcore`, `consents`, `common`, `security` |
| `bankcore` | `BankCoreClient`, its properties, the transfer's body and answer, `TransferFailures`: everything that knows bank-core's API | `common` |
| `common` | `RequestIdFilter`, `ApiExceptionHandler`, `TracingConfig`, `ErrorCode`, `ApiException`, `Iban`, `ResourceIds`, `TppRedirectUris`, `PublicUrlProperties`, `TimeConfig`, `StartupLogger` | nothing of ours |

The contract's types stay in `api` and the controllers; bank-core's JSON stays in `bankcore`; SQL stays in
`ConsentRepository` and `PaymentRepository`; Spring Security stays in `security`, and the controllers learn the
provider from `AuthenticatedProvider` and the customer from `SignedInCustomer`. `LayeringTest` fails the build when
an import goes against the table, when a package has no line in it, when an HTTP client appears outside `bankcore`
(bank-core) and `security` (Keycloak), SQL outside `consents` and `payments` or Spring Security outside
`security`.

## The contract

`src/main/resources/static/openapi/bank-open-api.yaml` is GD Next's Open Banking Ukraine 2.0 YAML (Apache 2.0,
licence next to it) cut down to what this version offers: creating, reading and deleting an account access
consent, the account list, details and balances, and initiating, reading, following and cancelling a payment. The
service serves it at `/openapi/bank-open-api.yaml`, and its header lists what the bank changed. For payments:

- the payment service is fixed to `payments` in the path (`/v2/payments/{payment-product}`): single payments only;
- the products are the bank's own list, as the specification leaves them to each bank: one,
  `domestic-credit-transfers`, in place of the SEPA defaults;
- the body is the single payment's JSON form only, the information answer the single payment's only, and a
  cancellation answers 204 only (no cancellation the customer authorises);
- `ultimateCreditor` is a plain object: the specification's three forms without a discriminator give a generated
  interface no JSON reader can build, so a request naming one would end in a 500. The bank refuses it anyway;
- each operation names the scope it needs (`accounts` or `payments`) instead of allowing calls without a token.

openapi-generator 7.27.0 turns it into Spring interfaces and models at build time. The controllers implement the
interfaces, so a path, header or field that disagrees with the contract does not compile. The interfaces carry
`@Validated`, so Spring checks the bean validation rules of the models (required fields, patterns, minimums) before
a controller runs, and the error names the field.

## Providers and their tokens

A provider is a client of Keycloak's realm `bank` with the scope `accounts`, `payments` or both (an account
information or payment initiation service, in PSD2's words), and has a client certificate from the bank's CA (a
test CA here, made by `scripts/cluster-up.sh`). One key pair serves both: the certificate for mutual TLS, its key
for signing in.

```
provider                       Traefik (:8443)                     Keycloak, realm bank            bank-open-api
   │ https://localhost:8443, TLS with client certificate
   │ ─────────────────────────────► checks it against the CA, puts it into
   │                                X-Forwarded-Tls-Client-Cert, the provider's rate limit
   │ POST /realms/bank/.../token: client credentials, client_assertion = JWT (PS256)
   │ ───────────────────────────────────────────────────────────────► checks the JWT with the
   │                                                                  provider's certificate; FAPI 2.0
   │ ◄─────────────────────── access token: typ at+jwt, aud bank-open-api, scope accounts payments, azp,
   │                           cnf.x5t#S256 = SHA-256 of the client certificate
   │ https://api.localhost:8443, TLS with the same certificate
   │ ─────────────────────────────► no certificate from the CA: no connection;
   │                                the header, the provider's rate limit
   │ GET /v2/... with the token
   │ ─────────────────────────────────────────────────────────────────────────────────────────────► security chain
```

Keycloak's client policy `fapi-2-for-providers` puts every client with the default scope `accounts` or
`payments` under the built-in profile `fapi-2-security-profile`: confidential clients, `private_key_jwt` or mutual
TLS sign-in, PS256 or ES256 signatures, tokens bound to the client certificate, PKCE for the authorization code
flow of step 4. The realm signs with a PS256 key only. The client scopes `accounts` and `payments` add the
audience `bank-open-api`, and the clients use the RFC 9068 token type `at+jwt`.

In bank-open-api, `SecurityConfig` builds the chain each request to `/v2` passes:

1. `RequestIdFilter`, ahead of the chain, so refused requests echo their `X-Request-ID` too.
2. Spring Security's bearer token filter: the token's PS256 signature against Keycloak's keys (read from
   `http://keycloak:8080/.../certs` and cached), then `ProviderTokenValidator`'s rules in order: type `at+jwt`,
   issuer `https://localhost:8443/realms/bank`, audience `bank-open-api`, expiry (30 s of clock skew), a provider
   in `azp`, and a `cnf.x5t#S256`.
3. `CertificateBindingFilter`: the certificate in Traefik's header must be readable, valid today, and the one whose
   thumbprint the token carries (RFC 8705). A stolen token is of no use without the provider's key.
4. The scope: `/v2/payments/**` needs `payments`, the rest of `/v2/**` needs `accounts`. Health, info and the
   contract are open; everything else is refused.

Controllers then ask `AuthenticatedProvider` for the provider (the token's `azp`), and `ConsentService` and
`PaymentService` treat another provider's consent or payment exactly as one that does not exist.

The API also describes itself as RFC 9728 asks: `GET /.well-known/oauth-protected-resource` on the API's host
answers, to any provider, with the resource (`https://api.localhost:8443`, the address the API is called at), the
authorization server (the realm's issuer), the scopes `accounts` and `payments`, the bearer header and
certificate-bound tokens, and every `WWW-Authenticate` challenge links there with `resource_metadata`. Spring
Security serves the document; `SecurityConfig` fills it in from `bank-open-api.api-url`.

## Customers and their approval

The Berlin Group's redirect approach: the provider sends the customer's browser to the bank, which signs the
customer in, shows the consent (or the payment, below) and sends the browser back.

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
  decided` and the rest), written in one place for every page controller (`CustomerPageErrors`, ahead of the API's
  handler), and a sign-in that fails (an old link, the back button at Keycloak) ends on the bank's page, not on
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

## Payments

One product, `domestic-credit-transfers`: a credit transfer in hryvnias from an account at this bank to an account
at any bank in Ukraine, named by IBAN. The provider initiates it, the customer approves it on the bank's page, and
the approval makes the transfer at bank-core at once.

```
provider             customer's browser                  bank-open-api                          bank-core
 │ POST /v2/payments/domestic-credit-transfers, PSU-IP-Address, TPP-Redirect-URI
 │ ◄── 201 RCVD, _links.scaRedirect = https://localhost:8443/customer/payments/{id}
 │ opens it ─────────► sign-in at Keycloak, as for a consent (prompt=login)
 │                     ◄── the page: amount, payee, payee's account, purpose, the account it is paid from
 │                     ── POST approve, CSRF token ──────► GET /api/v1/accounts/{iban} ─────────► the holder
 │                                                       the customer's? ACTC, stored
 │                                                       POST /api/v1/transfers, Idempotency-Key = id ──► transfer
 │ ◄── 302 TPP-Redirect-URI ◄── (session ended) ◄─────── ACCC, ACSP or RJCT, stored
 │ GET .../{id}/status ─────────────────────────────────► ACSP: GET /api/v1/transfers/{id} ─────► PENDING,
 │ ◄── transactionStatus, reasonCode ◄───────────────────                                         COMPLETED, REJECTED
```

### A payment's life

| From | What happens | To |
|---|---|---|
| | `POST /v2/payments/domestic-credit-transfers` | `RCVD` |
| `RCVD` | `DELETE /v2/payments/domestic-credit-transfers/{id}` | `CANC` (a second DELETE: 204 again) |
| `RCVD` | the customer rejects it on the bank's page | `RJCT`, no reason code |
| `RCVD` | the customer approves; bank-core says they hold the debtor account, in hryvnias | `ACTC` |
| `ACTC` | bank-core completes the transfer within the bank | `ACCC` |
| `ACTC` | bank-core sends it to another bank (`PENDING`): the money has left the account | `ACSP` |
| `ACTC` | bank-core refuses it, nothing moved: `INSUFFICIENT_FUNDS` / anything else | `RJCT`, `AM04` / `DS04` |
| `ACTC` | bank-core cannot be asked (a 409, a 5xx, a timeout) | stays `ACTC`; the retry job tries again |
| `ACSP` | read by the provider: bank-core says the other bank accepted / refused it | `ACSC` / `RJCT`, `DS04` |

Every payment belongs to the provider that initiated it (`provider_id`); for any other it does not exist (403
`RESOURCE_UNKNOWN`). An approved or rejected payment names the customer who decided (`customer_id`); its page shows
what it pays only to that customer, and anyone else learns no more than its status. Once the customer has decided
the provider can no longer cancel it (405 `CANCELLATION_INVALID`): an approved payment may be paid already.

What a provider may initiate, checked in `PaymentRequests` (what the bank offers) and `Payment` (the rules):

- the debtor and the creditor account by IBAN only, both Ukrainian (`UA`, 29 characters, ISO 13616 check digits),
  and different; no charges, agents or ultimate creditor (`PARAMETER_NOT_SUPPORTED`);
- the amount in UAH, more than 0 and at most 1 000 000 000.00, with no more than two decimals (bank-core's limits);
  account currencies, when named, UAH too;
- the creditor's name and the purpose (`remittanceInformationUnstructured`), each one line of at most 140
  characters, not blank: a Ukrainian credit transfer states both, and bank-core takes them as `toName` and
  `description`;
- an `endToEndId` of at most 35 characters, if any: kept and returned to the provider.

Whether the debtor account exists is not checked at initiation: a provider must not learn which accounts do.

### Making the transfer

`PaymentService.approve` checks the holder, stores `ACTC` (from `RCVD` only, so a cancellation at the same moment
wins or loses cleanly), then calls bank-core: `POST /api/v1/transfers` with the payment's order and the payment's
id as the `Idempotency-Key`. The order never changes, so any repeat of the call is answered with the transfer made
the first time (bank-core's 200 replay) instead of a second one. `TransferFailures` reads bank-core's answer:

| bank-core answers | Means | The payment |
|---|---|---|
| 201 or 200, `COMPLETED` / `PENDING` / `REJECTED` | the transfer, made now or before | `ACCC` (`ACSC` to another bank) / `ACSP` / `RJCT` `DS04` |
| 400, 404 or 422 with bank-core's code | refused for its content, nothing moved | `RJCT`, `AM04` for `INSUFFICIENT_FUNDS`, else `DS04` |
| 422 `IDEMPOTENCY_KEY_REUSED` | bank-core keeps a different transfer under the key | stays `ACTC`, logged as an error for a person |
| 409, 5xx, an answer without bank-core's code, no answer | the outcome is unknown | stays `ACTC` |

`PaymentRetryJob` runs every `bank-open-api.payments.retry-delay` (30 s) on each instance. It takes up to 20
payments still `ACTC` whose last attempt started a retry delay ago, oldest first, and makes their transfers the same
way; it stops at the first one bank-core cannot be asked for. Each attempt first claims its payment in one
statement (`attempted_at` moves to now, only if it is still older than the retry delay), so of two instances only
one makes a payment's transfer at a time. The approval's own attempt counts as the first. The delay is at least
30 s, longer than a call to bank-core may take (2 s to connect, 5 s to answer), so an attempt is over before
another may claim the payment.

A payment sent to another bank stays `ACSP` until that bank answers. bank-core learns the answer from
bank-integration over Kafka; bank-open-api asks bank-core for the transfer (`GET /api/v1/transfers/{id}`) when the
provider reads the payment or its status, and stores what changed. In the k3d cluster Kafka is off, so such a
payment stays `ACSP` there; where bank-integration runs beside bank-core, it becomes `ACSC` or `RJCT`.

## Errors

RFC 7807 problem details with the contract's `code`. `ErrorCode` holds each code's HTTP status and the text a
provider reads; an `ApiException` carries a code and either a detail for the provider (which field, which rule,
never a value the provider sent) or, for failures inside the bank, a message that goes to the log only.

| Code | Status | When |
|---|---|---|
| `FORMAT_ERROR` | 400 | a header, parameter or body field breaks the contract, a consent rule or a payment rule |
| `PARAMETER_NOT_SUPPORTED` | 400 | access beyond current accounts, an account not named by IBAN, rights the bank does not offer, payment fields it does not offer (charges, agents, ultimate creditor) |
| `CONSENT_TYPE_NOT_SUPPORTED` | 400 | a consent type other than `detailed` |
| `CONSENT_UNKNOWN` | 400 | the `Consent-ID` header names no consent of this provider |
| `TOKEN_UNKNOWN` | 401 | no access token, or one the bank cannot read: malformed, not signed by Keycloak's key |
| `TOKEN_INVALID` | 401 | a token of the bank, but not for this: another type, issuer or audience, no provider, not bound to a certificate, or bound to another one |
| `TOKEN_EXPIRED` | 401 | the token's expiry has passed |
| `CERTIFICATE_MISSING` | 401 | the request came without the provider's client certificate |
| `CERTIFICATE_INVALID` | 401 | the certificate in Traefik's header cannot be read, or is not valid yet |
| `CERTIFICATE_EXPIRED` | 401 | the certificate's end date has passed |
| `ROLE_INVALID` | 401 | the token lacks the scope the path needs: `accounts` for consents and accounts, `payments` for payments |
| `CONSENT_INVALID` | 401 | the consent is not valid, or does not include this data |
| `CONSENT_EXPIRED` | 401 | the consent's `validTo` has passed |
| `CONSENT_UNKNOWN` | 403 | the path names no consent of this provider |
| `RESOURCE_UNKNOWN` | 403 | the path names no payment of this provider |
| `RESOURCE_UNKNOWN` | 404 | the account is not part of the consent, or no API path matches |
| `PRODUCT_UNKNOWN` | 404 | a payment product other than `domestic-credit-transfers` |
| `SERVICE_INVALID` | 405 | the method is not offered for the path |
| `CANCELLATION_INVALID` | 405 | the customer has decided on the payment: it can no longer be cancelled |
| `REQUESTED_FORMATS_INVALID` | 406 | `Accept` asks for something other than JSON |
| `STATUS_INVALID` | 409 | the consent's or the payment's status changed at the same moment |
| `FORMAT_ERROR` | 415 | the body is not JSON (Spring's status, the contract's code) |
| `ACCESS_EXCEEDED` | 429 | today's reads without the customer are used up |
| `INTERNAL_ERROR` | 500 | a bug; the provider quotes its `X-Request-ID` |
| `SERVICE_UNAVAILABLE` | 503 | bank-core, or Keycloak's keys, did not answer; the provider may retry |

Refusals of the token and the certificate come with RFC 6750's `WWW-Authenticate` header: `Bearer` without an
error when there is no token, `Bearer error="invalid_token"` or `error="insufficient_scope"` otherwise, each with
RFC 9728's `resource_metadata` link. They go through Spring MVC's exception handling (`SecurityRefusals`), so
`ApiExceptionHandler` writes every error in the same form. The customer pages answer with pages, not JSON
(`CustomerPageErrors`).

Traefik answers some calls before bank-open-api sees them, in its own form rather than the contract's:

| Answer | When |
|---|---|
| no connection (a TLS alert) | the API's host without a client certificate, or with one the bank's CA did not sign |
| `429` with `Retry-After`, text body | the provider's rate limit is used up (10 a second, bursts of 20) |
| `421 Misdirected Request` | a request whose route has other TLS options than the connection's handshake: the API's host named on a connection made to the bank's address, or the bank's pages on one made to the API's host |
| `404`, text body | a path with no route, such as `/v2` on the bank's address |

## One request, end to end

```
provider ── GET https://api.localhost:8443/v2/accounts, client certificate, Authorization, X-Request-ID, Consent-ID
  └─ k3d load balancer :443 ── Traefik, entry point websecure (:8443): TLS options providers, client certificate
     required and checked against the bank's CA
       └─ IngressRoute bank-open-api-api, host api.localhost, path /v2/, middleware provider-api: the certificate
          into X-Forwarded-Tls-Client-Cert, then the provider's rate limit
            └─ Service bank-open-api:8090 (network policy: from Traefik only) ── pod: RequestIdFilter ──
               security chain ── AccountsController
                 ├─ (keys, cached) Service keycloak:8080 ── /realms/bank/protocol/openid-connect/certs
                 ├─ ConsentService ── Service postgres:5432, database openbanking
                 └─ BankCoreClient ── Service bank-core:8080 ── GET /api/v1/accounts/{iban}
                      └─ bank-core ── Service postgres:5432, database bank
```

`RequestIdFilter` puts the provider's `X-Request-ID` into the logging MDC and echoes it. `BankCoreClient` sends it
on as `X-Request-Id`, the header bank-core logs under, and the RestClient from Spring Boot's builder sends the
`traceparent`. bank-core's log lines about the request then carry the same request id and trace id.

## The provider app

`provider-app/` is a provider's site as a provider would build it, kept in this repository for the demo. It runs in
the namespace `provider`, outside the bank, and reaches the bank only the way any provider's server does: through
Traefik, on the bank's public names, with a key and certificate of its own.

```
customer's browser       provider app (namespace provider)                    the bank, through Traefik
 │ POST /consents ─────► a new state, 16 random bytes, into the session
 │                       a token: client_credentials, a PS256 assertion,
 │                       mutual TLS ────────────────────────────────────────► Keycloak: bound to the certificate
 │                       POST /v2/consents/account-access, TPP-Redirect-URI
 │                       .../consents/done?state=... ───────────────────────► bank-open-api: 201, scaRedirect
 │ ◄── 302 scaRedirect ◄─
 │ ── signs in and approves on the bank's page, as above ────────────────────► the consent is valid
 │ ◄── 302 .../consents/done?state=... ◄──────────────────────────────────────
 │ GET /consents/done ─► the state compared in constant time, then the
 │                       consent's status ─────────────────────────────────► valid
 │ ◄── 302 /accounts
 │ GET /accounts ──────► the accounts, then each one's balances,
 │                       PSU-IP-Address ───────────────────────────────────► bank-open-api ── bank-core
 │ ◄── the balances
```

A payment goes the same way: `POST /payments` from the account of the consent, the customer approves on the bank's
page, and `/payments/done` shows the status the bank reports (`ACCC` within the bank).

- **Its packages**: `bank` knows the bank: `BankTokens` (OpenID Connect discovery, the client credentials grant
  with a `ClientAssertion`, one token kept until 30 seconds before its end), `BankApi` (consents, accounts and
  balances, payments; the bank's problem details become a `BankApiException` with the contract's code),
  `BankConnection` (the RestClient with the SSL bundle `bank`: the provider app's key and certificate and the bank's
  test CA) and `BankEdgeResolver`. `web` holds the pages: `ProviderController`, the customer's `Journey` in the
  session, `BankErrors` for the bank's refusals (502, with the code) and silences (503), `SecurityConfig` (CSRF
  tokens on every form, no sign-in, as the provider app keeps no users of its own) and `TracingConfig`, the same
  span names as the bank's. The top package has `ProviderProperties` and `StartupLogger`, which logs the version
  at start (`provider-app 0.8.0 started`).
- **The bank's names, Traefik's address**: in a pod, `localhost` is the pod itself and `api.localhost` leads
  nowhere. `BankEdgeResolver` answers HttpClient's lookup of the bank's two names with the addresses of Traefik's
  Service in `kube-system`, port 443, as curl's `--connect-to` does; TLS still names the bank's host (SNI) and checks
  the certificate against it, and Traefik routes by it. Outside the cluster `BANK_CONNECT_TO` is empty and nothing is
  remapped.
- **Its key stays its own**: `cluster-up.sh` issues the provider app's certificate from the bank's test CA into the
  Secret `provider-app-tls` of `provider`, the only copy of the key. Keycloak's realm takes the certificate alone,
  from the Secret `provider-app-certificate` in `bank`, as the client `provider-app`'s `jwt.credential.certificate`:
  Keycloak checks the client assertion's signature against it, and Traefik's header gives it the same certificate to
  bind the token to.
- **The return proves nothing by itself**: anyone can open `/consents/done`. The provider app compares the return's
  `state` with the one in the customer's own session, in constant time, and asks the bank what the customer decided;
  another state changes nothing and gets a page that says so.
- **Pages**: Thymeleaf without JavaScript, every form with its CSRF token, a session cookie that is `Secure`,
  `HttpOnly` and `SameSite=Lax`; Lax lets the cookie come along on the bank's redirect back, a top-level GET.
- **On the bank's Gateway**: the provider app's HTTPRoute is the one route the Gateway takes from `provider`, on a
  host of its own, `provider.localhost`, on the Gateway's certificate. A real provider would serve its site itself.

## The logs

```
a pod of the bank ── stdout ── containerd's file under /var/log/pods (a link to it in /var/log/containers)
  └─ Filebeat bank-logs (log-collector, a DaemonSet): the files of the namespace bank, the container parser, the
     pod's Kubernetes metadata
       └─ Logstash bank-logs (logging), beats input :5044 (network policy: from Filebeat only)
            ├─ json: an ECS line's fields to the event's root; PostgreSQL's plain lines stay as they are
            ├─ mutate: the codec's copy of the line as it came (event.original) and a line's own data_stream
            │  fields dropped; the output names the data stream
            ├─ ruby, mask.rb: personal data masked in every field; an event it cannot handle is dropped
            └─ elasticsearch output, ECK's user for Logstash, TLS with ECK's CA
                 └─ Elasticsearch bank-logs :9200: data stream logs-bank-default, ILM policy bank-logs
                      └─ Kibana bank-logs :5601 (network policy: from Traefik only)
                           └─ HTTPRoute kibana on bank-gateway: https://kibana.localhost:8443
```

What `cluster/charts/logging/masking/mask.rb` masks:

| What | Where it is found | What is stored |
|---|---|---|
| IBANs, electronic or printed in groups of four | any text | the country code and the last four kept: `UA***********************6001` |
| who: usernames, user ids, emails, first, last and full names, a creditor's or debtor's name | a field whose name holds one (`user.name`, `user.id`, `creditor.name`, `payment.debtorName`); `key="value"` as in Keycloak's events and `"key":"value"` in text, dotted keys, keys after a prefix (`previous_email`, `customer.email`) and escaped quotes too; a value that is a whole JSON document, field by field, its keys as text | `masked-` and 12 hex characters of HMAC-SHA256 with the key in the Secret `log-masking`: one value, one pseudonym |
| the values PostgreSQL quotes | `Key (...)=(...)` of a duplicate or missing key, `Failing row contains (...)` | `masked` |
| passwords | `PASSWORD '...'` in SQL, `password=...` in text (`DB_PASSWORD=...` too), a field named `password` | `masked` |
| a JSON document too deep to read field by field (over 100 levels), or with a number JSON cannot write back | any field | `masked`, the whole value |

What stays: IP addresses, the bank's own ids (customer numbers, consent, payment, request and trace ids) and the
rest of each line. The script's tests sit at the end of the file; Logstash runs them each time it loads the
pipeline and refuses to start if one fails.

`cluster-up.sh` checks the whole way: it sends a canary line from a pod in `bank`, as the services write theirs,
with a made-up IBAN, a username and a database detail, and looks for it in Elasticsearch, masked as `mask.rb`
masks. Then a connection from the PostgreSQL pod to Elasticsearch must be refused.

## The traces

```
provider app, bank-open-api, bank-core ── OTLP/HTTP :4318 ──┐
Traefik: its entry point and router ───── OTLP/HTTP :4318 ──┤
Keycloak: requests, its database ───────── OTLP/gRPC :4317 ──┴── trace-collector (logging), network policy: from
                                                                  these five only
  ├─ memory_limiter: refuses spans before the collector runs out of memory; the senders try again
  ├─ redaction: only the attributes on the allow-list stay; IBANs, PostgreSQL's quoted values and emails in what
  │  stays become "masked", and the values in SQL statements "?"
  └─ otlp_http/apm, with ECK's secret token ── APM Server bank-traces :8200 (network policy: from the collector)
       └─ Elasticsearch bank-logs: traces-apm-default, logs-apm.error-default, metrics-apm.*
            └─ Kibana's APM app: the services, each trace across them, its errors, the bank's log lines of its
               trace.id
```

What the collector keeps (`cluster/charts/logging/tracing/collector.yaml`), of the resource, the spans and their
events alike:

| Kind | Kept |
|---|---|
| the service | `service.name`, `service.namespace`, `service.version`, `service.instance.id`, the SDK's and the runtime's names and versions, `host.name`, `os.type` |
| HTTP, OpenTelemetry's names | `http.request.method`, `http.response.status_code`, `http.route`, `url.path`, `url.scheme`, `server.address`, `server.port`, `network.protocol.version`, `error.type` |
| HTTP, Spring's client names | `method`, `status`, `uri`, `outcome`, `exception`, `client.name`, `http.url`, `peer.service` |
| Traefik | `entry_point`, `traefik.router.name`, `traefik.service.name` |
| a database | its system, name and operation, the table, the statement with its values replaced by `?` |
| Keycloak | `kc.realmName`, `kc.clientId` |
| an exception | `exception.type`, `exception.message`, `exception.stacktrace`, `exception.escaped` |

Everything else goes: query strings, user agents, client addresses, headers, Keycloak's session, token and user
ids, the attributes of its user events, a process's command line. Each span counts what was removed and masked.

Who sends what: the Spring Boot services through Micrometer Tracing's OpenTelemetry bridge and the OTLP exporter,
when the Helm chart sets `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` (outside the cluster nothing is exported), with the
commit Jenkins built as `service.version`; Traefik from its static configuration (`cluster/traefik-config.yaml`);
Keycloak with `KC_TRACING_ENABLED`, its database calls included. A provider's `traceparent` continues at Traefik,
so a provider's trace and the bank's line up.

`cluster-up.sh` checks the whole way: it sends a canary span to the collector, as a service sends its own, with a
made-up IBAN in its path and its exception, a session id, a query string, a user agent, a password on a command
line, and in its stack trace a key and a row as PostgreSQL quotes them and an email. APM Server must store it as a
transaction and an error, the IBAN, the quoted values and the email masked and the rest gone. Then connections from
the PostgreSQL pod to APM Server and to the collector must be refused, as to Elasticsearch.

## The cluster

| Where | What | Made by |
|---|---|---|
| `kube-system` | Traefik with the Gateway API provider, CoreDNS, local-path storage | k3s, `cluster/traefik-config.yaml` |
| `bank` | namespace with the "restricted" Pod Security Standard | `scripts/cluster-up.sh` |
| `bank` | Gateway `bank-gateway` (HTTPS, routes from `bank`, `logging` and `provider`), Traefik's TLS options (`default`, and `providers` for the API's host), default certificate and middleware (`cluster/platform/edge.yaml`), the Secrets of the test CA's certificates | `scripts/cluster-up.sh` |
| `bank` | network policies: no ingress unless allowed; Traefik to bank-open-api and Keycloak, bank-open-api to bank-core, Keycloak and PostgreSQL, Keycloak and bank-core to PostgreSQL (`cluster/platform/network-policies.yaml`) | `scripts/cluster-up.sh` |
| `bank` | PostgreSQL (`postgres`) with the databases `bank`, `openbanking` and `keycloak`, Keycloak (`keycloak`) with the realm `bank` (clients `smoke-test`, `provider-app` and `bank-approval`, test customer `artem.demo`), their Secrets (`provider-app-certificate`: the provider app's certificate without its key), account `jenkins-deployer` | `scripts/cluster-up.sh` |
| `bank` | `bank-core`: Deployment, Service; `bank-open-api`: Deployment, Service, HTTPRoute (pages), IngressRoute (the API's host) | Jenkins, with Helm |
| `provider` | namespace with the "restricted" Pod Security Standard, network policies: only Traefik reaches the provider app (`cluster/platform/provider.yaml`); the Secret `provider-app-tls`, its key and certificate and the bank's test CA | `scripts/cluster-up.sh` |
| `provider` | `provider-app`: Deployment, Service, HTTPRoute `provider.localhost` on `bank-gateway` | Jenkins, with Helm |
| `elastic-system` | the ECK operator 3.5.0 (Helm release `elastic-operator`, Elastic's chart with `cluster/eck-operator.yaml`); "restricted" | `scripts/cluster-up.sh` |
| `logging` | Elasticsearch, Kibana and Logstash `bank-logs`, APM Server `bank-traces` and the trace collector (Helm release `bank-logs`, `cluster/charts/logging`), Kibana's route, their network policies, the Secrets `log-masking` and `log-reader`; "restricted" | `scripts/cluster-up.sh` |
| `log-collector` | Filebeat `bank-logs`, a DaemonSet; "privileged", for the node's log folders | `scripts/cluster-up.sh` |
| Docker | registry `k3d-bank-registry`, on this machine `localhost:5050` | k3d |
| this machine | the test CA's key and certificate, a copy of the smoke-test provider's and of the test customer's password, in `~/.config/bank-open-api/pki` | `scripts/cluster-up.sh` |

Ports on this machine, all on 127.0.0.1: 6550 the Kubernetes API, 8443 HTTPS into Traefik (the bank's address
`localhost`, the API's host `api.localhost`, Kibana's `kibana.localhost` and the provider app's
`provider.localhost`, which curl and systemd's resolver take to 127.0.0.1), 5050 the registry. 8480, Traefik's HTTP
entry point, has no routes.

## Rules

- **The contract leads**: the code is generated from it, and what the bank does not offer is refused with the
  contract's codes rather than ignored.
- **Logs name consents and payments, not people**: their ids, counts, statuses and bank-core's error codes, never
  IBANs, names, purposes or amounts. What still reaches a line (a library's exception, Keycloak's events,
  PostgreSQL's details) is masked before it is stored.
- **Spans carry only what the collector allows**: an attribute nobody has put on its allow-list never reaches
  Elasticsearch, and an IBAN, a value PostgreSQL quotes or an email in one that does is masked.
- **Configuration** comes from environment variables (`BANK_CORE_URL`, `DB_URL`, `DB_USER`, `DB_PASSWORD`,
  `PROVIDER_TOKENS_ISSUER`, `PROVIDER_TOKENS_JWK_SET_URI`, `PUBLIC_URL`, `API_URL`, `CUSTOMER_SIGN_IN_TOKEN_URI`,
  `CUSTOMER_SIGN_IN_CLIENT_SECRET`, `LOGGING_STRUCTURED_FORMAT_CONSOLE`), which the Helm chart sets; the database
  login comes from the Secret `bank-open-api-db`, the client secret from `bank-approval-client`. The payments'
  retry delay (`bank-open-api.payments.retry-delay`, 30 s, at least 30 s) is a setting of `application.yml`.
- **No token, certificate or key in a log line**: refusals log the contract's code and a detail written for the
  provider; the provider id (a client id), the customer's number and consent ids are logged, nothing a token holds
  beyond that. A failed sign-in logs only its OAuth 2.0 error code.
- **Nothing in the cluster is changed by hand**: the platform comes from `cluster-up.sh`, the services from
  Jenkins. Running either again is safe.

## Decisions

- **Generated interfaces, a subset of the contract.** GD Next's YAML describes the whole Berlin Group API; the
  copy here keeps the operations this version offers, so the generated code has no endpoints that answer
  nothing. Each step adds its operations from the same source.
- **JdbcClient and Flyway, not JPA.** Four small tables and a handful of statements, among them an upsert with a
  condition and a claim; plain SQL shows exactly what runs. `Consent` and `Payment` are plain classes with their
  rules, and the repositories map them by hand.
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
- **Mutual TLS ends at Traefik, required on the API's host.** A TLS handshake can only ask for a certificate per
  server name, and customers' browsers have none, so the API has a host of its own (`api.localhost`) whose TLS
  options require one from the bank's CA (`RequireAndVerifyClientCert`). On the bank's address Traefik checks a
  certificate when one comes (`VerifyClientCertIfGiven`): Keycloak, which binds providers' tokens to it, is there,
  and its discovery document names that address. bank-open-api still checks the certificate on every call, for
  anything that reaches it some other way. Traefik's entry point drops `X-Forwarded-Tls-Client-Cert` (and headers
  whose names only look like it) when a client sends it, so the header can only come from Traefik's middleware.
- **The API's route is a Traefik IngressRoute.** In Traefik 3.7, the version k3s 1.36 ships, every Gateway
  listener takes the TLS options named `default`; only Traefik's own IngressRoute can name others. The pages and
  Keycloak stay on the Gateway. The Gateway API's own client certificate validation (GEP-91, which Traefik 3.8
  reads) is set per port, not per host, so moving the API back to the Gateway would also give it a port of its own.
- **The rate limit is per certificate, at the edge.** The provider's certificate is the one identity Traefik knows
  before the token is checked, and one provider's traffic must not use up another's. Traefik's token bucket (10 a
  second, bursts of 20) also covers Keycloak's token and PAR endpoints, counted apart from the API: Traefik builds
  a route's middleware for that route alone. The buckets live in the memory of the one Traefik pod; several pods
  would need Traefik's Redis store to share them. Calls without a certificate share a bucket only they can empty.
- **Traefik's refusals are not in the contract's form.** A rate-limited call gets `429` with `Retry-After` and a
  text body; a refused handshake gets no HTTP at all. Both happen before bank-open-api could write a problem
  document, and `Retry-After` is what a client needs to wait correctly.
- **Network policies leave Traefik the only way in.** Without them any pod in the cluster could call bank-open-api
  directly with a certificate header of its choosing. Every pod of the `bank` namespace refuses connections no
  policy allows; the policies name each caller by its labels and each target by its named port. They limit what
  comes in only: what goes out (DNS, the Kubernetes API, images) stays open. `cluster-up.sh` checks they are
  enforced, since k3s would accept them silently with its policy controller off.
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
- **One payment product of the bank's own, single payments only.** GD Next's list is the SEPA products of the
  Berlin Group's defaults and says each bank publishes its own. Hryvnia transfers within Ukraine are what
  bank-core makes; bulk and periodic payments would need bank-core's payment files and a schedule, later.
- **The payment service fixed in the path.** With `{payment-service}` as a variable, `/v2/{service}/{product}`
  would overlap `/v2/consents/...` and `/v2/accounts/...` (Spring picks the more specific path, but a typo in
  `accounts` would land in the payments controller), and the scope rule could not be a path prefix.
- **The holder check sits next to the transfer.** For consents the reads check the holder again each time; a
  payment moves money once, so `PaymentService.approve` asks bank-core right before making the transfer, rather
  than leaving it to the page's code. Rejecting needs no check: it moves nothing.
- **The payment's id is the transfer's Idempotency-Key, and its order never changes.** A timeout leaves the outcome
  unknown; the same call again gets bank-core's answer for the first one. The amount is stored with two decimals,
  so every attempt sends the same body, which bank-core compares before it replays.
- **Only a refusal from bank-core rejects a payment.** 400, 404 and 422 with bank-core's code are its rules saying
  no, before any money moved. Anything else could hide a transfer that went through, so the payment stays `ACTC`
  and is tried again with the same key. A reused key (bank-core keeps another transfer under it) is a bug no retry
  fixes: an error in the log, for a person.
- **A retry job, with the payment table as its queue.** Retrying when the provider reads would leave a payment
  unpaid if no provider reads it again. The job needs no lock across instances: each attempt claims its payment in
  one `UPDATE` on `attempted_at`, and the result is stored only from `ACTC`. The claim matters beyond the key: bank-core
  keeps nothing for a refused transfer, so two attempts at the same time could see a refusal and a success, and
  the payment would say `RJCT` while the money moved.
- **The payee's bank's answer comes when the provider asks.** Only a payment the provider follows needs it, and
  bank-open-api has no Kafka of its own: a read asks bank-core, which knows from bank-integration.
- **`ACCC` within the bank, `ACSC` from another bank.** ISO 20022's `ACCC` says the creditor's account is credited,
  which bank-core knows only for its own accounts; for another bank its `COMPLETED` means that bank accepted, `ACSC`.
- **No reason code when the customer rejects.** The contract's list (`AM04`, `DS04` and the signature codes) has none
  for it, and the Berlin Group prefers no code to a proprietary one: `RJCT` without a reason is the customer's.
- **Undecided payments do not expire yet.** A payment waits in `RCVD` until the customer decides or the provider
  cancels it. Expiry after some minutes would be computed on read, as a consent's is.
- **The customer's sign-in is not bound to the amount and the payee.** The page shows both and the approval is a
  form of the same session, but the sign-in itself says nothing of the payment, as PSD2's dynamic linking would
  have it. RFC 9396's `authorization_details` in the authorization request would carry them to Keycloak.
- **`endToEndId` is kept, not passed on.** bank-core's transfer API has no field for it yet, so the payee's bank
  does not see it; the provider gets it back with the payment.

- **Masked in Logstash as well, not only kept out by the services.** The services log ids, not people, but a
  library's exception can quote a database row (pgjdbc's `Detail: Failing row contains (...)`), Keycloak's events
  name users, and PostgreSQL's own log quotes keys. One filter before storage covers every source, whoever wrote
  the line.
- **Pseudonyms for who, the last four digits for accounts.** Support can still see that fifty failed sign-ins were
  one account, and which account by its last four digits, as on a statement, without seeing the person. The
  pseudonym is a keyed HMAC, so nobody can hash a list of likely usernames to find one; the key sits in a Secret
  only Logstash reads.
- **Anything shaped like an IBAN is masked, valid or not.** A rejected IBAN in a validation message is still
  someone's account number, and masking a string that only looks like one costs nothing. Lowercase ids never match.
- **Fail closed.** On an error the ruby filter passes the event on as it came; the pipeline drops such an event
  instead, and Logstash's own log names the error, not the event.
- **IP addresses stay.** They are what a security team blocks, and the logs live 7 days.
- **Filebeat reads the node's files, as root, in a namespace of its own.** Shipping from the services (an appender
  sending to Logstash) would put the logging stack on their requests' way and need bank-core changed. Reading the
  files needs hostPath and root, which "restricted" and "baseline" forbid, so `log-collector` allows them and holds
  nothing else.
- **Plain TCP from Filebeat to Logstash.** Both run on this one node, the beats input takes connections from
  Filebeat's pods only, and the masking happens in Logstash. Several nodes would call for TLS with client
  certificates, which ECK 3.5 sets up for Beats and Logstash.
- **One Elasticsearch node.** No replicas (so the cluster stays green), `node.store.allow_mmap: false` so that no
  machine needs `vm.max_map_count` raised, 2 GB of memory, 5 GB of disk.
- **7 days through `logs@custom`.** Elasticsearch's own `logs` template takes that component template when it
  exists, the way Elastic means its managed templates to be changed; its built-in ILM policy keeps logs for ever.
- **Kibana through the Gateway, Elasticsearch through a port-forward.** `cluster-up.sh` imports the saved search
  with Kibana's saved objects API, over the Gateway like a browser, and sets the lifecycle with Elasticsearch's own
  API through `kubectl port-forward`, checked against ECK's CA. The Kubernetes API carries that connection into the
  pod, past the network policies, and opens no port. 0.7.0 went through Kibana's console proxy, which Kibana 9
  refuses to outside clients as an internal API; the header Kibana's own pages send would have let it through, but
  that leans on an API Kibana does not promise to keep.
- **The chart owns what it sets.** Helm 4 applies server-side, and ECK rewrites its resources whole whenever it adds
  its annotations; the defaults Go adds on the way make the operator the owner of whole lists the chart sets, such
  as Elasticsearch's `nodeSets` or Logstash's containers. The first install goes through, every later upgrade stops
  at the conflict. `cluster-up.sh` upgrades the logging stack with `--force-conflicts`: the operator only changed
  how those lists are stored, not what they say, so Helm takes them back and nothing restarts.
- **No line stored as it came.** In ECS mode the Beats input's plain codec copies every line whole into
  `event.original`, and that copy is JSON as text, with escaped quotes the patterns of 0.7.2 did not read: the
  usernames in it went into Elasticsearch unmasked, which the canary found on its first real run. The pipeline drops
  the copy before the masking (the json filter has its fields), and `mask.rb` masks a value that is a whole JSON
  document field by field and reads escaped quotes and dotted keys in text, so a copy that came back would be
  masked too; the canary fails on a stored copy either way. A document is written back as one line, so the text
  patterns do not run over it again: the failing row's runs to the end of a line and would take the fields after
  it. Its keys are masked as text instead, as a map keyed by IBAN needs.
- **Ready means this version's Logstash.** Logstash's health lags an upgrade: right after Helm changes its pipeline
  or `mask.rb` it is still green from the pod about to be replaced, and a canary sent then would race the restart,
  read by the old pipeline or held past its wait. `cluster-up.sh` takes the stack as ready once ECK has seen the
  Logstash resource's last change (`observedGeneration`), the StatefulSet ECK made for it has seen its own, and every
  pod of it is of its latest revision; Logstash's health then says whether they are ready.
- **A canary to prove it.** The script's tests prove the masking; only a line that went the whole way proves
  Filebeat's glob, the json filter, the script mounted where the pipeline looks for it and the output's data
  stream. Its IBAN has the check digits 00, which no real IBAN has.
- **Kibana on the bank's Gateway.** A host of its own on the same certificate, and a reader with the role viewer; a
  bank would put Kibana behind its staff's sign-in, on an internal address.

- **The collector decides what a span may carry.** The services send what their libraries record, and that changes
  with every upgrade; a list of what to drop would let the next new attribute through. The redaction processor keeps
  what its allow-list names, of resources, spans and events alike, and an attribute shows in Kibana only once
  someone has looked at it and added it.
- **IBANs masked whole in spans.** The logs keep the country code and the last four characters; the redaction
  processor replaces what a pattern matches with one fixed text, so a span shows `masked`. Its IBAN pattern is
  `mask.rb`'s, so both find the same IBANs; PostgreSQL's quoted values go from `Key (` or `Failing row contains (`
  to the end of their line, as there is no keeping the column names either.
- **Exceptions keep their message and stack trace.** They are what an error in Kibana is for, and the same masks
  run over them. A username a library quotes in one has no pattern to find it; Keycloak's events, which name users,
  lose their attributes instead.
- **APM Server over plain HTTP inside the cluster.** The collector reads a CA file only when it starts, and ECK
  renews APM Server's self-signed certificate on its own schedule: TLS would break silently at the first renewal.
  The hop stays on one node, only the collector may connect, and APM Server still demands ECK's secret token, which
  the collector takes from ECK's own Secret.
- **The collector in `logging`.** A pod mounts Secrets of its own namespace only, and ECK keeps APM Server's token
  in `logging`. The senders are named in the collector's network policy by namespace and pod label
  (`traceCollector.senders`), Traefik by its own.
- **Traces 7 days, as logs.** APM's data streams come with Elasticsearch's built-in templates, which keep traces and
  errors 10 days. `traces@custom` names the ILM policy `bank-traces` and `logs@custom` already names `bank-logs` (one
  file for both: an index a day, deleted 7 days after it rolls over); ILM wins over the templates' data stream
  lifecycle because `index.lifecycle.prefer_ilm` is on by default. Data streams that exist already get the settings
  too. APM's metrics, counts and latencies per service and route, keep Elastic's retention.
- **Failure stores 7 days.** APM's data streams have a failure store, where Elasticsearch keeps documents it could
  not index, for 30 days by default; the cluster setting `data_streams.lifecycle.retention.failures_default` keeps
  every failure store 7 days, like the rest.
- **No service map.** Kibana draws one only under Elastic's Platinum license, and ECK runs the stack on the free
  Basic license. A trace's waterfall shows the same calls for one request, which is what a demo needs.
- **The provider app goes through the edge, not around it.** It could call bank-open-api's Service as bank-core is
  called, and then nothing it did would meet what a provider meets: Traefik's TLS options, the client certificate
  check, the rate limit, the routes. `BankEdgeResolver` changes where a connection goes and nothing else, which
  HttpClient 5.6 allows (`DnsResolver.resolve(host, port)`) and the JDK's client does not.
- **Jenkins deploys the provider app without reading its key.** Helm keeps a release in Secrets by default, and an
  account that may write Secrets in `provider` may read `provider-app-tls`. The provider app's release lives in
  ConfigMaps instead (`HELM_DRIVER=configmap`), and the deploy account has no right to Secrets there;
  `cluster-up.sh` checks that it has none.
- **The provider app's spans go to the bank's collector.** A real provider traces in its own systems; the demo wants
  one picture, and the provider app's spans cross the same masking as the bank's.
- **A provider's trace is continued, its baggage dropped.** Traefik continues an incoming `traceparent`, the
  sampled flag included, so a provider's trace and the bank's line up. With every trace kept the flag decides
  nothing; a bank that kept a share of its traces would let each provider choose for its own. `baggage` is off in
  bank-open-api (`management.tracing.baggage.enabled: false`): what a provider put there would go on to bank-core
  and into its spans. `BankOpenApiIT` checks bank-core gets none.
- **Keycloak samples on its own.** Its sampler (`traceidratio`) is not parent-based; at its default ratio of 1.0
  every request is traced, and its spans join the caller's trace because the trace id is the caller's.
- **No spans for Spring Security's filters.** Each step of the security chain would be a span of its own, a dozen a
  request with nothing in them; `management.observations.enable.spring.security: false` leaves the request's span
  and the calls it makes.
- **OpenTelemetry's names for the server spans.** Spring's default names a request's attributes `method`, `uri` and
  `status`, which APM Server keeps as unknown labels; `OpenTelemetryServerRequestObservationConvention`
  (`TracingConfig`) gives the names APM Server reads as HTTP, the ones Traefik and Keycloak use. The client spans
  keep Spring's names, which the collector allows too, and `http.url`, whose IBAN it masks.

## Extending it

- **Endpoint**: its operation from GD Next's YAML into `bank-open-api.yaml`, then the generated method in a
  controller; tests with `@WebMvcTest` and, for the whole path, `BankOpenApiIT`.
- **Consent rule**: in `Consent` (or `ConsentRequests` for what the bank offers), with a case in `ConsentTest`.
- **Payment rule**: in `Payment` (or `PaymentRequests` for what the bank offers), with a case in `PaymentTest`.
- **Payment product**: its name in the contract's `paymentProduct` list (`build-subset`'s product), a check in
  `PaymentsController` that leads to its rules, and a `product` column once there are two.
- **Table or column**: a new Flyway migration `V5__...`; the old ones never change.
- **Call to bank-core**: a method in `BankCoreClient` that turns every failure into `BankCoreUnavailableException`,
  or, where bank-core's answer can refuse, into what `TransferFailures` says it means.
- **Error**: a value in `ErrorCode` with its status, the contract's code and its text, then a test.
- **Provider**: a client in the realm like `smoke-test` (scope `accounts`, `payments` or both, `client-jwt`, the
  PS256 and binding attributes), with its certificate from the bank's CA; the client policy then applies FAPI 2.0
  to it.
- **Token rule**: a check in `ProviderTokenValidator` with its code, and a case in `ProviderTokenValidatorTest`.
- **Customer page**: a controller in `approval` (its refusals then go through `CustomerPageErrors`) and a template
  in `templates/customer/` that takes the head from `customer/layout`, forms with `th:action` (so they carry the
  CSRF token); tests with `oidcLogin()` in a `@WebMvcTest` like `CustomerPaymentsControllerTest` and, through a
  sign-in, in `CustomerApprovalIT` or `PaymentApprovalIT`.
- **Package**: a line in `MAY_DEPEND_ON` of `LayeringTest` and in the table above.
- **Service in the cluster**: a chart like `charts/bank-open-api`, a Jenkinsfile with the same stages, its
  database lines in `cluster-up.sh` (`ensure_secret`, `ensure_database`), and a network policy naming who may call
  it (and in PostgreSQL's policy, if it keeps data there).
- **Provider-only endpoint at Keycloak**: its path in the first rule of Keycloak's route, the one with the
  rate-limited middleware.
- **Something more to mask**: a field name in `IDENTITY_FIELDS` of `mask.rb`, or a pattern beside the others, and a
  test block at the end of the file; Logstash runs it at its next start.
- **Another namespace's logs**: Filebeat's `collectedNamespace` in the logging chart's values (or a second path in
  its input), and a test in `mask.rb` for what that namespace writes.
- **Something more in the traces**: its attribute's name in `allowed_keys` of
  `cluster/charts/logging/tracing/collector.yaml`, once it is clear what its values can hold; if they can hold
  something personal, a pattern in `blocked_values` and a value of the canary span (`canary_span` in
  `cluster-up-logging.sh`) that must not be stored.
- **Another service that traces**: its namespace and its pods' `app.kubernetes.io/name` in `traceCollector.senders`
  of the logging chart's values, and `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` in its chart, as in this one.
- **Another provider's site**: a client in the realm like `provider-app`, its certificate from a Secret as
  `ensure_provider_certificate` makes it, a namespace like `provider` with its policies, the namespace in the
  Gateway's `allowedRoutes` and its host on the Gateway's certificate.
