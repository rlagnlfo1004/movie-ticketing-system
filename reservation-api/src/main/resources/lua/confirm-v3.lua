-- ARGV: screeningId, paymentId, amount, transactionId, reservationId
local sid = ARGV[1]
local tag = '{' .. sid .. '}'
local resultKey = 'payment:' .. tag .. ':' .. ARGV[2]
local prior = redis.call('GET', resultKey)
if prior then return prior end
local mapping = 'payment:' .. tag .. ':' .. ARGV[2] .. ':hold'
local holdId = redis.call('GET', mapping)
if not holdId then return cjson.encode({code='MISSING'}) end
local holdKey = 'hold:' .. tag .. ':' .. holdId
local token = redis.call('HGET', holdKey, 'token')
local seatsJson = redis.call('HGET', holdKey, 'seats')
local amount = redis.call('HGET', holdKey, 'amount')
local idempotencyKey = redis.call('HGET', holdKey, 'idempotencyKey')
if not token or not seatsJson then return cjson.encode({code='MISSING'}) end
if tonumber(amount) ~= tonumber(ARGV[3]) then return cjson.encode({code='AMOUNT_MISMATCH'}) end
local seats = cjson.decode(seatsJson)
local reservedKey = 'seat:' .. tag .. ':reserved'
for _, seat in ipairs(seats) do
  local seatKey = 'seat:' .. tag .. ':hold:' .. seat
  local holder = redis.call('GET', seatKey)
  if redis.call('SISMEMBER', reservedKey, seat) == 1 or (holder and holder ~= holdId) then
    return cjson.encode({code='EXPIRED_TAKEN'})
  end
end
for _, seat in ipairs(seats) do
  redis.call('SADD', reservedKey, seat)
  redis.call('DEL', 'seat:' .. tag .. ':hold:' .. seat)
end
redis.call('SET', 'token:' .. tag .. ':' .. token .. ':reservation', ARGV[5])
redis.call('DEL', 'token:' .. tag .. ':' .. token .. ':hold')
redis.call('DEL', 'token:' .. tag .. ':' .. token .. ':last_hold')
redis.call('DEL', holdKey)
redis.call('DEL', mapping)
local result = cjson.encode({reservationId=ARGV[5], status='CONFIRMED'})
redis.call('SET', resultKey, result)
local event = cjson.encode({screeningId=sid, reservationId=ARGV[5], holdId=holdId,
  paymentId=ARGV[2], queueToken=token, idempotencyKey=idempotencyKey,
  amount=tonumber(amount), transactionId=ARGV[4], seats=seats})
redis.call('PUBLISH', 'reservations:' .. tag, event)
return result
