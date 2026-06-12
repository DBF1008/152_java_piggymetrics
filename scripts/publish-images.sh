#!/usr/bin/env bash
#
# Build, tag and push every image in the publish manifest.
#
# Each image is tagged twice and both tags are pushed explicitly:
#   - :<commit>  the 7-char commit (immutable, traceable)
#   - :<tag>     "latest" on master, otherwise the branch name
#
# Tags/commit come from the CI environment (TRAVIS_COMMIT / TRAVIS_BRANCH) and
# fall back to git, so this same script runs locally. `docker login` is the
# caller's responsibility (CI does it in after_success before invoking this).
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck source=scripts/manifest.sh
source "$(dirname "$0")/manifest.sh"

[ -f "$MANIFEST_FILE" ] || { echo "publish: manifest not found: $MANIFEST_FILE" >&2; exit 1; }

COMMIT="${COMMIT:-$(git rev-parse --short=7 HEAD)}"
BRANCH="${TRAVIS_BRANCH:-}"
[ -n "$BRANCH" ] || BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ -z "${TAG:-}" ]; then
  if [ "$BRANCH" = "master" ]; then TAG="latest"; else TAG="$BRANCH"; fi
fi

echo "Publishing images   commit=$COMMIT   branch=$BRANCH   tag=$TAG"

count=0
while IFS=$'\t' read -r dir repo; do
  [ -n "$dir" ] || continue
  echo "==> $repo   (context: ./$dir)"
  docker build -t "$repo:$COMMIT" "./$dir"
  docker tag "$repo:$COMMIT" "$repo:$TAG"
  docker push "$repo:$COMMIT"
  docker push "$repo:$TAG"
  count=$((count + 1))
done < <(manifest_images)

[ "$count" -gt 0 ] || { echo "publish: manifest produced no images" >&2; exit 1; }
echo "Published $count image(s)."
