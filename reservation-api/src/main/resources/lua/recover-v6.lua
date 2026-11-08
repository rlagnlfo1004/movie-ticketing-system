-- ARGV: sid, paymentId, holdId, token, idempotencyKey, amount, transactionId, reservationId, seatsJson
local tag = '{' .. ARGV[1] .. '}'
local reservedKey = 'seat:' .. tag .. ':reserved'
local paymentKey = 'payment:' .. tag .. ':' .. ARGV[2]
local existing = redis.call('GET', paymentKey)
local reservationId = ARGV[8]
if existing then
  local decoded = cjson.decode(existing)
  if decoded.status == 'CANCELLED' then return cjson.encode({code='EXPIRED_TAKEN'}) end
  if decoded.reservationId then reservationId = decoded.reservationId end
end
local seats = cjson.decode(ARGV[9])
if not existing then
  for _, seat in ipairs(seats) do
    local seatKey = 'seat:' .. tag .. ':hold:' .. seat
    local holder = redis.call('GET', seatKey)
    if redis.call('SISMEMBER', reservedKey, seat) == 1 or (holder and holder ~= ARGV[3]) then
      return cjson.encode({code='EXPIRED_TAKEN'})
    end
  end
end
for _, seat in ipairs(seats) do
  redis.call('SADD', reservedKey, seat)
  local seatKey = 'seat:' .. tag .. ':hold:' .. seat
  if redis.call('GET', seatKey) == ARGV[3] then redis.call('DEL', seatKey) end
end
redis.call('SET', 'token:' .. tag .. ':' .. ARGV[4] .. ':reservation', reservationId)
redis.call('SET', 'payment:screening:' .. ARGV[2], ARGV[1])
local result = existing or cjson.encode({reservationId=reservationId,status='CONFIRMED'})
redis.call('SET', paymentKey, result)
local event = cjson.encode({screeningId=ARGV[1], reservationId=reservationId, holdId=ARGV[3],
  paymentId=ARGV[2], queueToken=ARGV[4], idempotencyKey=ARGV[5],
  amount=tonumber(ARGV[6]), transactionId=ARGV[7], seats=seats})
redis.call('XADD', 'stream:' .. tag .. ':reservations', '*', 'event', event)
redis.call('DEL', 'token:' .. tag .. ':' .. ARGV[4] .. ':hold')
redis.call('DEL', 'token:' .. tag .. ':' .. ARGV[4] .. ':last_hold')
redis.call('DEL', 'hold:' .. tag .. ':' .. ARGV[3])
redis.call('DEL', paymentKey .. ':hold')
return result
