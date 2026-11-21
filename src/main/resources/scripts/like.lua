-- KEYS[1] = like:rel:1:1001 关系集合
-- KEYS[2] = like:count:1:1001 计数字符串
-- ARGV[1] = userId
-- 返回：1=点赞成功，-1=取消点赞成功，0=参数异常
local isMember = redis.call('SISMEMBER', KEYS[1], ARGV[1])
if isMember == 1 then
    -- 已点赞 → 取消
    redis.call('SREM', KEYS[1], ARGV[1])
    redis.call('DECR', KEYS[2])
    return -1
else
    -- 未点赞 → 点赞
    redis.call('SADD', KEYS[1], ARGV[1])
    redis.call('INCR', KEYS[2])
    return 1
end
