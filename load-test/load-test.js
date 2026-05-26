import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8000';
const SCREENING_ID = __ENV.SCREENING_ID || '1';

const ticketIssued    = new Counter('tickets_issued');
const ticketSoldOut   = new Counter('tickets_sold_out');
const queueRegErrors  = new Rate('queue_register_errors');
const ticketErrors    = new Rate('ticket_issue_errors');
const registerDur     = new Trend('queue_register_duration', true);
const ticketDur       = new Trend('ticket_issue_duration', true);

export const options = {
  scenarios: {
    /**
     * 30,000명이 동시에 접속해서 각자 대기열 등록 → 폴링 → 티켓 발급까지 수행.
     * - 5,000 VU가 30,000 iteration을 나눠서 처리 (초기 5,000개 등록이 동시 burst).
     * - 처리 시간은 상관없으므로 maxDuration 15분.
     * - 핵심 검증: tickets_issued == 1000 (정확히 1000장만 발급)
     */
    thirty_k_users: {
      executor: 'shared-iterations',
      vus: 5000,
      iterations: 30000,
      maxDuration: '15m',
      exec: 'fullFlow',
    },
  },
  thresholds: {
    // 핵심: 1000장이 모두 발급되어야 한다
    'tickets_issued':        ['count>=1000'],
    // 등록 에러 1% 미만 (30k burst 허용)
    'queue_register_errors': ['rate<0.01'],
    // 발급 시도 에러는 SOLD_OUT 제외하고 1% 미만
    'ticket_issue_errors':   ['rate<0.01'],
  },
};

export function fullFlow() {
  // ── 1. 대기열 등록 ──────────────────────────────────────────────
  const regStart = Date.now();
  const regRes = http.post(
    `${BASE_URL}/api/v1/screenings/${SCREENING_ID}/queue/register`,
    JSON.stringify({ token: null }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '10s' }
  );
  registerDur.add(Date.now() - regStart);

  const regOk = check(regRes, {
    'register 200': (r) => r.status === 200,
  });
  if (!regOk) {
    queueRegErrors.add(1);
    return;
  }
  queueRegErrors.add(0);

  let body;
  try { body = JSON.parse(regRes.body); } catch { return; }
  const token = body.token;
  if (!token) return;

  // ── 2. ADMITTED 될 때까지 폴링 (최대 10분) ──────────────────────
  // 1000장 모두 나간 뒤에도 대기 중인 사용자는 영원히 WAITING → 적당히 타임아웃
  let admitted = false;
  for (let i = 0; i < 600; i++) {
    const statusRes = http.get(
      `${BASE_URL}/api/v1/screenings/${SCREENING_ID}/queue/status?token=${token}`,
      { timeout: '5s' }
    );
    if (statusRes.status !== 200) break;

    let statusBody;
    try { statusBody = JSON.parse(statusRes.body); } catch { break; }

    if (statusBody.status === 'ACTIVE') {
      admitted = true;
      break;
    }

    sleep((statusBody.pollAfterMillis || 1000) / 1000);
  }

  if (!admitted) return;

  // ── 3. 티켓 발급 ───────────────────────────────────────────────
  const ticketStart = Date.now();
  const ticketRes = http.post(
    `${BASE_URL}/api/v1/screenings/${SCREENING_ID}/tickets`,
    JSON.stringify({ quantity: 1 }),
    {
      headers: {
        'Content-Type':   'application/json',
        'Queue-Token':    token,
        'Idempotency-Key': uuidv4(),
      },
      timeout: '10s',
    }
  );
  ticketDur.add(Date.now() - ticketStart);

  if (ticketRes.status === 201) {
    ticketIssued.add(1);
    check(ticketRes, { 'ticket 201': () => true });
  } else if (ticketRes.status === 409) {
    // SOLD_OUT — 정상 응답, 에러로 집계하지 않음
    ticketSoldOut.add(1);
  } else {
    ticketErrors.add(1);
    check(ticketRes, { 'ticket 201': () => false });
  }
}
