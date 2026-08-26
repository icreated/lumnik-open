#!/usr/bin/env bash
# Suite 3 — THE ACTS: analytic NL→SQL over the ingested hub (data must already be in).
# Each act: ask in natural language (curl mode=analytic) AND show the deterministic
# ground-truth from a direct readonly SQL, so the WOW is verifiable, not just plausible.
# Acts run loose (HTTP 200 + >=1 row) because the LLM's SQL phrasing varies run to run.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# ensure the SchemaCard exists (cheap, idempotent)
lm source rediscover --scope "${WOW_SCOPE}" >/dev/null 2>&1 || true

# act <title> "<question>" "<ground-truth-sql>"
act() {
  local title="$1" q="$2" truth_sql="$3"
  hdr "$title"
  echo "  Q: $q"
  local resp; resp="$(analytic_ask "$q")"
  local parsed rc sql ans
  parsed="$(printf '%s' "$resp" | parse_analytic)"
  rc="${parsed%%|*}"; rest="${parsed#*|}"; sql="${rest%%|*}"; ans="${rest##*|}"
  echo "  SQL : ${sql:-<none>}"
  echo "  ANS : ${ans:-<none>}"
  if [ "$rc" = "PARSE_ERR" ]; then
    echo "  raw: $(printf '%s' "$resp" | head -c 300)"
    bad "$title — analytic call returned no parseable JSON (Ollama down? scope has no SchemaCard?)"
  elif [ "${rc:-0}" -ge 1 ] 2>/dev/null; then
    ok "$title — analytic returned ${rc} row(s)"
  else
    bad "$title — analytic returned 0 rows"
  fi
  if [ -n "$truth_sql" ]; then
    echo "  ground-truth (readonly SQL): $(ro_sql "$truth_sql" | tr '\n' ' ')"
  fi
}

# Act A — the headline number (count + total value of sales orders)
act "Act A — sales orders: count & total value" \
    "How many sales orders are there and what is their total value?" \
    "SELECT count(*) AS n, round(sum(grandtotal)::numeric,2) AS total FROM connector.t_c_order"

# Act 3 — breakdown by document status (the kind of slice the frozen ERP buries)
act "Act 3 — orders by document status" \
    "Break down the number of orders by their document status." \
    "SELECT docstatus, count(*) FROM connector.t_c_order GROUP BY docstatus ORDER BY 2 DESC"

# Act D — cross-table reasoning (orders joined to partners) — the demanding one
act "Act D — top business partners by order value" \
    "Who are the top 5 business partners by total order value?" \
    "SELECT o.c_bpartner_id, round(sum(o.grandtotal)::numeric,2) total FROM connector.t_c_order o GROUP BY o.c_bpartner_id ORDER BY total DESC LIMIT 5"

summary
