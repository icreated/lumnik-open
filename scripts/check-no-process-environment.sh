#!/usr/bin/env bash
# #2000: production code never reads the hub's process environment or its JVM properties. A manifest's `*_env` key
# resolves in the tenant's lm secret registry only: the environment holds the hub's own secrets (the registry's master
# key, the admin database password), and a manifest naming one sent it to a host of the integrator's choice. The hub's
# configuration reaches the code through MicroProfile Config, never through a direct read. A comment is not a call.
# Covered: System.getenv/getProperty/getProperties, a static import of any of them, Boolean.getBoolean, Integer.getInteger,
# Long.getLong (they read JVM properties), their method references (System::getenv…) and ProcessBuilder's environment(), called or referenced. Java production modules only:
# lumnik-testkit is test fixtures, and a Kotlin script is code, not a manifest — the shipped reference hooks are the
# operator's to deploy; a script a tenant can store (hook overrides) is #2011's, not a scan's.
#   scripts/check-no-process-environment.sh             # the repository's src/main
#   scripts/check-no-process-environment.sh --selftest  # proves the scan catches each read and spares a comment
set -euo pipefail

scan() {   # scan DIR...: prints each offending line, file:line
  grep -rnE --include='*.java' \
      -e 'System\.(getenv|getProperty|getProperties)[[:space:]]*\(' \
      -e 'import[[:space:]]+static[[:space:]]+java\.lang\.System\.(getenv|getProperty|getProperties|\*)' \
      -e 'import[[:space:]]+static[[:space:]]+java\.lang\.(Boolean\.getBoolean|Integer\.getInteger|Long\.getLong|(Boolean|Integer|Long)\.\*)' \
      -e '(Boolean\.getBoolean|Integer\.getInteger|Long\.getLong)[[:space:]]*\(' \
      -e 'System[[:space:]]*::[[:space:]]*(getenv|getProperty|getProperties)' \
      -e '(Boolean[[:space:]]*::[[:space:]]*getBoolean|Integer[[:space:]]*::[[:space:]]*getInteger|Long[[:space:]]*::[[:space:]]*getLong)' \
      -e '\.environment[[:space:]]*\([[:space:]]*\)' \
      -e '::[[:space:]]*environment\b' "$@" \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(/?\*|//)' || true
}

if [[ ${1:-} == --selftest ]]; then
  dir=$(mktemp -d)
  trap 'rm -rf "${dir:?}"' EXIT
  printf 'import static java.lang.System.getenv;\nclass A {\n  String a(String n) { return System.getenv(n); }\n  String b(String n) { return System.getProperty(n); }\n  Object c() { return System.getenv(); }\n  /** quotes System.getenv(n) */\n  // System.getProperty(n)\n  Object d() { return System.getProperties (); }\n  long e() { return Long.getLong("x", 1L); }\n  Object f(ProcessBuilder p) { return p.environment(); }\n  String g(java.util.Properties p) { return p.getProperty("k"); }\n  Object h(java.util.Optional<String> n) { return n.map(System::getenv); }\n  Object i(java.util.Optional<String> n) { return n.map(Long :: getLong); }\n  Object j(java.util.Optional<ProcessBuilder> b) { return b.map(ProcessBuilder::environment); }\n}\nimport static java.lang.Long.getLong;\nimport static java.lang.Boolean.*;\nimport static java.lang.Integer.getInteger;\nclass B {\n  boolean a() { return Boolean.getBoolean("x"); }\n  Object b(java.util.Optional<String> n) { return n.map(Integer::getInteger); }\n  Object c(java.util.Optional<String> n) { return n.map(Boolean::getBoolean); }\n  Integer d() { return Integer.getInteger("x"); }\n}\n' > "$dir/A.java"
  found=$(scan "$dir")
  lines=$(cut -d: -f2 <<<"$found" | tr '\n' ' ')
  [[ $lines == "1 3 4 5 8 9 10 12 13 14 16 17 18 20 21 22 23 " ]] || { echo "selftest: expected lines 1 3 4 5 8 9 10 12 13 14 16 17 18 20 21 22 23, got: $found" >&2; exit 1; }
  echo "selftest: the scan catches each read and spares comments"
  exit 0
fi

cd "$(dirname "$0")/.."
files=()
while IFS= read -r f; do [[ -f $f ]] && files+=("$f"); done < <(git ls-files '*/src/main/java/*.java' ':!lumnik-testkit/**')
found=$(scan "${files[@]}")
if [[ -n $found ]]; then
  echo "Production code reads the process environment or a JVM property — use MicroProfile Config, or lm secret for a manifest (#2000):" >&2
  echo "$found" >&2
  exit 1
fi
echo "no process environment or JVM property read in */src/main/java (${#files[@]} files)"
