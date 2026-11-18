package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.we.sharesystem.common.BusinessException;
import com.we.sharesystem.dto.LoginDTO;
import com.we.sharesystem.dto.RegisterDTO;
import com.we.sharesystem.entity.User;
import com.we.sharesystem.mapper.UserMapper;
import com.we.sharesystem.service.UserService;
import com.we.sharesystem.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {


    @Autowired
    private UserMapper userMapper;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    @Autowired
    private JwtUtils jwtUtils;

    @Override
    public void register(RegisterDTO dto) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername,dto.getUsername())
        );
        if (count!=null && count>0){
            throw new BusinessException("用户名已存在");
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(encoder.encode(dto.getPassword()));
        user.setNickname(dto.getUsername());
        userMapper.insert(user);


    }

    @Override
    public String login(LoginDTO dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername,dto.getUsername())
        );
        if(user==null || !encoder.matches(dto.getPassword(),user.getPassword())){
            throw new BusinessException("用户名或密码错误");
        }
        return jwtUtils.generateToken(user.getId());
    }
}
