// Smoke: 1 VU, a handful of iterations over every read endpoint.
// Purpose: prove the harness itself works (auth, routes, thresholds) before
// any sizing run. Fast enough to run before every read-mix session.
import http from 'k6/http';
import { check } from 'k6';
import { BASE, authParams, READ_ENDPOINTS } from './lib.js';

export const options = {
  vus: 1,
  iterations: 3,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate==1.0'],
  },
};

export default function () {
  for (const e of READ_ENDPOINTS) {
    const res = http.get(`${BASE}${e.path}`, authParams(e.name));
    check(res, { [`${e.name} is 200`]: (r) => r.status === 200 });
  }
}
