package com.we.sharesystem.service;

import com.we.sharesystem.dto.LoginDTO;
import com.we.sharesystem.dto.RegisterDTO;
import com.we.sharesystem.dto.ResetPasswordDTO;
import com.we.sharesystem.entity.User;

import java.util.Map;
import java.util.Set;


public interface UserService {
    void register(RegisterDTO dto);

    String login(LoginDTO dto);

    void resetPassword(ResetPasswordDTO dto);

    /** 发送找回密码验证码；邮箱未注册时直接报错，避免给不存在的人发信 */
    boolean sendPasswordCode(String email);

    Map<Long, User> getUserMapByIds(Set<Long> ids);
}
