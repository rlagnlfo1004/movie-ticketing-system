-- KEYS: reserved, token hold, token reservation, hold hash, payment hold, payment screening, last hold, seat hold keys...
-- ARGV: holdId, paymentId, token, idempotencyKey, amount, heldUntilMs, holdMs, retentionMs, seatsJson, screeningId
if redis.call('EXISTS', KEYS[3]) == 1 then return -3 end
if redis.call('EXISTS', KEYS[2]) == 1 then return -2 end
for i = 8, #KEYS do
  local seat = string.match(KEYS[i], ':hold:(.+)$')
  if redis.call('SISMEMBER', KEYS[1], seat) == 1 or redis.call('EXISTS', KEYS[i]) == 1 then
    return 0
  end
end
for i = 8, #KEYS do
  redis.call('SET', KEYS[i], ARGV[1], 'PX', ARGV[7])
end
redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[7])
redis.call('SET', KEYS[7], ARGV[1], 'PX', ARGV[8])
redis.call('HSET', KEYS[4], 'token', ARGV[3], 'seats', ARGV[9], 'paymentId', ARGV[2],
  'amount', ARGV[5], 'idempotencyKey', ARGV[4], 'heldUntil', ARGV[6], 'screeningId', ARGV[10])
redis.call('PEXPIRE', KEYS[4], ARGV[8])
redis.call('SET', KEYS[5], ARGV[1], 'PX', ARGV[8])
redis.call('SET', KEYS[6], ARGV[10], 'PX', ARGV[8])
return 1
