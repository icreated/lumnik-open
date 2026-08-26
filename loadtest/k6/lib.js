// Shared config for lumnik k6 scenarios.
// Auth: pass K6_TOKEN (a Bearer token for an integrator user) — see ../run.sh,
// which mints one via the lm-cli client's direct grant and handles the issuer
// pitfall (the token must be minted against the SAME issuer URL the hub trusts).

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export function authParams(endpoint) {
  if (!__ENV.K6_TOKEN) {
    throw new Error('K6_TOKEN is required — run through ../run.sh or export a Bearer token');
  }
  return {
    headers: {
      Authorization: `Bearer ${__ENV.K6_TOKEN}`,
      Accept: 'application/json',
    },
    tags: { endpoint },
  };
}

// The read mix: every endpoint answers 200 for an integrator on any CURRENT
// stack, even an empty one — list endpoints only, no ids to guess, no model
// calls. ONLY=name,name narrows the mix explicitly (isolate one surface, or
// run against an older build that predates a surface) — never silently.
const ALL_READ_ENDPOINTS = [
  { name: 'version',    path: '/api/platform/version',    weight: 1 },
  { name: 'connectors', path: '/api/platform/connectors', weight: 2 },
  { name: 'runs',       path: '/api/platform/runs',       weight: 3 },
  { name: 'views',      path: '/api/views',               weight: 3 },
  { name: 'workflows',  path: '/api/workflows',           weight: 1 },
  { name: 'inbox',      path: '/api/inbox',               weight: 2 },
  // NB: there is no bare GET /api/entities — the entity read surface is
  // /{name} (needs an applied manifest) and /conflicts (a real list, 200 on
  // any stack). The conflicts ledger is the honest choice here.
  { name: 'conflicts',  path: '/api/entities/conflicts',  weight: 1 },
];

function narrow(only) {
  const names = only.split(',').map((n) => n.trim()).filter(Boolean);
  const known = new Set(ALL_READ_ENDPOINTS.map((e) => e.name));
  const unknown = names.filter((n) => !known.has(n));
  if (unknown.length > 0) {
    throw new Error(`ONLY names unknown endpoint(s): ${unknown.join(', ')} — known: ${[...known].join(', ')}`);
  }
  return ALL_READ_ENDPOINTS.filter((e) => names.includes(e.name));
}

export const READ_ENDPOINTS = __ENV.ONLY ? narrow(__ENV.ONLY) : ALL_READ_ENDPOINTS;
if (READ_ENDPOINTS.length === 0) {
  throw new Error(`ONLY='${__ENV.ONLY}' matches no endpoint`);
}

// Numeric env knob with a loud failure on garbage — a silent NaN would feed
// the executor nonsense stages.
export function numEnv(name, fallback) {
  const raw = __ENV[name];
  if (raw === undefined || raw === '') return fallback;
  const v = Number(raw);
  if (Number.isNaN(v)) throw new Error(`${name}='${raw}' is not a number`);
  return v;
}

// Weighted round-robin, deterministic per iteration index.
const WHEEL = READ_ENDPOINTS.flatMap((e) => Array(e.weight).fill(e));
export function pick(i) {
  return WHEEL[i % WHEEL.length];
}
