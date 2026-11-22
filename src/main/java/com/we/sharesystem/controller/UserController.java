package com.we.sharesystem.controller;

import com.we.sharesystem.common.Result;
import com.we.sharesystem.dto.EmailCodeDTO;
import com.we.sharesystem.dto.LoginDTO;
import com.we.sharesystem.dto.RegisterDTO;
import com.we.sharesystem.dto.ResetPasswordDTO;
import com.we.sharesystem.service.EmailCodeService;
import com.we.sharesystem.service.UserService;
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

    @PostMapping("/login")
    public Result<String> login(@RequestBody @Valid LoginDTO dto){

        return Result.ok(userService.login(dto));
    }

}
