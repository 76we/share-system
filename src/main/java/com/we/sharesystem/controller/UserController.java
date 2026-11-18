package com.we.sharesystem.controller;

import com.we.sharesystem.common.Result;
import com.we.sharesystem.dto.LoginDTO;
import com.we.sharesystem.dto.RegisterDTO;
import com.we.sharesystem.service.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public Result<Void> register(@RequestBody @Valid RegisterDTO dto){
        userService.register(dto);
        return Result.ok(null);
    }

    @PostMapping("/login")
    public Result<String> login(@RequestBody @Valid LoginDTO dto){

        return Result.ok(userService.login(dto));
    }
}
