package com.we.sharesystem.service;

import com.we.sharesystem.common.BusinessException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;

/**
 * 邮箱验证码：生成、限流、发送、校验。
 * 验证码存 Redis（5 分钟有效），同一邮箱 60 秒只能发一次、每天最多 10 次。
 * 未配置 MAIL_USERNAME / MAIL_AUTH_CODE 时不真发邮件，只把验证码打到后端日志，方便本地联调。
 */
@Service
public class EmailCodeService {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

    /** 验证码有效期 */
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    /** 同一邮箱重发间隔 */
    private static final Duration RESEND_GAP = Duration.ofSeconds(60);
    /** 同一邮箱每日上限 */
    private static final int DAILY_LIMIT = 10;
    /**
     * 单个验证码允许的试错次数。
     * 6 位数字只有 100 万种组合，5 分钟内不限次数地试，脚本并发几十个请求就能撞开；
     * 超过次数直接把验证码作废，必须重新获取（重新获取又受 60 秒 + 每日上限约束）。
     */
    private static final int MAX_VERIFY_ATTEMPTS = 5;
    /** 场景：注册 */
    public static final String SCENE_REGISTER = "register";
    /** 场景：找回密码 */
    public static final String SCENE_RESET = "reset";

    private final SecureRandom random = new SecureRandom();

    @Autowired
    private StringRedisTemplate redis;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${spring.mail.password:}")
    private String mailPassword;

    @Value("${app.mail.from:}")
    private String mailFrom;

    @Value("${app.mail.from-name:尘叙阁}")
    private String fromName;

    /* ==================== 对外方法 ==================== */

    /**
     * 发送注册验证码。
     *
     * @return true=已真发邮件；false=未配置发件账号，验证码只打到后端日志（本地联调模式）
     */
    public boolean sendRegisterCode(String email) {
        return send(email, SCENE_REGISTER);
    }

    /** 校验并消费验证码（成功即失效） */
    public void verifyRegisterCode(String email, String code) {
        verify(email, SCENE_REGISTER, code, true);
    }

    /**
     * 发送找回密码验证码。
     *
     * @return true=已真发邮件；false=未配置发件账号，验证码只打到后端日志
     */
    public boolean sendResetCode(String email) {
        return send(email, SCENE_RESET);
    }

    public void verifyResetCode(String email, String code) {
        verify(email, SCENE_RESET, code, true);
    }

    /* ==================== 内部实现 ==================== */

    private boolean send(String email, String scene) {
        String to = normalize(email);

        // 1. 重发间隔
        String gapKey = "mail:gap:" + scene + ":" + to;
        Boolean first = redis.opsForValue().setIfAbsent(gapKey, "1", RESEND_GAP);
        if (Boolean.FALSE.equals(first)) {
            throw new BusinessException("请求过于频繁，请 60 秒后再试");
        }

        // 2. 每日上限
        String dayKey = "mail:day:" + scene + ":" + to + ":" + LocalDate.now();
        Long today = redis.opsForValue().increment(dayKey);
        if (today != null && today == 1L) {
            redis.expire(dayKey, Duration.ofDays(1));
        }
        if (today != null && today > DAILY_LIMIT) {
            redis.delete(gapKey);
            throw new BusinessException("该邮箱今日验证码次数已用完，请明天再试");
        }

        // 3. 生成并落库（覆盖旧码），同时把试错计数清零
        String code = String.format("%06d", random.nextInt(1_000_000));
        String codeKey = codeKey(scene, to);
        redis.opsForValue().set(codeKey, code, CODE_TTL);
        redis.delete(tryKey(scene, to));

        // 4. 发送
        if (canSend()) {
            try {
                sendMail(to, code, scene);
            } catch (Exception e) {
                redis.delete(codeKey);
                log.error("验证码邮件发送失败 to={}", to, e);
                throw new BusinessException("验证码发送失败，请稍后重试");
            }
            return true;
        }
        // 本地联调模式：没配发件账号，只打印，不真发
        log.warn("[本地联调] 邮件未配置，验证码 {} -> {}", code, to);
        return false;
    }

    private void verify(String email, String scene, String code, boolean consume) {
        String to = normalize(email);
        String key = codeKey(scene, to);
        String cached = redis.opsForValue().get(key);
        if (cached == null || cached.isBlank()) {
            throw new BusinessException("验证码已过期，请重新获取");
        }
        if (code == null || !cached.trim().equals(code.trim())) {
            // 记一次错误：超过上限就作废验证码，逼攻击者重新走「60 秒 + 每日 10 次」那一关
            String tryKey = tryKey(scene, to);
            Long fails = increase(tryKey, CODE_TTL);
            if (fails >= MAX_VERIFY_ATTEMPTS) {
                redis.delete(key);
                redis.delete(tryKey);
                throw new BusinessException("验证码错误次数过多，请重新获取");
            }
            throw new BusinessException("验证码不正确");
        }
        redis.delete(tryKey(scene, to));
        if (consume) {
            redis.delete(key);
        }
    }

    private String codeKey(String scene, String email) {
        return "mail:code:" + scene + ":" + email;
    }

    private String tryKey(String scene, String email) {
        return "mail:try:" + scene + ":" + email;
    }

    /** 计数器 +1，首次写入时设置过期时间（与验证码同寿命） */
    private Long increase(String key, Duration ttl) {
        try {
            Long value = redis.opsForValue().increment(key);
            if (value != null && value == 1L) {
                redis.expire(key, ttl);
            }
            return value == null ? 0L : value;
        } catch (Exception e) {
            log.warn("写入验证码试错计数失败 key={}: {}", key, e.getMessage());
            return 0L;
        }
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private boolean canSend() {
        return mailSender != null && !mailUsername.isBlank() && !mailPassword.isBlank();
    }

    private void sendMail(String to, String code, String scene) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        String from = mailFrom.isBlank() ? mailUsername : mailFrom;
        helper.setFrom(from, fromName);
        helper.setTo(to);
        boolean reset = SCENE_RESET.equals(scene);
        helper.setSubject(reset ? "尘叙阁 · 找回密码验证码" : "尘叙阁 · 注册验证码");
        helper.setText(html(code, reset), true);
        mailSender.send(message);
    }

    /** 古风邮件正文：暖墨底 + 青玉点缀 */
    private String html(String code, boolean reset) {
        String action = reset
                ? "你正在重置尘叙阁账号密码，请于 5 分钟内输入下方验证码。"
                : "你正在注册尘叙阁，请于 5 分钟内输入下方验证码。";
        return """
                <div style="background:#f6f1e8;padding:36px 0;font-family:'Songti SC','STSong',serif;">
                  <div style="max-width:520px;margin:0 auto;background:#1f1a16;border:1px solid #3a322a;border-radius:14px;padding:38px 34px;">
                    <div style="color:#8fd3c7;letter-spacing:10px;font-size:13px;">尘 叙 阁</div>
                    <div style="height:1px;background:linear-gradient(to right,#8fd3c7,transparent);margin:16px 0 24px;"></div>
                    <div style="color:#f3ebdf;font-size:18px;letter-spacing:2px;">阁中客，谨启：</div>
                    <div style="color:#b8a894;font-size:14px;line-height:1.9;margin-top:10px;">
                      偶拾尘间景，聊寄心上言。<br/>%s
                    </div>
                    <div style="margin:26px 0;text-align:center;">
                      <span style="display:inline-block;background:#2b241e;border:1px solid #8fd3c7;border-radius:10px;padding:14px 30px;color:#8fd3c7;font-size:30px;letter-spacing:10px;font-family:Consolas,monospace;">%s</span>
                    </div>
                    <div style="color:#7d6f61;font-size:12px;line-height:1.8;">
                      验证码 5 分钟内有效，请勿转发他人。<br/>若非本人操作，忽略此邮件即可。
                    </div>
                  </div>
                </div>
                """.formatted(action, code);
    }
}
