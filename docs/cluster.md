# The k3d cluster

bank-core and bank-open-api run in a one-node Kubernetes cluster in Docker: k3d with k3s 1.36. The stack needs
about 3 GB of memory now; the ELK step adds about 3 GB more. Run the commands from `~/projects/bank-open-api`
unless a step says otherwise.

## 1. The tools

k3d 5.9.0, kubectl 1.36.6 and Helm 4.3.1, installed into `~/.local/bin` after a checksum check. It changes
nothing outside that folder and needs no sudo:

```
bash scripts/install-tools.sh
```

`--check` only shows what is installed. Open a new terminal afterwards if it says `~/.local/bin` is not in
`PATH`.

## 2. The cluster and its platform

```
bash scripts/cluster-up.sh
```

The first run downloads the k3s images and takes a few minutes. It creates the cluster `bank` from
`cluster/k3d.yaml` and switches kubectl to it, then waits for Traefik, and makes the `bank` namespace, its
Gateway, PostgreSQL with a database for each service, and the account Jenkins deploys with. It writes that account's
kubeconfig to `~/.config/bank-open-api/jenkins-kubeconfig.yaml`. Every run checks the lot again and fixes only
what is missing, so it is also how you start the cluster after `k3d cluster stop`. `--check` reports without
changing anything. A cluster made by an earlier version gets what is new (here bank-open-api's database and its
Secret) the same way: run it once before Jenkins deploys the new version.

It uses these ports on 127.0.0.1 and stops if one is taken: 6550 (Kubernetes API), 8480 (HTTP), 8443 (HTTPS)
and 5050 (image registry).

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
`file:///repos/bank-open-api`, branch `*/main`, script path `Jenkinsfile`). bank-open-api's last stage takes a
consent through the API via Traefik (`scripts/smoke-test.sh`).

## 4. Check it

```
kubectl -n bank get pods,svc,gateway,httproute
```

The smoke test does what Jenkins' last stage does: it asks for a consent, reads its status, sees account reads
refused while the consent waits for the customer, and deletes it:

```
bash scripts/smoke-test.sh
```

To read account data before step 4 brings the customer's approval page, approve a consent in the database by
hand. Ask for one to bank-core's first demo account and keep its id:

```
CONSENT_ID=$(curl -sS -X POST localhost:8480/v2/consents/account-access -H "X-Request-ID: $(cat /proc/sys/kernel/random/uuid)" -H 'PSU-IP-Address: 192.0.2.10' -H 'Content-Type: application/json' -d '{"access": {"payments": [{"account": {"iban": "UA843999990000000000000000001"}, "rights": ["accountDetails", "balances"]}]}, "consentType": "detailed", "recurringIndicator": true, "validTo": "9999-12-31", "frequencyPerDay": 4}' | sed -n 's/.*"consentId":"\([^"]*\)".*/\1/p')
```

```
kubectl -n bank exec postgres-0 -- psql -U postgres -d openbanking -c "UPDATE consent SET status = 'valid' WHERE id = '$CONSENT_ID'"
```

```
curl -sS -w '\n' -H 'X-Request-ID: 7f3e8a52-5d1b-4c6e-9a2f-0b8c4d6e1f3a' -H "Consent-ID: $CONSENT_ID" 'localhost:8480/v2/accounts?withBalance=true'
```

The answer lists the account under its `resourceId`, with its balance. Every read of account data leaves a log
line under the consent id and the provider's request id, next to the trace id that also went on to bank-core:

```
kubectl -n bank logs deploy/bank-open-api | grep 7f3e8a52-5d1b-4c6e-9a2f-0b8c4d6e1f3a
```

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

## When something goes wrong

- `these ports it needs are taken`: another program or container has one of the ports. `ss -ltnp` shows which;
  change the port in `cluster/k3d.yaml` (and in the curl commands) or stop the other one.
- A pod stays in `ImagePullBackOff`: the image is not in the registry. `curl -sS localhost:5050/v2/_catalog`
  lists what is there; push it again.
- `violates PodSecurity "restricted"` in `kubectl -n bank get events`: a pod asks for more than the namespace
  allows (root, privilege escalation, capabilities). The charts here meet the standard; compare with them.
- The Gateway is not programmed: `kubectl -n bank describe gateway bank-gateway` shows why;
  `kubectl -n kube-system get helmchartconfig traefik` must exist (`cluster-up.sh` applies it).
- bank-core or bank-open-api restarts: `kubectl -n bank logs deploy/bank-core --previous` (or `bank-open-api`)
  shows the last start. A wrong database password means the Secret (`bank-core-db`, `bank-open-api-db`) and the
  database disagree; `cluster-up.sh` sets the password again.
- bank-open-api's pod shows `CreateContainerConfigError` with `secret "bank-open-api-db" not found`: the cluster
  is older than 0.2.0. Run `bash scripts/cluster-up.sh`, then build the job `bank-open-api` again.
