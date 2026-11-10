-- KEYS: queue, admission lock, active index, fencing floor.
-- ARGV: lock owner, Sentinel config epoch, now ms, max active, batch size,
--       active TTL ms, active key prefix.
if redis.call('GET', KEYS[2]) ~= ARGV[1] then return -1 end
local epoch = tonumber(ARGV[2])
local floor = tonumber(redis.call('GET', KEYS[4]) or '-1')
if epoch < floor then return -2 end
if epoch > floor then redis.call('SET', KEYS[4], ARGV[2]) end

local expired = redis.call('ZRANGEBYSCORE', KEYS[3], '-inf', ARGV[3])
for _, token in ipairs(expired) do
  if redis.call('EXISTS', ARGV[7] .. token) == 0 then
    redis.call('ZREM', KEYS[3], token)
  end
end

local capacity = tonumber(ARGV[4]) - redis.call('ZCARD', KEYS[3])
if capacity <= 0 then return -3 end
local selected = math.min(tonumber(ARGV[5]), capacity)
local admitted = 0
for i = 1, selected do
  local popped = redis.call('ZPOPMIN', KEYS[1], 1)
  if #popped == 0 then break end
  local token = popped[1]
  if redis.call('SET', ARGV[7] .. token, token, 'NX', 'PX', ARGV[6]) then
    redis.call('ZADD', KEYS[3], tonumber(ARGV[3]) + tonumber(ARGV[6]), token)
    admitted = admitted + 1
  end
end
if admitted == 0 then return -4 end
return admitted
