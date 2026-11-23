package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.we.sharesystem.common.BusinessException;
import com.we.sharesystem.common.RelationCacheWarmer;
import com.we.sharesystem.entity.UserCollect;
import com.we.sharesystem.mapper.UserCollectMapper;
import com.we.sharesystem.service.CollectService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 收藏服务：与点赞同一套模式——Redis Lua 原子切换 + 异步落库 MySQL。
 * 收藏只针对动态(moment)，没有 targetType 维度。
 *
 * 冷缓存回填同样走 {@link RelationCacheWarmer}，见 LikeServiceImpl 的说明。
 */
@Service
@RequiredArgsConstructor
public class CollectServiceImpl implements CollectService {

    private static final String REL_PREFIX = "collect:rel:";
    private static final String COUNT_PREFIX = "collect:count:";
    private static final String SEED_PREFIX = "collect:seed:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final UserCollectMapper collectMapper;
    private final ThreadPoolTaskExecutor asyncExecutor;
    private final RelationCacheWarmer cacheWarmer;

    // Lua：原子执行收藏/取消，防止并发重复收藏
    private static final DefaultRedisScript<Long> COLLECT_SCRIPT = new DefaultRedisScript<>("""
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

    @Override
    public Integer collect(Long userId, Long momentId) {
        if (momentId == null || momentId <= 0) {
            throw new IllegalArgumentException("收藏目标不合法");
        }
        String relKey = relKey(momentId);
        String countKey = countKey(momentId);
        warmUp(momentId, relKey, countKey);

        Long res = redisTemplate.execute(COLLECT_SCRIPT, Arrays.asList(relKey, countKey), userId.toString());
        if (res == null) {
            throw new BusinessException("收藏失败，请稍后重试");
        }
        int op = res.intValue();

        // 异步落库，不阻塞接口
        asyncExecutor.execute(() -> syncToDb(userId, momentId));
        return op;
    }

    /**
     * 以 Redis 为权威对账（与点赞同款）。
     * 原来按 op 决定 insert / delete，异步线程池不保证顺序：
     * 快速「收藏 → 取消」会乱序执行，DB 可能停在和 Redis 相反的状态，
     * 表现就是「取消收藏了，刷新后又出现」。改为任务执行时重读 Redis 现状再对齐。
     */
    private void syncToDb(Long userId, Long momentId) {
        String relKey = relKey(momentId);
        boolean collected = Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(relKey, userId.toString()));

        LambdaQueryWrapper<UserCollect> wrapper = Wrappers.lambdaQuery(UserCollect.class)
                .eq(UserCollect::getUserId, userId)
                .eq(UserCollect::getMomentId, momentId);

        if (collected) {
            Long exist = collectMapper.selectCount(wrapper);
            if (exist != null && exist > 0) {
                return; // 已存在，幂等跳过
            }
            UserCollect c = new UserCollect();
            c.setUserId(userId);
            c.setMomentId(momentId);
            try {
                collectMapper.insert(c);
            } catch (DuplicateKeyException e) {
                // 唯一索引冲突=已存在，幂等忽略
            }
        } else {
            collectMapper.delete(wrapper);
        }
    }

    @Override
    public boolean hasCollect(Long userId, Long momentId) {
        String relKey = relKey(momentId);
        if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(relKey, userId.toString()))) {
            return true;
        }
        warmUp(momentId, relKey, countKey(momentId));
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(relKey, userId.toString()));
    }

    @Override
    public Long getCollectCount(Long momentId) {
        String countKey = countKey(momentId);
        Object val = redisTemplate.opsForValue().get(countKey);
        if (val == null) {
            // 缓存未命中 → 按数据库回填（计数 + 关系集合），再取
            warmUp(momentId, relKey(momentId), countKey);
            val = redisTemplate.opsForValue().get(countKey);
        }
        if (val != null) {
            try {
                return Long.parseLong(val.toString());
            } catch (NumberFormatException ignored) {
                // 脏值当作缺失，走下面的回源
            }
        }
        return collectMapper.selectCount(Wrappers.lambdaQuery(UserCollect.class)
                .eq(UserCollect::getMomentId, momentId));
    }

    private String relKey(Long momentId) {
        return REL_PREFIX + momentId;
    }

    private String countKey(Long momentId) {
        return COUNT_PREFIX + momentId;
    }

    /** 首次访问该动态（或缓存被清）时，按数据库现状回填计数与收藏关系 */
    private void warmUp(Long momentId, String relKey, String countKey) {
        cacheWarmer.ensure(relKey, countKey, SEED_PREFIX + momentId,
                () -> collectMapper.selectCount(Wrappers.lambdaQuery(UserCollect.class)
                        .eq(UserCollect::getMomentId, momentId)),
                () -> collectorIds(momentId));
    }

    private List<Long> collectorIds(Long momentId) {
        return collectMapper.selectList(Wrappers.<UserCollect>lambdaQuery()
                        .select(UserCollect::getUserId)
                        .eq(UserCollect::getMomentId, momentId))
                .stream()
                .map(UserCollect::getUserId)
                .toList();
    }
}
