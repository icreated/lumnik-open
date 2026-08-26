// Read-mix ramp: the sizing scenario.
// Ramps virtual users over the weighted read surface and reports latency
// per endpoint. Raise MAX_VUS between runs until p95 bends or errors appear —
// that knee is the capacity of the deployment under test.
//
// Env knobs:
//   MAX_VUS      peak virtual users            (default 25)
//   STAGE        duration of each ramp stage   (default 60s)
//   P95_MS       p95 gate per endpoint, ms     (default 500)
//   THINK_MS     think time between requests   (default 200)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE, authParams, pick, READ_ENDPOINTS, numEnv } from './lib.js';

const MAX_VUS = numEnv('MAX_VUS', 25);
const STAGE = __ENV.STAGE || '60s';
const P95 = numEnv('P95_MS', 500);

// One latency gate per endpoint IN THE ACTIVE MIX, so a single slow surface
// is named, not averaged away — and an ONLY-excluded endpoint gets no phantom
// always-green threshold.
const thresholds = { http_req_failed: ['rate<0.01'] };
for (const e of READ_ENDPOINTS) {
  thresholds[`http_req_duration{endpoint:${e.name}}`] = [`p(95)<${P95}`];
}

export const options = {
  scenarios: {
    read_mix: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: Math.max(1, Math.round(MAX_VUS / 5)) },
        { duration: STAGE, target: Math.round(MAX_VUS / 2) },
        { duration: STAGE, target: MAX_VUS },
        { duration: '20s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds,
};

export default function () {
  const e = pick(__ITER);
  const res = http.get(`${BASE}${e.path}`, authParams(e.name));
  check(res, { 'status 200': (r) => r.status === 200 });
  sleep(Number(__ENV.THINK_MS || 200) / 1000);
}
