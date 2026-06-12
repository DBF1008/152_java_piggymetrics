# Shared reader for the PiggyMetrics publish manifest.
#
# Source this file, then call `manifest_images`. It emits one
# "<context-dir><TAB><image-repo>" line per image, with comments and blank
# lines stripped and the repo derived from the directory when not given
# explicitly. Both the publisher and the guard read the manifest through here
# so the parsing and the naming convention live in exactly one place.
#
# Overridable via the environment:
#   REPO_PREFIX    image repo prefix (default: sqshq/piggymetrics)
#   MANIFEST_FILE  path to the manifest (default: publish-manifest.txt beside this file)

REPO_PREFIX="${REPO_PREFIX:-sqshq/piggymetrics}"
MANIFEST_FILE="${MANIFEST_FILE:-$(dirname "${BASH_SOURCE[0]}")/publish-manifest.txt}"

manifest_images() {
  local dir repo _
  while read -r dir repo _; do
    [ -z "$dir" ] && continue
    case "$dir" in '#'*) continue ;; esac
    printf '%s\t%s\n' "$dir" "${repo:-$REPO_PREFIX-$dir}"
  done < "$MANIFEST_FILE"
}
