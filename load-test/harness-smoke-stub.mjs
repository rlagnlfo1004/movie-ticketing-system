import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';

let next = 0;
createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const json = (status, data) => {
    res.writeHead(status, { 'content-type': 'application/json' });
    res.end(JSON.stringify(data));
  };
  if (url.pathname.endsWith('/queue/register')) return json(200, { token: `smoke-token-${++next}` });
  if (url.pathname.endsWith('/queue/status')) return json(200, { status: 'ACTIVE' });
  if (url.pathname.endsWith('/seats')) return json(200, { seats: [] });
  if (url.pathname.endsWith('/holds')) { const id = randomUUID(); return json(201, {
    holdId: `hold-${id}`, paymentId: `payment-${id}`,
    amount: 15000, heldUntil: new Date(Date.now() + 30000).toISOString(),
  }); }
  if (url.pathname.endsWith('/complete')) return json(200, { reservationId: `reservation-${next}`, status: 'CONFIRMED' });
  if (url.pathname.endsWith('/webhooks/portone')) return json(200, {});
  return json(404, { code: 'NOT_FOUND' });
}).listen(Number(process.env.PORT || 18080), '0.0.0.0');
