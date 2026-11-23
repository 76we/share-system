package com.we.sharesystem.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 简单的固定窗口限流（Redis 计数）。
 *
 * 用于登录之外那批「未登录可调用」的接口：发验证码、注册、重置密码。
 * 这些接口原先只有「同一邮箱 60 秒一次 / 每天 10 次」的限制，
 * 换个邮箱就能刷，等于没有 —— 单 IP 不加闸门既能被人拿去压 SMTP 额度，
 * 也能把注册接口当成撞库入口。
 *
 * 与 LoginGuard 一样，Redis 故障时选择放行：宁可少一层防护，
 * 也不能因为缓存抖动把所有人挡在门外。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private final StringRedisTemplate redis;

    /**
     * @param key    限流维度，例如 "rl:email-code:1.2.3.4"
     * @param limit  窗口内允许的次数
     * @param window 窗口长度
     * @return true=放行；false=超限
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        try {
            Long current = redis.opsForValue().increment(key);
            if (current != null && current == 1L) {
                redis.expire(key, window);
            }
            return current == null || current <= limit;
        } catch (Exception e) {
            log.warn("限流计数失败，按放行处理 key={}: {}", key, e.getMessage());
            return true;
        }
    }

    /** 超限时统一抛业务异常，前端能拿到人能看懂的提示 */
    public void check(String key, int limit, Duration window, String message) {
        if (!tryAcquire(key, limit, window)) {
            throw new BusinessException(message);
        }
    }
}
