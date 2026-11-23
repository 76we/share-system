package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.we.sharesystem.common.BusinessException;
import com.we.sharesystem.common.RelationCacheWarmer;
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
import java.util.List;

/**
 * 点赞服务：Redis Lua 原子切换 + 异步落库 MySQL。
 *
 * 冷缓存处理见 {@link RelationCacheWarmer}：计数与关系集合都会在首次访问时按数据库现状回填，
 * 避免「Redis 重启后计数从 1 开始、老用户重复点赞导致计数永久漂移」。
 */
@Service
@RequiredArgsConstructor
public class LikeServiceImpl implements LikeService {

    private static final String REL_PREFIX = "like:rel:";
    private static final String COUNT_PREFIX = "like:count:";
    private static final String SEED_PREFIX = "like:seed:";

    // Lua脚本：原子执行点赞/取消，防止并发超赞
    private static final DefaultRedisScript<Long> LIKE_SCRIPT = new DefaultRedisScript<>("""
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
            """, Long.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final UserLikeMapper likeMapper;
    private final ThreadPoolTaskExecutor asyncExecutor;
    private final NotificationService notificationService;
    private final RelationCacheWarmer cacheWarmer;

    @Override
    public Integer like(Long userId, Long targetId, Integer targetType) {
        // 目标合法性校验：targetType 只允许 1=动态 / 2=评论 / 3=照片，
        // 否则任何 targetType 都能凭空写一条关系记录
        if (targetId == null || targetId <= 0) {
            throw new IllegalArgumentException("点赞目标不合法");
        }
        if (targetType == null || targetType < 1 || targetType > 3) {
            throw new IllegalArgumentException("点赞类型不合法");
        }

        String relKey = relKey(targetType, targetId);
        String countKey = countKey(targetType, targetId);
        // 切换之前先确保缓存与数据库一致，否则冷缓存下的「点赞」其实是在给老数据重新计数
        warmUp(targetType, targetId, relKey, countKey);

        Long res = redisTemplate.execute(LIKE_SCRIPT, Arrays.asList(relKey, countKey), userId.toString());
        if (res == null) {
            throw new BusinessException("点赞失败，请稍后重试");
        }
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
        String relKey = relKey(targetType, targetId);
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
        String relKey = relKey(targetType, targetId);
        if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(relKey, userId.toString()))) {
            return true;
        }
        // 未命中不等于「没点过」：可能是冷缓存，回填后再判一次
        warmUp(targetType, targetId, relKey, countKey(targetType, targetId));
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(relKey, userId.toString()));
    }

    @Override
    public Long getLikeCount(Long targetId, Integer targetType) {
        String countKey = countKey(targetType, targetId);
        Object val = redisTemplate.opsForValue().get(countKey);
        if (val == null) {
            // 缓存没有 → 先按数据库回填（计数与关系集合一起），再取
            warmUp(targetType, targetId, relKey(targetType, targetId), countKey);
            val = redisTemplate.opsForValue().get(countKey);
        }
        if (val != null) {
            try {
                return Long.parseLong(val.toString());
            } catch (NumberFormatException ignored) {
                // 脏值（例如被手工写坏）当作缺失处理，走下面的回源
            }
        }
        return likeMapper.selectCount(Wrappers.lambdaQuery(UserLike.class)
                .eq(UserLike::getTargetId, targetId)
                .eq(UserLike::getTargetType, targetType));
    }

    private String relKey(Integer targetType, Long targetId) {
        return REL_PREFIX + targetType + ":" + targetId;
    }

    private String countKey(Integer targetType, Long targetId) {
        return COUNT_PREFIX + targetType + ":" + targetId;
    }

    /** 首次访问该目标（或缓存被清）时，按数据库现状回填计数与点赞关系 */
    private void warmUp(Integer targetType, Long targetId, String relKey, String countKey) {
        cacheWarmer.ensure(relKey, countKey, SEED_PREFIX + targetType + ":" + targetId,
                () -> likeMapper.selectCount(Wrappers.lambdaQuery(UserLike.class)
                        .eq(UserLike::getTargetId, targetId)
                        .eq(UserLike::getTargetType, targetType)),
                () -> likerIds(targetId, targetType));
    }

    private List<Long> likerIds(Long targetId, Integer targetType) {
        return likeMapper.selectList(Wrappers.<UserLike>lambdaQuery()
                        .select(UserLike::getUserId)
                        .eq(UserLike::getTargetId, targetId)
                        .eq(UserLike::getTargetType, targetType))
                .stream()
                .map(UserLike::getUserId)
                .toList();
    }
}
