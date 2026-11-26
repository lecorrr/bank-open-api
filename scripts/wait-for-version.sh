#!/usr/bin/env bash
# wait-for-version.sh: waits until every pod of a Helm release runs the image Jenkins just deployed. Helm is done once
# the new pod is ready, but the old one answers on during its preStop pause, until Traefik leaves it out: a smoke test
# would meet both versions. Gives up after two minutes and lists the pods. Jenkins runs it after each deploy.
#
#   bash scripts/wait-for-version.sh NAMESPACE RELEASE TAG    e.g. bank bank-open-api 0a1b2c3d4e5f
set -euo pipefail

readonly SCRIPT_NAME="wait-for-version.sh"
readonly PROJECT_VERSION="0.8.0"
if (($# != 3)); then
  echo "usage: ${SCRIPT_NAME} NAMESPACE RELEASE TAG" >&2
  exit 2
fi
readonly NAMESPACE=$1 RELEASE=$2 TAG=$3
readonly WAIT_SECONDS="${WAIT_SECONDS:-120}"
echo "== ${SCRIPT_NAME} (bank-open-api ${PROJECT_VERSION}): ${RELEASE} in ${NAMESPACE} at ${TAG} =="

# The version label of every pod of the release, one a line; the charts set it to the image's tag.
versions() {
  kubectl --namespace "$NAMESPACE" get pods -l "app.kubernetes.io/instance=${RELEASE}" \
    -o jsonpath='{range .items[*]}{.metadata.labels.app\.kubernetes\.io/version}{"\n"}{end}'
}

deadline=$((SECONDS + WAIT_SECONDS))
while true; do
  # A failed call to the cluster counts as not yet: its error is in the log, and the deadline still holds.
  current="$(versions)" || current=""
  # Done when there are pods and none of another version.
  if [[ -n "$current" ]] && ! grep -qvxF "$TAG" <<< "$current"; then
    echo "ok   only pods of ${TAG} are left"
    exit 0
  fi
  if ((SECONDS >= deadline)); then
    echo "FAIL pods of another version, or none, are still there after ${WAIT_SECONDS} seconds:"
    kubectl --namespace "$NAMESPACE" get pods -l "app.kubernetes.io/instance=${RELEASE}" || true
    exit 1
  fi
  sleep 2
done
