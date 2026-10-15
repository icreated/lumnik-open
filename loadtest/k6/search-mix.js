// Search mix: constant-arrival-rate queries over four equivalent entities, one per matching mode (#750).
// Env: RATE (req/s, 20) DURATION (60s) PHASE (A|C) ENTITIES (sz_exact,sz_text,sz_fuzzy,sz_prefix)
//      MIN_HIT_SHARE (0.95) P95_MS (500)   — unknown values fail loudly, never silently.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';
import { BASE, authParams, numEnv } from './lib.js';

const MODES = { exact: 'sz_exact', text: 'sz_text', fuzzy: 'sz_fuzzy', prefix: 'sz_prefix' };
const RATE = numEnv('RATE', 20);
const DURATION = __ENV.DURATION || '60s';
const PHASE = __ENV.PHASE || 'A';
if (!['A', 'C'].includes(PHASE)) throw new Error(`PHASE='${PHASE}' must be A or C`);
const MIN_HIT = numEnv('MIN_HIT_SHARE', 0.95);
const P95 = numEnv('P95_MS', 500);

const wanted = (__ENV.ENTITIES || Object.values(MODES).join(',')).split(',').map((s) => s.trim()).filter(Boolean);
const known = new Set(Object.values(MODES));
const unknown = wanted.filter((e) => !known.has(e));
if (unknown.length > 0) throw new Error(`ENTITIES names unknown entity(ies): ${unknown.join(', ')} — known: ${[...known].join(', ')}`);
const active = Object.entries(MODES).filter(([, e]) => wanted.includes(e));

const QUERIES = JSON.parse(open('/data/queries.json'));
const hit = new Rate('search_hit');
const expectHit = new Rate('search_expect_hit');
const tooMany = new Counter('search_http_429');

const thresholds = { http_req_failed: ['rate<0.01'], search_http_429: ['count==0'] };
// per mode, like latency: an aggregate hit share would let three healthy surfaces hide a fourth that answers nothing
for (const [mode] of active) {
  thresholds[`http_req_duration{mode:${mode}}`] = [`p(95)<${P95}`];
  thresholds[`search_hit{mode:${mode}}`] = [`rate>${MIN_HIT}`];
}
if (active.some(([m]) => m === 'exact')) thresholds['search_expect_hit{mode:exact}'] = ['rate>0.99'];

export const options = {
  scenarios: {
    search: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: Math.max(10, RATE), maxVUs: Math.max(50, RATE * 4) },
  },
  thresholds,
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

let announced = false;  // per VU: the orchestrator waits for this line before it starts an ingestion

export default function () {
  // iterationInTest counts across ALL VUs: __ITER restarts at 0 in every VU, so a short phase would only ever use the
  // head of each query list and phases of different length would compare different queries.
  const n = exec.scenario.iterationInTest;
  const [mode, entity] = active[n % active.length];
  const list = QUERIES[mode];
  const q = list[Math.floor(n / active.length) % list.length];
  const params = authParams(`search-${mode}`);
  params.tags = { ...params.tags, mode, phase: PHASE };
  const res = http.get(`${BASE}/api/search?entity=${entity}&limit=10&q=${encodeURIComponent(q.q)}`, params);
  if (!announced) { announced = true; console.log('SZ_FIRST_REQUEST'); }
  if (res.status === 429) tooMany.add(1);
  const ok = check(res, { 'status 200': (r) => r.status === 200 });
  let hits = [];
  if (ok) { try { hits = res.json('hits') || []; } catch (e) { hits = []; } }
  hit.add(ok && hits.length > 0, { mode });
  if (mode === 'exact') expectHit.add(ok && hits.some((h) => JSON.stringify(h).includes(q.expect_code)), { mode });
}

export function handleSummary(data) {
  const m = data.metrics;
  const per = {};
  for (const [mode] of active) {
    const d = m[`http_req_duration{mode:${mode}}`];
    const h = m[`search_hit{mode:${mode}}`];
    per[mode] = d ? { p50: d.values.med, p95: d.values['p(95)'], p99: d.values['p(99)'], max: d.values.max,
      hit_share: h ? h.values.rate : null } : null;
  }
  const out = {
    phase: PHASE, rate: RATE, duration: DURATION, modes: per,
    requests: m.http_reqs ? m.http_reqs.values.count : 0,
    failed_rate: m.http_req_failed ? m.http_req_failed.values.rate : null,
    http_429: m.search_http_429 ? m.search_http_429.values.count : 0,
    expect_share_exact: m['search_expect_hit{mode:exact}'] ? m['search_expect_hit{mode:exact}'].values.rate : null,
  };
  const path = __ENV.SUMMARY_OUT || '/out/summary.json';
  return { [path]: JSON.stringify(out, null, 1), stdout: JSON.stringify(out) + '\n' };
}
