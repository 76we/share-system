package com.we.sharesystem.controller;

import com.we.sharesystem.common.IpUtil;
import com.we.sharesystem.common.RateLimiter;
import com.we.sharesystem.common.Result;
import com.we.sharesystem.dto.EmailCodeDTO;
import com.we.sharesystem.dto.LoginDTO;
import com.we.sharesystem.dto.RegisterDTO;
import com.we.sharesystem.dto.ResetPasswordDTO;
import com.we.sharesystem.service.EmailCodeService;
import com.we.sharesystem.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/users")
public class UserController {

    /** 同一 IP 每小时可请求的验证码数量（防止换邮箱刷、把 SMTP 额度打爆） */
    private static final int CODE_PER_IP = 10;
    /** 同一 IP 每小时可注册/重置密码的次数 */
    private static final int AUTH_PER_IP = 20;
    private static final Duration ONE_HOUR = Duration.ofHours(1);

    @Autowired
    private UserService userService;

    @Autowired
    private EmailCodeService emailCodeService;

    @Autowired
    private RateLimiter rateLimiter;

    @PostMapping("/register")
    public Result<Void> register(@RequestBody @Valid RegisterDTO dto, HttpServletRequest request) {
        rateLimiter.check(ipKey("register", request), AUTH_PER_IP, ONE_HOUR,
                "操作过于频繁，请稍后再试");
        userService.register(dto);
        return Result.ok(null);
    }

    /** 发送注册邮箱验证码（6 位，5 分钟有效，60 秒可重发一次）。返回 true 表示邮件已真发，false 表示未配置发件账号 */
    @PostMapping("/email-code")
    public Result<Boolean> sendEmailCode(@RequestBody @Valid EmailCodeDTO dto, HttpServletRequest request) {
        rateLimiter.check(ipKey("email-code", request), CODE_PER_IP, ONE_HOUR,
                "验证码请求过于频繁，请稍后再试");
        return Result.ok(emailCodeService.sendRegisterCode(dto.getEmail()));
    }

    /** 发送找回密码验证码（6 位，5 分钟有效）。返回 true 表示邮件已真发 */
    @PostMapping("/password-code")
    public Result<Boolean> sendPasswordCode(@RequestBody @Valid EmailCodeDTO dto, HttpServletRequest request) {
        rateLimiter.check(ipKey("password-code", request), CODE_PER_IP, ONE_HOUR,
                "验证码请求过于频繁，请稍后再试");
        return Result.ok(userService.sendPasswordCode(dto.getEmail()));
    }

    /** 用邮箱验证码重置密码，成功后验证码失效 */
    @PostMapping("/reset-password")
    public Result<Void> resetPassword(@RequestBody @Valid ResetPasswordDTO dto, HttpServletRequest request) {
        rateLimiter.check(ipKey("reset-password", request), AUTH_PER_IP, ONE_HOUR,
                "操作过于频繁，请稍后再试");
        userService.resetPassword(dto);
        return Result.ok(null);
    }

    /**
     * 登录：返回 token。失败次数按「用户名 + 来源 IP」双重计数，
     * 连续失败过多会被锁定 15 分钟（见 LoginGuard）。
     * 来源 IP 一律通过 IpUtil 取（X-Real-IP 优先），否则伪造 XFF 就能绕过锁定。
     */
    @PostMapping("/login")
    public Result<String> login(@RequestBody @Valid LoginDTO dto, HttpServletRequest request) {
        return Result.ok(userService.login(dto, IpUtil.clientIp(request)));
    }

    private String ipKey(String scene, HttpServletRequest request) {
        return "rl:" + scene + ":" + IpUtil.clientIp(request);
    }
}
