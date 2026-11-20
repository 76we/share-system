package com.we.sharesystem.service;

import com.we.sharesystem.dto.LoginDTO;
import com.we.sharesystem.dto.RegisterDTO;
import com.we.sharesystem.entity.User;

import java.util.Map;
import java.util.Set;


public interface UserService {
    void register(RegisterDTO dto);

    String login(LoginDTO dto);

    Map<Long, User> getUserMapByIds(Set<Long> ids);
}
