package com.we.sharesystem.controller;

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

@RestController
@RequestMapping("/users")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private EmailCodeService emailCodeService;

    @PostMapping("/register")
    public Result<Void> register(@RequestBody @Valid RegisterDTO dto){
        userService.register(dto);
        return Result.ok(null);
    }

    /** 发送注册邮箱验证码（6 位，5 分钟有效，60 秒可重发一次）。返回 true 表示邮件已真发，false 表示未配置发件账号 */
    @PostMapping("/email-code")
    public Result<Boolean> sendEmailCode(@RequestBody @Valid EmailCodeDTO dto){
        return Result.ok(emailCodeService.sendRegisterCode(dto.getEmail()));
    }

    /** 发送找回密码验证码（6 位，5 分钟有效）。返回 true 表示邮件已真发 */
    @PostMapping("/password-code")
    public Result<Boolean> sendPasswordCode(@RequestBody @Valid EmailCodeDTO dto){
        return Result.ok(userService.sendPasswordCode(dto.getEmail()));
    }

    /** 用邮箱验证码重置密码，成功后验证码失效 */
    @PostMapping("/reset-password")
    public Result<Void> resetPassword(@RequestBody @Valid ResetPasswordDTO dto){
        userService.resetPassword(dto);
        return Result.ok(null);
    }

    /**
     * 登录：返回 token。失败次数按「用户名 + 来源 IP」双重计数，
     * 连续失败过多会被锁定 15 分钟（见 LoginGuard）。
     */
    @PostMapping("/login")
    public Result<String> login(@RequestBody @Valid LoginDTO dto, HttpServletRequest request){
        return Result.ok(userService.login(dto, clientIp(request)));
    }

    /** 取真实来源 IP：nginx 反代后 remoteAddr 是 127.0.0.1，需要读 X-Forwarded-For 的第一段 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        String ip = (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
        // 本地联调时 IPv6 回环统一成 127.0.0.1，避免 ::1 与 127.0.0.1 被当成两个 IP 计数
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }

}
