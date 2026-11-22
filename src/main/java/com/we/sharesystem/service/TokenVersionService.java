package com.we.sharesystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.we.sharesystem.entity.User;
import com.we.sharesystem.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * token 版本号服务：让 JWT 变成"可撤销"的。
 *
 * JWT 一旦签发，服务端无法收回，默认要等 7 天过期。
 * 做法是在 user 表放一个 token_version，签发 token 时把它写进 claim；
 * 改密码 / 重置密码时 +1，旧 token 的 tv 立刻对不上，全部设备当场失效。
 *
 * 每次请求都查库太浪费，这里用 Redis 缓存当前版本号（30 分钟），
 * 版本变更时主动删缓存，所以下次请求就会读到新值。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TokenVersionService {

    private static final String KEY_PREFIX = "user:tv:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final UserMapper userMapper;

    /**
     * 当前有效的版本号。用户不存在返回 -1（与任何 token 里的版本号都不相等，等于判失效）。
     */
    public int current(Long userId) {
        if (userId == null) {
            return -1;
        }
        try {
            String cached = redis.opsForValue().get(KEY_PREFIX + userId);
            if (cached != null) {
                return Integer.parseInt(cached);
            }
        } catch (Exception e) {
            // Redis 挂了就退回查库，不能因为缓存故障把所有人挡在门外
            log.warn("读取 token 版本缓存失败 id={}: {}", userId, e.getMessage());
        }
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .select(User::getId, User::getTokenVersion)
                        .eq(User::getId, userId)
        );
        if (user == null) {
            return -1;
        }
        int version = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        try {
            redis.opsForValue().set(KEY_PREFIX + userId, String.valueOf(version), CACHE_TTL);
        } catch (Exception e) {
            log.warn("写入 token 版本缓存失败 id={}: {}", userId, e.getMessage());
        }
        return version;
    }

    /**
     * 版本号 +1：让该用户已签发的所有 token 失效。改密码、重置密码时必须调用。
     * 用 SQL 自增而不是"读出来再写"，避免并发改密时两个请求互相覆盖。
     */
    @Transactional(rollbackFor = Exception.class)
    public void bump(Long userId) {
        if (userId == null) {
            return;
        }
        userMapper.update(null,
                new LambdaUpdateWrapper<User>()
                        .eq(User::getId, userId)
                        .setSql("token_version = token_version + 1"));
        try {
            redis.delete(KEY_PREFIX + userId);
        } catch (Exception e) {
            log.warn("清理 token 版本缓存失败 id={}: {}", userId, e.getMessage());
        }
    }
}
