#!/usr/bin/env bash
# install-tools.sh: installs the k3d, kubectl and helm versions this project is built for into ~/.local/bin,
# after checking every download against the checksum its publisher lists. No sudo, no package manager.
#
#   bash scripts/install-tools.sh --check   show what is installed, change nothing
#   bash scripts/install-tools.sh           install the tools that are missing or of another version
#   bash scripts/install-tools.sh --force   install all three again
set -euo pipefail

readonly SCRIPT_NAME="install-tools.sh"
readonly PROJECT_VERSION="0.2.1"
readonly K3D_VERSION="v5.9.0"
readonly KUBECTL_VERSION="v1.36.6"
readonly HELM_VERSION="v4.3.1"
readonly BIN_DIR="${HOME}/.local/bin"

echo "== ${SCRIPT_NAME} (bank-open-api ${PROJECT_VERSION}): k3d ${K3D_VERSION}, kubectl ${KUBECTL_VERSION}," \
  "helm ${HELM_VERSION} into ${BIN_DIR} =="

mode="install"
force=0
for arg in "$@"; do
  case "$arg" in
    --check) mode="check" ;;
    --force) force=1 ;;
    -h | --help)
      sed -n '2,8p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option '$arg'. Use --check, --force or --help." >&2
      exit 2
      ;;
  esac
done

case "$(uname -m)" in
  x86_64) arch="amd64" ;;
  aarch64 | arm64) arch="arm64" ;;
  *)
    echo "FAIL  unsupported CPU architecture $(uname -m)" >&2
    exit 1
    ;;
esac

for tool in curl sha256sum tar; do
  if ! command -v "$tool" > /dev/null 2>&1; then
    echo "FAIL  ${tool} is missing; install it first (Ubuntu: sudo apt install ${tool})" >&2
    exit 1
  fi
done

results=()
failures=0

# The version a tool reports, or "missing"; its output differs from tool to tool.
installed_version() {
  local tool=$1 path=$2 out
  [[ -x "$path" ]] || {
    echo "missing"
    return
  }
  case "$tool" in
    k3d) out="$("$path" version 2> /dev/null | sed -n 's/^k3d version \(v[0-9.]*\).*/\1/p')" ;;
    kubectl) out="$("$path" version --client 2> /dev/null | sed -n 's/^Client Version: \(v[0-9.]*\).*/\1/p')" ;;
    helm) out="$("$path" version --short 2> /dev/null | sed -n 's/^\(v[0-9.]*\).*/\1/p')" ;;
  esac
  echo "${out:-unknown}"
}

download() {
  local url=$1 dest=$2
  if ! curl -fsSL --retry 3 -o "$dest" "$url"; then
    echo "download failed: ${url}" >&2
    return 1
  fi
}

# Compares a file with the hash the publisher lists. Only the hash is compared, so the file's name inside
# the checksum list does not matter.
verify() {
  local file=$1 expected=$2 actual
  actual="$(sha256sum "$file" | cut -d' ' -f1)"
  if [[ -z "$expected" || "$actual" != "$expected" ]]; then
    echo "checksum mismatch for $(basename "$file"): expected '${expected}', got '${actual}'" >&2
    return 1
  fi
}

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

install_k3d() {
  local base="https://github.com/k3d-io/k3d/releases/download/${K3D_VERSION}" file="k3d-linux-${arch}"
  download "${base}/${file}" "${workdir}/${file}" || return 1
  download "${base}/checksums.txt" "${workdir}/k3d-checksums.txt" || return 1
  # checksums.txt lists paths such as _dist/k3d-linux-amd64
  verify "${workdir}/${file}" "$(awk -v f="$file" '$2 == f || $2 ~ ("/" f "$") { print $1 }' \
    "${workdir}/k3d-checksums.txt")" || return 1
  install -m 0755 "${workdir}/${file}" "${BIN_DIR}/k3d"
}

install_kubectl() {
  local base="https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${arch}"
  download "${base}/kubectl" "${workdir}/kubectl" || return 1
  download "${base}/kubectl.sha256" "${workdir}/kubectl.sha256" || return 1
  verify "${workdir}/kubectl" "$(cut -d' ' -f1 < "${workdir}/kubectl.sha256")" || return 1
  install -m 0755 "${workdir}/kubectl" "${BIN_DIR}/kubectl"
}

install_helm() {
  local file="helm-${HELM_VERSION}-linux-${arch}.tar.gz"
  download "https://get.helm.sh/${file}" "${workdir}/${file}" || return 1
  download "https://get.helm.sh/${file}.sha256sum" "${workdir}/${file}.sha256sum" || return 1
  verify "${workdir}/${file}" "$(cut -d' ' -f1 < "${workdir}/${file}.sha256sum")" || return 1
  tar -xzf "${workdir}/${file}" -C "$workdir" "linux-${arch}/helm"
  install -m 0755 "${workdir}/linux-${arch}/helm" "${BIN_DIR}/helm"
}

mkdir -p "$BIN_DIR"

for tool in k3d kubectl helm; do
  case "$tool" in
    k3d) wanted="$K3D_VERSION" ;;
    kubectl) wanted="$KUBECTL_VERSION" ;;
    helm) wanted="$HELM_VERSION" ;;
  esac
  ours="${BIN_DIR}/${tool}"
  have="$(installed_version "$tool" "$ours")"

  if [[ "$mode" == "check" ]]; then
    if [[ "$have" == "$wanted" ]]; then
      results+=("OK    ${tool} ${have} in ${BIN_DIR}")
    else
      results+=("FAIL  ${tool}: ${have} in ${BIN_DIR}, ${wanted} wanted")
      failures=$((failures + 1))
    fi
  elif [[ "$have" == "$wanted" && $force -eq 0 ]]; then
    results+=("OK    ${tool} ${have} is already in ${BIN_DIR}")
  else
    echo "Installing ${tool} ${wanted} (was: ${have})"
    if "install_${tool}"; then
      results+=("OK    ${tool} $(installed_version "$tool" "$ours") installed in ${BIN_DIR}")
    else
      results+=("FAIL  ${tool} ${wanted} could not be installed; see the message above")
      failures=$((failures + 1))
    fi
  fi

  # Another copy earlier in PATH (a snap, for example) would be the one that runs.
  found="$(command -v "$tool" || true)"
  if [[ -n "$found" && "$found" != "$ours" ]]; then
    results+=("WARN  '${tool}' runs ${found} ($(installed_version "$tool" "$found")), not the one in ${BIN_DIR}")
  fi
done

case ":${PATH}:" in
  *":${BIN_DIR}:"*) ;;
  *) results+=("WARN  ${BIN_DIR} is not in PATH: open a new terminal, or run: export PATH=\"${BIN_DIR}:\$PATH\"") ;;
esac

echo
echo "== summary: ${SCRIPT_NAME} (${mode}) =="
printf '%s\n' "${results[@]}"
if ((failures > 0)); then
  echo "== ${failures} problem(s)"
  exit 1
fi
echo "== all three tools are ready"
