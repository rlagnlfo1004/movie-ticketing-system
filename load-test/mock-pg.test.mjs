import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { after, before, test } from 'node:test';
import { createServer as createHttpServer } from 'node:http';
import { createMockPgServer } from './mock-pg.mjs';

const secret = `whsec_${Buffer.from('local-webhook-secret').toString('base64')}`;
let pg;
let webhook;
let baseUrl;
let deliveries = [];

before(async () => {
  webhook = createHttpServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    deliveries.push({ headers: req.headers, body: Buffer.concat(chunks).toString() });
    res.writeHead(200).end();
  });
  await new Promise(resolve => webhook.listen(0, '127.0.0.1', resolve));
  pg = createMockPgServer({
    apiSecret: 'local-api-secret', webhookSecret: secret, storeId: 'store-test',
    approvalDelayMinMs: 0, approvalDelayMaxMs: 0,
    webhookDelayMinMs: 0, webhookDelayMaxMs: 0, firstDeliveryFailureRate: 0,
  });
  await new Promise(resolve => pg.listen(0, '127.0.0.1', resolve));
  baseUrl = `http://127.0.0.1:${pg.address().port}`;
});

after(async () => {
  await new Promise(resolve => pg.close(resolve));
  await new Promise(resolve => webhook.close(resolve));
});

test('승인한 결제는 조회되고 서명된 웹훅이 전달된다', async () => {
  const paymentId = 'pay-1';
  const preregistered = await fetch(`${baseUrl}/payments/${paymentId}/pre-register`, {
    method: 'POST', headers: { authorization: 'PortOne local-api-secret', 'content-type': 'application/json' },
    body: JSON.stringify({ totalAmount: 30000 }),
  });
  assert.equal(preregistered.status, 200);
  const response = await fetch(`${baseUrl}/test/payments/${paymentId}/approve`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ amount: 30000, webhookUrl: `http://127.0.0.1:${webhook.address().port}/webhook` }),
  });
  assert.equal(response.status, 200);
  const lookup = await fetch(`${baseUrl}/payments/${paymentId}`, {
    headers: { authorization: 'PortOne local-api-secret' },
  });
  assert.equal(lookup.status, 200);
  const payment = await lookup.json();
  assert.equal(payment.status, 'PAID');
  assert.equal(payment.amount.total, 30000);
  for (let i = 0; i < 50 && deliveries.length === 0; i++) {
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  assert.equal(deliveries.length, 1);
  const delivery = deliveries[0];
  const data = JSON.parse(delivery.body);
  assert.equal(data.type, 'Transaction.Paid');
  assert.equal(data.data.paymentId, paymentId);
  const expected = createHmac('sha256', Buffer.from(secret.slice(6), 'base64'))
    .update(`${delivery.headers['webhook-id']}.${delivery.headers['webhook-timestamp']}.${delivery.body}`)
    .digest('base64');
  assert.equal(delivery.headers['webhook-signature'], `v1,${expected}`);
});

test('결제 취소 후 조회 상태가 CANCELLED로 바뀐다', async () => {
  const response = await fetch(`${baseUrl}/payments/pay-1/cancel`, {
    method: 'POST', headers: { authorization: 'PortOne local-api-secret', 'content-type': 'application/json' },
    body: JSON.stringify({ reason: '선점 만료', skipWebhook: true }),
  });
  assert.equal(response.status, 200);
  assert.equal((await response.json()).cancellation.status, 'SUCCEEDED');
  const lookup = await fetch(`${baseUrl}/payments/pay-1`, {
    headers: { authorization: 'PortOne local-api-secret' },
  });
  assert.equal((await lookup.json()).status, 'CANCELLED');
});
