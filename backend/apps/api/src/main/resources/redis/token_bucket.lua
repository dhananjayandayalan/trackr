-- Token bucket (09-security §8), run atomically by Redis: no other command can slip in between the read and the
-- write below, so two api instances (or two threads) can't both spend the last token.
--
-- KEYS[1]  the bucket, e.g. rl:{userId}:write
-- ARGV[1]  refill rate, tokens per second (may be fractional: 5/min = 0.0833)
-- ARGV[2]  burst = bucket size
-- ARGV[3]  tokens this request costs (1)
-- returns  { allowed (1/0), milliseconds until enough tokens are back (0 if allowed), tokens left (rounded down) }

local rate = tonumber(ARGV[1])
local burst = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])

-- Redis's clock, not the caller's: every api instance then agrees on "now", however far their clocks drift
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil then
  -- a new (or expired) bucket starts full
  tokens = burst
  ts = now
end

-- refill for the time since the last request, never above the bucket size
tokens = math.min(burst, tokens + math.max(0, now - ts) * rate / 1000)

local allowed = 0
local wait = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  wait = math.ceil((cost - tokens) * 1000 / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- an idle bucket refills completely after burst/rate seconds; after that it carries no information, so let it go
redis.call('PEXPIRE', KEYS[1], math.ceil(burst * 1000 / rate) + 1000)
return { allowed, wait, math.floor(tokens) }
