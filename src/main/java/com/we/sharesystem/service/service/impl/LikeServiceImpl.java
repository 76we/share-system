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
        // 目标合法性校验：targetType 只允许 1=动态 / 2=评论 / 3=其他，
        // 否则任何 targetType 都能凭空写一条关系记录
        if (targetId == null || targetId <= 0) {
            throw new IllegalArgumentException("点赞目标不合法");
        }
        if (targetType == null || targetType < 1 || targetType > 3) {
            throw new IllegalArgumentException("点赞类型不合法");
        }

        // redis key规范
        String relKey = "like:rel:" + targetType + ":" + targetId;
        String countKey = "like:count:" + targetType + ":" + targetId;

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(LIKE_LUA_SCRIPT, Long.class);
        Long res = redisTemplate.execute(redisScript, Arrays.asList(relKey, countKey), userId.toString());
        int op = res.intValue();

        // 异步落库到MySQL（不阻塞点赞接口）
        asyncExecutor.execute(() -> syncToDb(userId, targetId, targetType));
        return op;
    }

    /**
     * 以 Redis 为权威对账，而不是按这次 op 做增量。
     * 原实现按 op 决定 insert 还是 delete，但异步线程池不保证执行顺序：
     * 用户快速「赞 → 取消」时，两个任务可能乱序，DB 会停在和 Redis 相反的状态。
     * 改为：任务执行时重新读 Redis 当前关系，再把 DB 对齐过去 —— 乱序也能收敛。
     */
    private void syncToDb(Long userId, Long targetId, Integer targetType) {
        String relKey = "like:rel:" + targetType + ":" + targetId;
        boolean liked = Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(relKey, userId.toString()));

        LambdaQueryWrapper<UserLike> wrapper = Wrappers.lambdaQuery(UserLike.class)
                .eq(UserLike::getUserId, userId)
                .eq(UserLike::getTargetId, targetId)
                .eq(UserLike::getTargetType, targetType);

        if (liked) {
            Long exist = likeMapper.selectCount(wrapper);
            if (exist != null && exist > 0) {
                return; // 已存在，幂等跳过（不重复通知）
            }
            UserLike like = new UserLike();
            like.setUserId(userId);
            like.setTargetId(targetId);
            like.setTargetType(targetType);
            try {
                likeMapper.insert(like);
                // 首次写入成功才记通知（异步线程内，不阻塞接口）
                notificationService.onLike(userId, targetId, targetType);
            } catch (DuplicateKeyException e) {
                // 唯一索引冲突，代表已经存在，幂等，直接忽略
            }
        } else {
            likeMapper.delete(wrapper);
        }
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
