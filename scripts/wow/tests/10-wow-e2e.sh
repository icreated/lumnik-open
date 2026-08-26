#!/usr/bin/env bash
# Suite 2 — END-TO-END: declarative ingest → discover → acts, driven by lm.
#   lm apply -f <manifest>  ·  lm connector run <name>  ·  lm source rediscover/schema
#   (acts: curl mode=analytic — no lm command for analytic ask yet)
# Requires: platform on :8080 with WOW_SRC_PASSWORD in ITS environment (for password_env),
# piscine on :5432, hub on :5433, Ollama up.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

hdr "apply manifests (declarative connectors)"
lm apply -f "$WOW_DIR/piscine-jdbc.yaml"   && ok "applied wow-atelier-c_order" || bad "apply c_order"
lm apply -f "$WOW_DIR/piscine-atelier.yaml" && ok "applied wow-atelier-rest"    || bad "apply rest"

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
