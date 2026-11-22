package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.we.sharesystem.entity.UserLike;
import com.we.sharesystem.mapper.UserLikeMapper;
import com.we.sharesystem.service.LikeService;
import com.we.sharesystem.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;


@Service
@RequiredArgsConstructor
public class LikeServiceImpl implements LikeService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final UserLikeMapper likeMapper;
    private final ThreadPoolTaskExecutor asyncExecutor;
    private final NotificationService notificationService;

    // Lua脚本：原子执行点赞/取消，防止并发超赞
    private static final String LIKE_LUA_SCRIPT = """
            local isMember = redis.call('SISMEMBER', KEYS[1], ARGV[1])
            if isMember == 1 then
                redis.call('SREM', KEYS[1], ARGV[1])
                redis.call('DECR', KEYS[2])
                return -1
            else
                redis.call('SADD', KEYS[1], ARGV[1])
                redis.call('INCR', KEYS[2])
                return 1
            end
            """;

    @Override
    public Integer like(Long userId, Long targetId, Integer targetType) {
        // redis key规范
        String relKey = "like:rel:" + targetType + ":" + targetId;
        String countKey = "like:count:" + targetType + ":" + targetId;

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(LIKE_LUA_SCRIPT, Long.class);
        Long res = redisTemplate.execute(redisScript, Arrays.asList(relKey, countKey), userId.toString());
        int op = res.intValue();

        // 异步落库到MySQL（不阻塞点赞接口）
        asyncExecutor.execute(() -> {
            if (op == 1) {
                // 点赞：插入记录
                UserLike like = new UserLike();
                like.setUserId(userId);
                like.setTargetId(targetId);
                like.setTargetType(targetType);
                try {
                    likeMapper.insert(like);
                    // 点赞成功才记通知（异步线程内，不阻塞接口）
                    notificationService.onLike(userId, targetId, targetType);
                } catch (DuplicateKeyException e) {
                    // 唯一索引冲突，代表已经存在，幂等，直接忽略
                }
            } else if (op == -1) {
                // 取消点赞：删除记录
                LambdaQueryWrapper<UserLike> wrapper = Wrappers.lambdaQuery(UserLike.class)
                        .eq(UserLike::getUserId, userId)
                        .eq(UserLike::getTargetId, targetId)
                        .eq(UserLike::getTargetType, targetType);
                likeMapper.delete(wrapper);
            }
        });
        return op;
    }

    @Override
    public boolean hasLike(Long userId, Long targetId, Integer targetType) {
        String relKey = "like:rel:" + targetType + ":" + targetId;
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(relKey, userId.toString()));
    }

    @Override
    public Long getLikeCount(Long targetId, Integer targetType) {
        String countKey = "like:count:" + targetType + ":" + targetId;
        Object val = redisTemplate.opsForValue().get(countKey);
        if (val == null) {
            // 缓存没有 → 查DB，回填Redis
            Long dbCount = likeMapper.selectCount(Wrappers.lambdaQuery(UserLike.class)
                    .eq(UserLike::getTargetId, targetId)
                    .eq(UserLike::getTargetType, targetType));
            redisTemplate.opsForValue().set(countKey, dbCount, 30, TimeUnit.MINUTES);
            return dbCount;
        }
        return Long.parseLong(val.toString());
    }
}
