package com.we.sharesystem.common;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动自检：把「配置错了但还能跑起来」的坑提前变成一条明确的日志或一次启动失败。
 *
 * 三件事：
 *   1. 上传目录必须是绝对路径 → 否则直接启动失败（这条最容易被忽略，见下面注释）。
 *   2. 生产环境没配发件邮箱 → 直接启动失败。否则注册验证码只打到后端日志，
 *      用户永远收不到信，表现为「注册页面一直提示验证码错误」，线上极难定位。
 *   3. 用 dev profile 起服务 → 打一条醒目警告。dev 配置里带着默认数据库密码
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

    @Value("${file.upload-dir}")
    private String uploadDir;

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

    /**
     * 上传目录必须是绝对路径。这条不管什么 profile 都检查。
     *
     * 为什么值得为它专门失败一次：
     *   application.yml 里 upload-dir 的默认值是 D:/uploads/（本地开发用）。
     *   Windows 上它是绝对路径，一切正常；但部署到 Linux 后，如果忘了设
     *   UPLOAD_DIR 或忘了切 prod profile，Paths.get("D:/uploads/") 会变成一个
     *   相对路径，图片被写进工作目录下名为「D:」的文件夹。
     *
     *   结果是：上传接口返回成功、数据库记录正常、日志没有任何异常，
     *   而 nginx 去 /data/uploads 找文件找不到 —— 全站破图。
     *   这是那种能让人查一整天的问题，所以宁可在启动时就拦下来。
     *
     * 放在 @PostConstruct 而不是 ApplicationReadyEvent：后者要等 Tomcat
     * 已经监听端口才执行，配错时容器会「起来→退出→重启」反复循环刷日志。
     * 放在 bean 初始化阶段，Servlet 容器还没启动就会失败，日志干净。
     */
    @PostConstruct
    public void checkUploadDir() {
        Path path = Paths.get(uploadDir.trim());
        if (!path.isAbsolute()) {
            throw new IllegalStateException(
                    "UPLOAD_DIR 必须是绝对路径，当前值：" + uploadDir
                            + "。在 Linux 上写 Windows 盘符（D:/uploads/）会被当成相对路径，"
                            + "图片会写进工作目录下的 D: 文件夹，nginx 找不到文件，表现为全站破图且无任何报错。"
                            + "请在环境变量里设置为 /data/uploads 这类绝对路径。");
        }
    }
}
