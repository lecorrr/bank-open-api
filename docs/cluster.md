# The k3d cluster

bank-core and bank-open-api run in a one-node Kubernetes cluster in Docker: k3d with k3s 1.36, Traefik in front
with HTTPS, mutual TLS and providers' rate limits, Keycloak for the providers' and the customers' sign-in, network
policies between them. Customers' browsers use the bank's address, `https://localhost:8443`; providers call the API
on its own host, `https://api.localhost:8443`, where Traefik requires their client certificate. curl and systemd's
resolver take any name under `localhost` to 127.0.0.1, so nothing goes into `/etc/hosts`. The stack needs about 4 GB of
memory now; the ELK step adds about 3 GB more. Run the commands from `~/projects/bank-open-api` unless a step says
otherwise.

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

The first run downloads the k3s and Keycloak images and takes several minutes. It creates the cluster `bank` from
`cluster/k3d.yaml` and switches kubectl to it, sets up Traefik, and makes:

- the `bank` namespace;
- the bank's test certificate authority, in `~/.config/bank-open-api/pki` (`ca.key` never leaves this machine),
  and the certificates it signs: Traefik's server certificate for `localhost`, `api.localhost` and
  `k3d-bank-serverlb`, and the client certificate of the test provider `smoke-test` (Secrets `bank-gateway-tls`,
  `smoke-test-provider`, `provider-ca`, and a copy of the provider's key and certificate in the same folder);
- the HTTPS Gateway with Traefik's TLS options, a client certificate required on the API's host, and the
  middleware that passes it on and holds each provider to its rate limit (`cluster/platform/edge.yaml`);
- PostgreSQL with a database for each service and for Keycloak;
- Keycloak with the realm `bank` (its first start builds it and creates its tables: two or three minutes): the
  test provider `smoke-test` with the scopes `accounts` and `payments`, bank-open-api's client for the customer
  pages `bank-approval` (its secret in the
  Secret `bank-approval-client`), and the test customer `artem.demo`, customer number C1001 as in bank-core's demo
  data (the password in the Secret `test-customer`, and a copy in `~/.config/bank-open-api/pki` for the smoke
  test);
- the network policies of the `bank` namespace (`cluster/platform/network-policies.yaml`): a pod there takes
  connections only from the callers its policy names, Traefik for bank-open-api and Keycloak;
- the account Jenkins deploys with, and its kubeconfig in `~/.config/bank-open-api/jenkins-kubeconfig.yaml`.

It fetches Keycloak's discovery document through Traefik, which proves TLS, the route and the realm, and then
checks the network policies are enforced: a connection from the PostgreSQL pod to Keycloak must be refused, while
Keycloak still answers through Traefik.
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
network policies, and the right for Jenkins' account to deploy the API's IngressRoute. It also puts this version's
Traefik settings into the node, where k3s reads them again at every start.

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
meet either. bank-open-api's last stage reads the test provider's key and certificate and the test customer's
password from the cluster's Secrets and runs `scripts/smoke-test.sh` through Traefik.

Run `cluster-up.sh` of a new version before Jenkins deploys it: bank-open-api 0.6.0 and later need the TLS options
and middleware for the API's host and the right to deploy its IngressRoute, and bank-core 0.8.0 or later (the
accounts' customer numbers), so build `bank-core` first.

## 4. Check it

```
kubectl -n bank get pods,svc,gateway,httproute,ingressroute,networkpolicy
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

## Stop, start, delete

The cluster keeps its data while stopped:

```
k3d cluster stop bank
```

```
bash scripts/cluster-up.sh
```

Delete it with its registry and volumes; `cluster-up.sh` builds a new one, and Jenkins then needs to be
recreated as in bank-core's `docs/jenkins.md`:

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
  made again while Keycloak kept its database). `kubectl -n bank logs deploy/keycloak | grep -i smoke-test` names
  the reason. The way out is a new cluster.
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
