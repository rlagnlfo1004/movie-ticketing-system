import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const base = __ENV.BASE_URL || 'http://nginx';
const pg = __ENV.MOCK_PG_URL || 'http://mock-pg:8082';
const webhook = __ENV.WEBHOOK_URL || 'http://reservation-api:8081/api/v1/webhooks/portone';
const sid = __ENV.SCREENING_ID || '1';
const smoke = __ENV.SMOKE === '1';
const rates = [100, 200, 400, 800, 1600, 3200];
const stageSeconds = Number(__ENV.STAGE_SECONDS || 60);
const holds = new Counter('seat_holds_created');
const taken = new Counter('seat_taken');
const confirmed = new Counter('reservations_confirmed');
const holdDuration = new Trend('seat_hold_duration', true);
const holdErrors = new Rate('seat_hold_errors');
const queueErrors = new Rate('queue_register_errors');
const paymentErrors = new Rate('payment_errors');

export const options = __ENV.SCENARIO === 'B' ? {
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
  scenarios: Object.fromEntries(rates.map((rate, i) => [`rate_${rate}`, {
    executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${stageSeconds}s`,
    startTime: `${i * stageSeconds}s`, preAllocatedVUs: Math.max(10, Math.ceil(rate / 20)),
    maxVUs: Math.max(20, rate * 2), exec: 'holdOnly',
  }])),
  thresholds: Object.fromEntries(rates.flatMap(rate => [
    [`seat_hold_duration{scenario:rate_${rate}}`, ['p(95)<1000']],
    [`seat_hold_errors{scenario:rate_${rate}}`, ['rate<0.01']],
  ])),
} : {
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
  scenarios: { full_flow: {
    executor: 'constant-arrival-rate', rate: smoke ? 2 : 3000, timeUnit: '1s',
    duration: smoke ? '5s' : '10s', preAllocatedVUs: smoke ? 5 : 5000,
    maxVUs: smoke ? 20 : 30000, exec: 'fullFlow',
    gracefulStop: smoke ? '30s' : '15m',
  } },
  thresholds: {
    queue_register_errors: ['rate<0.01'], seat_hold_errors: ['rate<0.01'],
    payment_errors: ['rate<0.01'],
  },
};

function body(response) { try { return response.json(); } catch { return {}; } }
function id() { return `${exec.vu.idInTest}-${exec.vu.iterationInScenario}-${Date.now()}-${Math.floor(Math.random() * 1e9)}`; }

function chooseSeats() {
  const p = Math.random();
  const count = p < .2 ? 1 : p < .7 ? 2 : p < .85 ? 3 : 4;
  const center = Math.random() < .7;
  const candidates = [];
  for (let n = center ? 350 : 0; n <= (center ? 649 : 999) - count + 1; n++) {
    if (Math.floor(n / 40) === Math.floor((n + count - 1) / 40)) candidates.push(n);
  }
  const first = candidates[Math.floor(Math.random() * candidates.length)];
  return Array.from({ length: count }, (_, i) => {
    const n = first + i;
    return `${String.fromCharCode(65 + Math.floor(n / 40))}-${n % 40 + 1}`;
  });
}

function makeHold(token, attempts = 4) {
  for (let attempt = 0; attempt < attempts; attempt++) {
    const response = http.post(`${base}/api/v1/screenings/${sid}/holds`,
      JSON.stringify({ seats: chooseSeats() }), { headers: {
        'Content-Type': 'application/json', 'Queue-Token': token, 'Idempotency-Key': id(),
      }, timeout: '10s', tags: { name: '/screenings/{sid}/holds', operation: 'hold' } });
    holdDuration.add(response.timings.duration);
    if (response.status === 201) { holds.add(1); holdErrors.add(0); return body(response); }
    if (response.status === 409 && body(response).code === 'SEAT_TAKEN') {
      taken.add(1); holdErrors.add(0); continue;
    }
    holdErrors.add(1);
    return null;
  }
  return null;
}

export function fullFlow() {
  const registered = http.post(`${base}/api/v1/screenings/${sid}/queue/register`, '{}',
    { headers: { 'Content-Type': 'application/json' }, timeout: '10s' });
  if (registered.status !== 200) { queueErrors.add(1); return; }
  const token = body(registered).token;
  queueErrors.add(token ? 0 : 1);
  if (!token) return;
  const deadline = Date.now() + (smoke ? 15000 : 600000);
  let active = false;
  while (Date.now() < deadline) {
    const response = http.get(`${base}/api/v1/screenings/${sid}/queue/status?token=${encodeURIComponent(token)}`,
      { timeout: '5s', tags: { name: '/screenings/{sid}/queue/status' } });
    if (response.status !== 200) return;
    const status = body(response);
    if (status.status === 'ACTIVE') { active = true; break; }
    sleep(Math.max(.1, (status.pollAfterMillis || 1000) / 1000));
  }
  if (!active) return;
  if (http.get(`${base}/api/v1/screenings/${sid}/seats`, { timeout: '10s' }).status !== 200) return;
  const hold = makeHold(token);
  if (!hold || Math.random() >= .85) return;
  const paid = http.post(`${pg}/test/payments/${encodeURIComponent(hold.paymentId)}/approve`,
    JSON.stringify({ amount: hold.amount, webhookUrl: webhook }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '5s', tags: { name: '/test/payments/{id}/approve' } });
  paymentErrors.add(paid.status === 200 ? 0 : 1);
  if (paid.status !== 200 || Math.random() < .1) return;
  const complete = http.post(`${base}/api/v1/payments/${encodeURIComponent(hold.paymentId)}/complete`,
    '{}', { headers: { 'Content-Type': 'application/json' }, timeout: '10s', tags: { name: '/payments/{id}/complete' } });
  if (check(complete, { 'reservation confirmed': r => r.status === 200 })) confirmed.add(1);
}

export function holdOnly() {
  const token = `seed-${exec.scenario.name}-${String(exec.scenario.iterationInTest).padStart(7, '0')}`;
  makeHold(token, 1);
}
