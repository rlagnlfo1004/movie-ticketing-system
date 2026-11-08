-- ARGV: screeningId, paymentId
local tag = '{' .. ARGV[1] .. '}'
local resultKey = 'payment:' .. tag .. ':' .. ARGV[2]
local prior = redis.call('GET', resultKey)
if prior then return prior end
local mapping = resultKey .. ':hold'
local holdId = redis.call('GET', mapping)
if holdId then
  local holdKey = 'hold:' .. tag .. ':' .. holdId
  local token = redis.call('HGET', holdKey, 'token')
  local seatsJson = redis.call('HGET', holdKey, 'seats')
  if seatsJson then
    for _, seat in ipairs(cjson.decode(seatsJson)) do
      local seatKey = 'seat:' .. tag .. ':hold:' .. seat
      if redis.call('GET', seatKey) == holdId then redis.call('DEL', seatKey) end
    end
  end
  if token then
    redis.call('SET', 'token:' .. tag .. ':' .. token .. ':cancelled', '1', 'EX', 1800)
    redis.call('DEL', 'token:' .. tag .. ':' .. token .. ':hold')
    redis.call('DEL', 'token:' .. tag .. ':' .. token .. ':last_hold')
  end
  redis.call('DEL', holdKey)
  redis.call('DEL', mapping)
end
local result = cjson.encode({status='CANCELLED'})
redis.call('SET', resultKey, result)
return result
