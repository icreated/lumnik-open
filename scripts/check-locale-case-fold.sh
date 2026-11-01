#!/usr/bin/env bash
# #1622: String.toLowerCase() / toUpperCase() without a Locale fold in the JVM's default one — under tr,
# "I" lowers to the dotless "ı" and "i" uppers to "İ": an ingested e-mail changed, `after_process: archive`
# silently never archived. Production code says Locale.ROOT. A comment quoting the old call is not a call.
#   scripts/check-locale-case-fold.sh             # the repository's src/main
#   scripts/check-locale-case-fold.sh --selftest  # proves the scan catches a call and spares a comment
set -euo pipefail

scan() {   # scan DIR...: prints each offending line, file:line
  grep -rnE --include='*.java' '\.to(Lower|Upper)Case\(\)' "$@" \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(/?\*|//)' || true
}

if [[ ${1:-} == --selftest ]]; then
  dir=$(mktemp -d)
  trap 'rm -rf "${dir:?}"' EXIT
  printf 'class A {\n  String a(String s) { return s.toUpperCase(); }\n  /** quotes s.toLowerCase() */\n  // s.toLowerCase()\n  String b(String s) { return s.toLowerCase(java.util.Locale.ROOT); }\n}\n' > "$dir/A.java"
  found=$(scan "$dir")
  [[ $(wc -l <<<"$found") -eq 1 && $found == *":2:"* ]] || { echo "selftest: expected exactly line 2, got: $found" >&2; exit 1; }
  echo "selftest: the scan catches a call and spares comments and Locale.ROOT"
  exit 0
fi

cd "$(dirname "$0")/.."
dirs=()
while IFS= read -r d; do dirs+=("$d"); done < <(find . -path ./node_modules -prune -o -type d -path '*/src/main/java' -print)
found=$(scan "${dirs[@]}")
if [[ -n $found ]]; then
  echo "Case folded in the JVM's default locale — write Locale.ROOT (#1622):" >&2
  echo "$found" >&2
  exit 1
fi
echo "no case fold in the default locale under */src/main/java"
