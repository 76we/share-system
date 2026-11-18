package com.we.sharesystem.service;

import com.we.sharesystem.dto.LoginDTO;
import com.we.sharesystem.dto.RegisterDTO;
import jakarta.validation.Valid;

public interface UserService {
    void register(RegisterDTO dto);

    String login(LoginDTO dto);
}
