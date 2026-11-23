package com.we.sharesystem.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动自检：把「配置错了但还能跑起来」的坑提前变成一条明确的日志或一次启动失败。
 *
 * 两件事：
 *   1. 生产环境没配发件邮箱 → 直接启动失败。否则注册验证码只打到后端日志，
 *      用户永远收不到信，表现为「注册页面一直提示验证码错误」，线上极难定位。
 *   2. 用 dev profile 起服务 → 打一条醒目警告。dev 配置里带着默认数据库密码
 *      和仓库公开的 JWT 密钥，误当生产用等于把数据摆在明面上。
 */
@Slf4j
@Component
public class StartupCheck {

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${spring.mail.password:}")
    private String mailPassword;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    private final Environment environment;

    public StartupCheck(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        boolean prod = environment.matchesProfiles("prod");
        if (!prod) {
            log.warn("========================================================================");
            log.warn("当前 profile 不是 prod：使用的是本地开发配置（含默认数据库密码与公开 JWT 密钥）。");
            log.warn("生产部署请设置 SPRING_PROFILES_ACTIVE=prod 并通过环境变量注入密钥。");
            log.warn("========================================================================");
            return;
        }

        if (mailUsername.isBlank() || mailPassword.isBlank()) {
            // 故意让启动失败：注册/找回密码两条主链路都依赖它，带着这个状态上线等于注册功能不可用
            throw new IllegalStateException(
                    "生产环境未配置 MAIL_USERNAME / MAIL_AUTH_CODE：注册与找回密码验证码无法送达，用户无法注册。"
                            + "请在 share-system.env 中补齐发件账号后重启。");
        }
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET 长度不足 32 位，请用 openssl rand -base64 48 重新生成。");
        }
        log.info("生产环境自检通过：发件账号已配置，JWT 密钥长度 {} 位", jwtSecret.length());
    }
}
