# The k3d cluster

bank-core and bank-open-api run in a one-node Kubernetes cluster in Docker: k3d with k3s 1.36, Traefik in front
with HTTPS and mutual TLS, Keycloak for the providers' sign-in. The stack needs about 4 GB of memory now; the ELK
step adds about 3 GB more. Run the commands from `~/projects/bank-open-api` unless a step says otherwise.

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
  and the certificates it signs: Traefik's server certificate for `localhost` and `k3d-bank-serverlb`, and the
  client certificate of the test provider `smoke-test` (Secrets `bank-gateway-tls`, `smoke-test-provider`,
  `provider-ca`, and a copy of the provider's key and certificate in the same folder);
- the HTTPS Gateway with Traefik's TLS options (`cluster/platform/edge.yaml`);
- PostgreSQL with a database for each service and for Keycloak;
- Keycloak with the realm `bank` (its first start builds it and creates its tables: two or three minutes);
- the account Jenkins deploys with, and its kubeconfig in `~/.config/bank-open-api/jenkins-kubeconfig.yaml`.

It ends by fetching Keycloak's discovery document through Traefik, which proves TLS, the route and the realm.
Every run checks the lot again and fixes only what is missing or about to expire, so it is also how you start the
cluster after `k3d cluster stop`. `--check` reports without changing anything.

A cluster made by an earlier version gets what is new the same way: run it once before Jenkins deploys the new
version. From 0.2.x that is Traefik's new settings (Traefik restarts), the certificates, the Gateway's HTTPS
listener in place of the HTTP one, and Keycloak with its database.

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
`file:///repos/bank-open-api`, branch `*/main`, script path `Jenkinsfile`). bank-open-api's last stage reads the
test provider's key and certificate from the cluster's Secrets and runs `scripts/smoke-test.sh` through Traefik.

## 4. Check it

```
kubectl -n bank get pods,svc,gateway,httproute
```

The smoke test does what Jenkins' last stage does. It signs in at Keycloak as the test provider with a JWT
signed by its key, over mutual TLS, and checks the token is bound to its certificate; then it asks for a consent,
reads its status, sees account reads refused while the consent waits for the customer, checks that a call
without the certificate or without a token is refused, and deletes the consent:

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
CONSENT_ID=$(curl -sS --cacert $PKI/ca.crt --cert $PKI/smoke-test.crt --key $PKI/smoke-test.key -H "Authorization: Bearer $TOKEN" -X POST https://localhost:8443/v2/consents/account-access -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)" -H 'PSU-IP-Address: 192.0.2.10' -H 'Content-Type: application/json' -d '{"access": {"payments": [{"account": {"iban": "UA843999990000000000000000001"}, "rights": ["accountDetails", "balances"]}]}, "consentType": "detailed", "recurringIndicator": true, "validTo": "9999-12-31", "frequencyPerDay": 4}' | sed -n 's/.*"consentId":"\([^"]*\)".*/\1/p')
```

To read account data before step 4 brings the customer's approval page, approve the consent in the database by
hand:

```
kubectl -n bank exec postgres-0 -- psql -U postgres -d openbanking -c "UPDATE consent SET status = 'valid' WHERE id = '$CONSENT_ID'"
```

```
curl -sS -w '\n' --cacert $PKI/ca.crt --cert $PKI/smoke-test.crt --key $PKI/smoke-test.key -H "Authorization: Bearer $TOKEN" -H 'X-Request-ID: 7f3e8a52-5d1b-4c6e-9a2f-0b8c4d6e1f3a' -H "Consent-ID: $CONSENT_ID" 'https://localhost:8443/v2/accounts?withBalance=true'
```

The answer lists the account under its `resourceId`, with its balance. The same call without `--cert` and
`--key` answers `401` with `CERTIFICATE_MISSING`, and without the token `TOKEN_UNKNOWN`. Every read of account
data leaves a log line under the consent id and the provider's request id, next to the trace id that also went on
to bank-core:

```
kubectl -n bank logs deploy/bank-open-api | grep 7f3e8a52-5d1b-4c6e-9a2f-0b8c4d6e1f3a
```

### Keycloak's admin console

The console is not routed through Traefik. Forward a port to it:

```
kubectl -n bank port-forward svc/keycloak 8081:8080
```

Then open `http://localhost:8081/admin` and sign in as `admin` with this password:

```
kubectl -n bank get secret keycloak-admin -o jsonpath='{.data.password}' | base64 -d; echo
```

The realm `bank` holds the client `smoke-test`, the client scope `accounts` and, under Realm settings, Client
policies, the policy `fapi-2-for-providers`.

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
  `alert unknown ca` or `bad certificate` (35, 56): the client certificate is not from the test CA.
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
- bank-open-api's pod shows `CreateContainerConfigError` with `secret "bank-open-api-db" not found`: the cluster
  is older than 0.2.0. Run `bash scripts/cluster-up.sh`, then build the job `bank-open-api` again.
