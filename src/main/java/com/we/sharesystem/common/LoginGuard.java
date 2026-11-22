package com.we.sharesystem.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败限流：挡住密码暴力破解。
 *
 * 两道闸门：
 *   ① 同一用户名连续失败 5 次 → 锁 15 分钟（防定向爆破）
 *   ② 同一 IP 连续失败 20 次 → 锁 15 分钟（防拿一个字典轮着试不同账号）
 * 计数窗口同样是 15 分钟，登录成功即清零，避免正常人误输几次(password 打错)就被锁。
 *
 * 状态全在 Redis，重启不丢；Redis 故障时降级为"只记日志不拦人"，
 * 宁可少一层防护也不能让所有人登不进来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginGuard {

    private static final int MAX_FAIL_PER_USER = 5;
    private static final int MAX_FAIL_PER_IP = 20;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private static final String USER_FAIL = "login:fail:user:";
    private static final String IP_FAIL = "login:fail:ip:";
    private static final String USER_LOCK = "login:lock:user:";
    private static final String IP_LOCK = "login:lock:ip:";

    private final StringRedisTemplate redis;

    /** 登录前调用：还在锁定期就直接拒绝，不给验证密码的机会 */
    public void check(String username, String ip) {
        long userRemain = remaining(USER_LOCK + username);
        if (userRemain > 0) {
            throw new BusinessException("登录失败次数过多，请 " + minutes(userRemain) + " 分钟后再试");
        }
        long ipRemain = remaining(IP_LOCK + ip);
        if (ipRemain > 0) {
            throw new BusinessException("当前网络登录失败次数过多，请 " + minutes(ipRemain) + " 分钟后再试");
        }
    }

    /** 登录成功后清零，避免"输错 4 次 + 登进去"之后仍然悬在边缘 */
    public void success(String username, String ip) {
        try {
            redis.delete(USER_FAIL + username);
            redis.delete(IP_FAIL + ip);
        } catch (Exception e) {
            log.warn("清理登录失败计数失败: {}", e.getMessage());
        }
    }

    /** 登录失败后调用：累加计数，达到阈值就上锁 */
    public void fail(String username, String ip) {
        long userFails = increase(USER_FAIL + username);
        long ipFails = increase(IP_FAIL + ip);
        if (userFails >= MAX_FAIL_PER_USER) {
            lock(USER_LOCK + username);
            clear(USER_FAIL + username);
            log.warn("账号 {} 连续登录失败 {} 次，锁定 15 分钟（来源 IP {}）", username, userFails, ip);
        }
        if (ipFails >= MAX_FAIL_PER_IP) {
            lock(IP_LOCK + ip);
            clear(IP_FAIL + ip);
            log.warn("IP {} 连续登录失败 {} 次，锁定 15 分钟", ip, ipFails);
        }
    }

    private long increase(String key) {
        try {
            Long value = redis.opsForValue().increment(key);
            if (value != null && value == 1L) {
                redis.expire(key, WINDOW);
            }
            return value == null ? 0 : value;
        } catch (Exception e) {
            log.warn("累加登录失败计数失败 key={}: {}", key, e.getMessage());
            return 0;
        }
    }

    private void lock(String key) {
        try {
            redis.opsForValue().set(key, String.valueOf(System.currentTimeMillis()), WINDOW);
        } catch (Exception e) {
            log.warn("写入登录锁定标记失败 key={}: {}", key, e.getMessage());
        }
    }

    private void clear(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("清理登录计数失败 key={}: {}", key, e.getMessage());
        }
    }

    private long remaining(String key) {
        try {
            Long ttl = redis.getExpire(key);
            return ttl == null ? 0 : ttl;
        } catch (Exception e) {
            log.warn("读取锁定剩余时间失败 key={}: {}", key, e.getMessage());
            return 0;
        }
    }

    /** 剩余秒数换算成"至少 1 分钟"，免得提示"请 0 分钟后再试" */
    private long minutes(long seconds) {
        return Math.max(1, (seconds + 59) / 60);
    }
}
