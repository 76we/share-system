package com.we.sharesystem.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * 点赞/收藏的 Redis 冷启动预热。
 *
 * 这套缓存以 Redis 为权威（Lua 原子切换 + 异步落库），但从来没从 MySQL 回填过：
 * Redis 一重启，like:count 里没有这个 key，下一个人点赞时 INCR 直接把计数写成 1，
 * 原本 300 个赞的帖子瞬间变 1 个，而且再也涨不回真实值；
 * like:rel 同时丢失，老用户看到的是「未点赞」，他再点一次会被唯一索引挡回数据库，
 * 但 Redis 计数已经 +1 —— 这就是「计数永久漂移」的成因。
 *
 * 这里做一次性回填：种子标记存在就认为该目标已按数据库现状对齐过，
 * 标记不存在才回源（计数缺失时才查 count，关系集合整批 SADD），
 * 正常请求只多一次 EXISTS。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RelationCacheWarmer {

    /** 种子标记有效期：到期后重新对齐一次，避免 Redis 淘汰造成的长期偏差 */
    private static final Duration SEED_TTL = Duration.ofHours(24);
    /** 计数与关系集合的有效期，到期后由下次访问重新预热，防止内存无限增长 */
    private static final Duration KEY_TTL = Duration.ofDays(7);
    /** 单次回填的最大成员数：极端热门目标不把整份关系塞进内存 */
    private static final int MAX_SEED_MEMBERS = 5000;

    private final RedisTemplate<String, Object> redis;

    public void ensure(String relKey, String countKey, String seedKey,
                       Supplier<Long> dbCount, Supplier<List<Long>> dbUserIds) {
        try {
            if (Boolean.TRUE.equals(redis.hasKey(seedKey))) {
                return;
            }
            // 抢占种子标记：并发请求只有一个真正回源
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(seedKey, "1", SEED_TTL))) {
                return;
            }
            if (!Boolean.TRUE.equals(redis.hasKey(countKey))) {
                Long count = dbCount.get();
                redis.opsForValue().setIfAbsent(countKey, count == null ? 0L : count, KEY_TTL);
            }
            List<Long> ids = dbUserIds.get();
            if (ids != null && !ids.isEmpty()) {
                String[] members = ids.stream()
                        .limit(MAX_SEED_MEMBERS)
                        .map(String::valueOf)
                        .toArray(String[]::new);
                redis.opsForSet().add(relKey, members);
                redis.expire(relKey, KEY_TTL);
            }
        } catch (Exception e) {
            // 回源失败：把标记删掉，下次访问再试，不能留下「标记在但数据没灌」的假预热状态
            try {
                redis.delete(seedKey);
            } catch (Exception ignored) {
                // Redis 整体不可用，删标记也没意义
            }
            log.warn("点赞/收藏缓存预热失败 relKey={}: {}", relKey, e.getMessage());
        }
    }
}
