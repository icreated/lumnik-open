#!/usr/bin/env bash
# Suite 2 — END-TO-END: declarative ingest → discover → acts, driven by lm.
#   lm apply -f <manifest>  ·  lm connector run <name>  ·  lm source rediscover/schema
#   (acts: curl mode=analytic — no lm command for analytic ask yet)
# Requires: platform on :8080 with WOW_SRC_PASSWORD in ITS environment (for password_env),
# the Adempiere source on :5432, hub on :5433, Ollama up.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# apply_template <file> — render ${WOW_SRC_URL} from .env, then apply.
# The source URL is site-specific, so it lives in the gitignored .env and never in a
# tracked manifest — the same place 00-smoke.sh already reads it from. Unset,
# lib.sh's `set -u` stops us here rather than applying a connector pointed at nothing.
#
# Bash substitution, not sed: in a sed REPLACEMENT `&` stands for the whole match, and a
# JDBC URL carries one the moment it has two query parameters. Measured, not feared —
# `?ssl=true&sslmode=require` rendered as `?ssl=true${WOW_SRC_URL}sslmode=require`, a
# manifest that applies cleanly and points at the wrong database. `\` and the `|`
# delimiter are the same trap; on this side nothing is special.
apply_template() {
  local body rendered rc
  body="$(<"$1")" || return 1
  rendered="$(mktemp "${TMPDIR:-/tmp}/wow-manifest.XXXXXX")" || return 1
  printf '%s\n' "${body//\${WOW_SRC_URL\}/$WOW_SRC_URL}" > "$rendered" || { rm -f "$rendered"; return 1; }
  # A placeholder nobody wired renders as itself and applies without complaint — the same
  # silent-wrong-URL failure by another route. Refuse instead. Values only: a comment
  # may legitimately mention a placeholder, and the first version of this check duly
  # refused the manifest whose own header explained the mechanism.
  if grep -q '^[^#]*\${' "$rendered"; then
    echo "  unrendered placeholder left in $(basename "$1") — is it a var .env does not set?" >&2
    rm -f "$rendered"; return 1
  fi
  lm apply -f "$rendered"; rc=$?
  rm -f "$rendered"
  return $rc
}

hdr "apply manifests (declarative connectors)"
apply_template "$WOW_DIR/acme-jdbc.yaml"    && ok "applied wow-atelier-c_order" || bad "apply c_order"
apply_template "$WOW_DIR/acme-atelier.yaml" && ok "applied wow-atelier-rest"    || bad "apply rest"

hdr "run connectors (async; lm connector run resolves name→id)"
lm connector run wow-atelier-c_order && ok "triggered c_order" || bad "run c_order"
lm connector run wow-atelier-rest    && ok "triggered rest"    || bad "run rest"

hdr "wait for ingest (poll hub t_* counts; readonly + tenant GUC)"
expect_order=291157
for i in $(seq 1 120); do
  n="$(ro_sql "SELECT count(*) FROM connector.t_c_order" 2>/dev/null)"
  inv="$(ro_sql "SELECT CASE WHEN to_regclass('connector.t_c_invoice') IS NULL THEN 0 ELSE (SELECT count(*) FROM connector.t_c_invoice) END" 2>/dev/null)"
  echo "  poll $i: t_c_order=${n:-?} t_c_invoice=${inv:-?}"
  [ "${n:-0}" = "$expect_order" ] && [ "${inv:-0}" -ge 100000 ] 2>/dev/null && break
  sleep 5
done
[ "${n:-0}" = "$expect_order" ] && ok "t_c_order ingested = $expect_order" || bad "t_c_order=${n:-?} (expected $expect_order)"

hdr "discover SchemaCard"
lm source rediscover --scope "${WOW_SCOPE}" && ok "rediscover" || bad "rediscover"
SCH="$(lm source schema --scope "${WOW_SCOPE}" 2>&1)"; echo "$SCH"
echo "$SCH" | grep -qiE "t_c_order" && ok "SchemaCard has hub tables" || bad "SchemaCard empty"

hdr "acts (analytic NL→SQL via REST — no lm command yet)"
"$(dirname "${BASH_SOURCE[0]}")/20-acts.sh" || bad "acts suite reported failures"

summary
