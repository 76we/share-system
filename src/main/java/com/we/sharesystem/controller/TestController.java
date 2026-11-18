package com.we.sharesystem.controller;

import com.we.sharesystem.common.Result;
import com.we.sharesystem.entity.User;
import com.we.sharesystem.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/users")
public class TestController {
    @Autowired
    private UserMapper userMapper;
    @GetMapping("/test")
    public Result<List<User>> test(){
        return Result.ok(userMapper.selectList(null));
    }
}
