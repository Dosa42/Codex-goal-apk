#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_WORKSPACE:?Run in the root-coordinated GitHub workflow}"
: "${GITHUB_SHA:?Delivery commit is required}"
: "${GITHUB_ENV:?GitHub environment output is required}"
runtime_dir="${GITHUB_WORKSPACE}/native-executor-source"
test ! -e "${runtime_dir}"
source_commit=c382cb99717bbaedd0e79c61835ddfc966423424
git init "${runtime_dir}"
git -C "${runtime_dir}" fetch --depth=1 https://github.com/DioNanos/codex-termux.git "${source_commit}"
git -C "${runtime_dir}" checkout --detach FETCH_HEAD
test "$(git -C "${runtime_dir}" rev-parse HEAD)" = "${source_commit}"

python3 - <<'PY'
import hashlib, json, os
from pathlib import Path
root = Path(os.environ["GITHUB_WORKSPACE"]) / "native-executor"
freeze = json.loads((root / "source-freeze.json").read_text())
assert hashlib.sha256((root / "retention.patch").read_bytes()).hexdigest() == freeze["patchSha256"]
PY
git -C "${runtime_dir}" apply --check "${GITHUB_WORKSPACE}/native-executor/retention.patch"
git -C "${runtime_dir}" apply "${GITHUB_WORKSPACE}/native-executor/retention.patch"
git -C "${runtime_dir}" add -u
source_tree="$(git -C "${runtime_dir}" write-tree)"
expected_tree="$(python3 -c 'import json; print(json.load(open("native-executor/source-freeze.json"))["expectedSourceTree"])')"
test "${source_tree}" = "${expected_tree}"

# Stamp the actual composed source commit, deterministically in both jobs.
export GIT_AUTHOR_NAME="Personal executor build"
export GIT_AUTHOR_EMAIL="build@localhost"
export GIT_COMMITTER_NAME="${GIT_AUTHOR_NAME}"
export GIT_COMMITTER_EMAIL="${GIT_AUTHOR_EMAIL}"
export GIT_AUTHOR_DATE="$(git -C "${runtime_dir}" show -s --format=%aI HEAD)"
export GIT_COMMITTER_DATE="$(git -C "${runtime_dir}" show -s --format=%cI HEAD)"
build_commit="$(printf 'Personal executor retention\nDelivery: %s\n' "${GITHUB_SHA}" | git -C "${runtime_dir}" commit-tree "${source_tree}" -p "${source_commit}")"
git -C "${runtime_dir}" checkout --detach "${build_commit}"
test -z "$(git -C "${runtime_dir}" status --porcelain)"
echo "STABLE_GIT_COMMIT=${build_commit}" >> "${GITHUB_ENV}"
echo "EXECUTOR_SOURCE_TREE=${source_tree}" >> "${GITHUB_ENV}"
