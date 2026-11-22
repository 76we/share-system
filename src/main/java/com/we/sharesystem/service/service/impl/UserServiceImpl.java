package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.we.sharesystem.common.BusinessException;
import com.we.sharesystem.dto.LoginDTO;
import com.we.sharesystem.dto.RegisterDTO;
import com.we.sharesystem.dto.ResetPasswordDTO;
import com.we.sharesystem.entity.User;
import com.we.sharesystem.mapper.UserMapper;
import com.we.sharesystem.service.EmailCodeService;
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
    public String login(LoginDTO dto) {        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername,dto.getUsername())
        );
        if(user==null || !encoder.matches(dto.getPassword(),user.getPassword())){
            throw new BusinessException("用户名或密码错误");
        }
        return jwtUtils.generateToken(user.getId());
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
