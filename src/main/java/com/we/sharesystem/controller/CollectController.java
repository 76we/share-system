package com.we.sharesystem.controller;

import com.we.sharesystem.common.Result;
import com.we.sharesystem.service.CollectService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/collects")
@RequiredArgsConstructor
public class CollectController {

    private final CollectService collectService;

    /**
     * 收藏 / 取消收藏
     * userId 由 JWT 拦截器从 token 解析后放入 request attribute，前端不传参
     */
    @PostMapping
    public Result<Integer> collect(@RequestParam Long momentId,
                                   HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(collectService.collect(userId, momentId));
    }

    /** 查询是否收藏（需登录） */
    @GetMapping("/state")
    public Result<Boolean> getCollectState(@RequestParam Long momentId,
                                           HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(collectService.hasCollect(userId, momentId));
    }

    /** 查询收藏数量（公开） */
    @GetMapping("/count")
    public Result<Long> getCollectCount(@RequestParam Long momentId) {
        return Result.ok(collectService.getCollectCount(momentId));
    }
}
