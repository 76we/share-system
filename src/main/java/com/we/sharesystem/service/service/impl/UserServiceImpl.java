package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.we.sharesystem.common.BusinessException;
import com.we.sharesystem.common.LoginGuard;
import com.we.sharesystem.dto.LoginDTO;
import com.we.sharesystem.dto.RegisterDTO;
import com.we.sharesystem.dto.ResetPasswordDTO;
import com.we.sharesystem.entity.User;
import com.we.sharesystem.mapper.UserMapper;
import com.we.sharesystem.service.EmailCodeService;
import com.we.sharesystem.service.TokenVersionService;
import com.we.sharesystem.service.UserService;
import com.we.sharesystem.utils.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserServiceImpl implements UserService {


    @Autowired
    private UserMapper userMapper;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    @Autowired
    private JwtUtil jwtUtils;

    @Autowired
    private EmailCodeService emailCodeService;

    @Autowired
    private LoginGuard loginGuard;

    @Autowired
    private TokenVersionService tokenVersionService;

    @Override
    public void register(RegisterDTO dto) {
        String username = dto.getUsername().trim();
        String email = dto.getEmail().trim().toLowerCase();
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (count != null && count > 0) {
            throw new BusinessException("用户名已存在");
        }
        Long emailCount = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)
        );
        if (emailCount != null && emailCount > 0) {
            throw new BusinessException("该邮箱已被注册");
        }
        // 校验并消费验证码，通过后即失效
        emailCodeService.verifyRegisterCode(email, dto.getEmailCode());

        User user = new User();
        user.setUsername(username);
        user.setPassword(encoder.encode(dto.getPassword()));
        user.setNickname(username);
        user.setEmail(email);
        userMapper.insert(user);
    }

    @Override
    public String login(LoginDTO dto, String ip) {
        String username = dto.getUsername() == null ? "" : dto.getUsername().trim();
        String clientIp = ip == null ? "unknown" : ip;
        // 先过限流闸门：处于锁定期就拒绝，连"密码对不对"都不告诉他
        loginGuard.check(username, clientIp);
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (user == null || !encoder.matches(dto.getPassword(), user.getPassword())) {
            // 用户不存在也计入失败，避免拿响应差异枚举账号是否存在
            loginGuard.fail(username, clientIp);
            throw new BusinessException("用户名或密码错误");
        }
        // 密码对了还要看账号状态：user.status 0=禁用。
        // 原先这张字段没人读，等于「封号」只能靠改库 + 改密码，运营上没有手段。
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new BusinessException("该账号已被禁用，如有疑问请联系管理员");
        }
        loginGuard.success(username, clientIp);
        // token 里带上当前密码版本号：以后改密码时版本 +1，这个 token 自动作废
        return jwtUtils.generateToken(user.getId(),
                user.getTokenVersion() == null ? 0 : user.getTokenVersion());
    }

    @Override
    public boolean sendPasswordCode(String email) {
        String mail = email.trim().toLowerCase();
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getEmail, mail)
        );
        if (count == null || count == 0) {
            throw new BusinessException("该邮箱未注册");
        }
        return emailCodeService.sendResetCode(mail);
    }

    @Override
    public void resetPassword(ResetPasswordDTO dto) {
        String email = dto.getEmail().trim().toLowerCase();
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email)
        );
        if (user == null) {
            throw new BusinessException("该邮箱未注册");
        }
        // 校验并消费验证码
        emailCodeService.verifyResetCode(email, dto.getCode());
        user.setPassword(encoder.encode(dto.getNewPassword()));
        userMapper.updateById(user);
        // 密码变了：旧 token 全部作废（可能是被别人用验证码改的，这时把原主人的登录态踢掉更安全）
        tokenVersionService.bump(user.getId());
    }

    @Override
    public Map<Long, User> getUserMapByIds(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new HashMap<>();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }
}
