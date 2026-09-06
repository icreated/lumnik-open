#!/usr/bin/env bash
# Suite 1 — SMOKE: read-only lm health checks (no writes). Requires the platform on :8080.
#   lm connector list · lm migration status · lm source jdbc test · lm source schema
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

hdr "platform reachable?"
if curl -s -m 5 "${WOW_SERVER}/q/openapi" >/dev/null 2>&1 || lm connector list >/dev/null 2>&1; then
  ok "platform answers on ${WOW_SERVER}"
else
  bad "platform not reachable on ${WOW_SERVER} — start it: mvn quarkus:dev -pl lumnik-hub -am"
  summary; exit 1
fi

hdr "lm connector list"
if lm connector list; then ok "connector list"; else bad "connector list failed"; fi

hdr "lm migration status"
if lm migration status >/dev/null 2>&1; then ok "migration status"; else bad "migration status failed"; fi

hdr "lm source jdbc test (source connectivity, password via stdin)"
if printf '%s\n' "${WOW_SRC_PASSWORD:-}" | lm source jdbc test --url "${WOW_SRC_URL}" --user "${WOW_SRC_USER}"; then
  ok "jdbc source reachable: ${WOW_SRC_URL}"
else
  bad "jdbc source test failed (is the source up at ${WOW_SRC_URL}?)"
fi

hdr "lm source schema --scope ${WOW_SCOPE}"
SCH="$(lm source schema --scope "${WOW_SCOPE}" 2>&1)"; echo "$SCH"
if echo "$SCH" | grep -qiE "t_c_order|tables"; then ok "SchemaCard present for scope ${WOW_SCOPE}"
else bad "no SchemaCard — run 10-wow-e2e.sh or 'lm source rediscover --scope ${WOW_SCOPE}'"; fi

summary
