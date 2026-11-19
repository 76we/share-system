package com.we.sharesystem.controller;

import com.we.sharesystem.common.Result;
import com.we.sharesystem.dto.MomentDTO;
import com.we.sharesystem.service.MomentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/moments")
public class MomentController {

    @Autowired
    private MomentService momentService;

    @PostMapping
    public Result<Void> publish(@RequestBody @Valid MomentDTO dto, HttpServletRequest request){
        Long userId = (Long) request.getAttribute("userId");
        momentService.publish(dto,userId);
        return Result.ok(null);
    }
}
