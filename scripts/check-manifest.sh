#!/usr/bin/env bash
#
# Guard the publish manifest. Fails (non-zero exit) when the manifest drifts
# from reality, so a missing/stale entry is caught before anything is built or
# pushed. Runs in CI (before_script) and is safe to run locally — read-only.
#
# It checks three things agree:
#   1. every manifest entry has a build context with a Dockerfile
#   2. every directory that has a Dockerfile is listed in the manifest
#      (this is what catches "added a service, forgot the manifest")
#   3. every sqshq/piggymetrics-* image used in docker-compose.yml is built here
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck source=scripts/manifest.sh
source "$(dirname "$0")/manifest.sh"

errors=0
fail() { echo "  ✗ $*"; errors=$((errors + 1)); }

echo "Checking publish manifest: $MANIFEST_FILE"
[ -f "$MANIFEST_FILE" ] || { echo "  ✗ manifest not found"; exit 1; }

manifest_dirs="$(manifest_images | cut -f1)"
manifest_repos="$(manifest_images | cut -f2)"

# 1. every manifest entry has a build context with a Dockerfile
while IFS= read -r dir; do
  [ -z "$dir" ] && continue
  [ -f "$dir/Dockerfile" ] || fail "manifest lists '$dir' but '$dir/Dockerfile' is missing"
done <<< "$manifest_dirs"

# 2. every directory with a Dockerfile is listed in the manifest
while IFS= read -r df; do
  d="$(dirname "$df")"; d="${d#./}"
  printf '%s\n' "$manifest_dirs" | grep -qxF "$d" \
    || fail "directory '$d' has a Dockerfile but is not in the manifest"
done < <(find . -mindepth 2 -maxdepth 2 -name Dockerfile -not -path './.git/*')

# 3. every piggymetrics image referenced by docker-compose.yml is built here
compose="docker-compose.yml"
if [ -f "$compose" ]; then
  while IFS= read -r img; do
    [ -z "$img" ] && continue
    printf '%s\n' "$manifest_repos" | grep -qxF "$img" \
      || fail "$compose references image '$img' that the manifest does not build"
  done < <(grep -hoE "image:[[:space:]]*${REPO_PREFIX}[^[:space:]]+" "$compose" \
             | sed -E 's/^image:[[:space:]]*//' | sort -u)
fi

count="$(printf '%s\n' "$manifest_dirs" | grep -c .)"
if [ "$errors" -gt 0 ]; then
  echo "Manifest check FAILED with $errors problem(s)."
  exit 1
fi
echo "Manifest OK — $count images; contexts and compose references all consistent."
