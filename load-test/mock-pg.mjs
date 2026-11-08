import { createHmac, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const randomDelay = (min, max) => Math.round(min + Math.random() * (max - min));

export function createMockPgServer(config = {}) {
  const {
    apiSecret = 'mock-api-secret',
    webhookSecret = `whsec_${Buffer.from('mock-webhook-secret').toString('base64')}`,
    storeId = 'store-mock',
    approvalDelayMinMs = 500,
    approvalDelayMaxMs = 2000,
    webhookDelayMinMs = 200,
    webhookDelayMaxMs = 800,
    firstDeliveryFailureRate = 0.01,
  } = config;
  const payments = new Map();
  const destinations = new Map();

  async function sendWebhook(payment, type, webhookUrl) {
    if (!webhookUrl) return;
    await sleep(randomDelay(webhookDelayMinMs, webhookDelayMaxMs));
    const eventId = `msg_${randomUUID()}`;
    const body = JSON.stringify({
      type, timestamp: new Date().toISOString(),
      data: { storeId, paymentId: payment.id, transactionId: payment.transactionId },
    });
    const deliberatelyFailFirst = Math.random() < firstDeliveryFailureRate;
    for (let attempt = 0; attempt < 3; attempt++) {
      if (attempt === 0 && deliberatelyFailFirst) {
        await sleep(1000);
        continue;
      }
      const timestamp = String(Math.floor(Date.now() / 1000));
      const signature = createHmac('sha256', Buffer.from(webhookSecret.replace(/^whsec_/, ''), 'base64'))
        .update(`${eventId}.${timestamp}.${body}`).digest('base64');
      try {
        const response = await fetch(webhookUrl, {
          method: 'POST',
          headers: {
            'content-type': 'application/json',
            'webhook-id': eventId,
            'webhook-timestamp': timestamp,
            'webhook-signature': `v1,${signature}`,
          },
          body,
          signal: AbortSignal.timeout(30000),
        });
        if (response.ok) return;
      } catch { /* 재전송 */ }
      if (attempt < 2) await sleep(attempt === 0 ? 1000 : 4000);
    }
  }

  return createServer(async (request, response) => {
    const send = (status, data) => {
      response.writeHead(status, { 'content-type': 'application/json' });
      response.end(JSON.stringify(data));
    };
    const readBody = async () => {
      let raw = '';
      for await (const chunk of request) raw += chunk;
      try { return raw ? JSON.parse(raw) : {}; } catch { return null; }
    };
    const url = new URL(request.url, 'http://localhost');
    const path = url.pathname;
    if (request.method === 'GET' && path === '/health') return send(200, { status: 'UP' });
    if (request.method === 'GET' && path === '/test/payments') return send(200, { payments: [...payments.values()] });

    const approve = path.match(/^\/test\/payments\/([^/]+)\/approve$/);
    if (request.method === 'POST' && approve) {
      const input = await readBody();
      if (!input || !Number.isSafeInteger(input.amount) || input.amount <= 0) {
        return send(400, { type: 'INVALID_REQUEST', message: 'amount must be a positive integer' });
      }
      const id = decodeURIComponent(approve[1]);
      const existing = payments.get(id);
      if (!existing) return send(404, { type: 'PAYMENT_NOT_FOUND' });
      if (existing.status !== 'READY') return send(409, { type: 'ALREADY_PAID' });
      if (existing.amount.total !== input.amount) return send(400, { type: 'INVALID_REQUEST' });
      await sleep(randomDelay(approvalDelayMinMs, approvalDelayMaxMs));
      existing.transactionId = randomUUID();
      existing.status = 'PAID';
      existing.amount.paid = input.amount;
      existing.paidAt = new Date().toISOString();
      existing.customData = input.customData == null ? null : JSON.stringify(input.customData);
      destinations.set(id, input.webhookUrl);
      void sendWebhook(existing, 'Transaction.Paid', input.webhookUrl);
      return send(200, existing);
    }

    if (request.headers.authorization !== `PortOne ${apiSecret}`) {
      return send(401, { type: 'UNAUTHORIZED' });
    }
    if (request.method === 'GET' && path === '/payments') {
      let input = {};
      try { input = JSON.parse(url.searchParams.get('requestBody') || '{}'); } catch { return send(400, { type: 'INVALID_REQUEST' }); }
      const number = Number(input.page?.number ?? 0);
      const size = Number(input.page?.size ?? 10);
      if (!Number.isSafeInteger(number) || number < 0 || !Number.isSafeInteger(size) || size < 1) {
        return send(400, { type: 'INVALID_REQUEST' });
      }
      const all = [...payments.values()];
      return send(200, { items: all.slice(number * size, (number + 1) * size),
        page: { number, size, totalCount: all.length } });
    }
    const preregister = path.match(/^\/payments\/([^/]+)\/pre-register$/);
    if (request.method === 'POST' && preregister) {
      const input = await readBody();
      if (!input || !Number.isSafeInteger(input.totalAmount) || input.totalAmount <= 0) {
        return send(400, { type: 'INVALID_REQUEST' });
      }
      const id = decodeURIComponent(preregister[1]);
      const previous = payments.get(id);
      if (previous) {
        if (previous.status !== 'READY') return send(409, { type: 'ALREADY_PAID' });
        if (previous.amount.total !== input.totalAmount) return send(409, { type: 'AMOUNT_MISMATCH' });
        return send(200, previous);
      }
      const payment = {
        id, transactionId: null, storeId, status: 'READY',
        amount: { total: input.totalAmount, paid: 0, cancelled: 0, taxFree: 0, discount: 0, cancelledTaxFree: 0 },
        currency: 'KRW', channel: { type: 'TEST' }, requestedAt: new Date().toISOString(),
      };
      payments.set(id, payment);
      return send(200, payment);
    }
    const paymentRoute = path.match(/^\/payments\/([^/]+)(\/cancel)?$/);
    if (!paymentRoute) return send(404, { type: 'NOT_FOUND' });
    const payment = payments.get(decodeURIComponent(paymentRoute[1]));
    if (!payment) return send(404, { type: 'PAYMENT_NOT_FOUND' });
    if (request.method === 'GET' && !paymentRoute[2]) return send(200, payment);
    if (request.method === 'POST' && paymentRoute[2]) {
      const input = await readBody();
      if (!input || !input.reason) return send(400, { type: 'INVALID_REQUEST' });
      if (payment.status !== 'PAID') return send(409, { type: 'PAYMENT_ALREADY_CANCELLED' });
      payment.status = 'CANCELLED';
      payment.amount.cancelled = payment.amount.paid;
      const cancellation = {
        status: 'SUCCEEDED', id: randomUUID(), totalAmount: payment.amount.cancelled,
        reason: input.reason, requestedAt: new Date().toISOString(), cancelledAt: new Date().toISOString(),
      };
      if (!input.skipWebhook) void sendWebhook(payment, 'Transaction.Cancelled', destinations.get(payment.id));
      return send(200, { cancellation });
    }
    return send(405, { type: 'METHOD_NOT_ALLOWED' });
  });
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  createMockPgServer({
    apiSecret: process.env.MOCK_PG_API_SECRET,
    webhookSecret: process.env.MOCK_PG_WEBHOOK_SECRET,
    storeId: process.env.MOCK_PG_STORE_ID,
  }).listen(Number(process.env.PORT || 8082), '0.0.0.0');
}
