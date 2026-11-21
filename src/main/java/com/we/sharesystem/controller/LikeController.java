package com.we.sharesystem.controller;


import com.we.sharesystem.common.Result;
import com.we.sharesystem.service.LikeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/likes")
@RequiredArgsConstructor
public class LikeController {

    private final LikeService likeService;

    /**
     * 点赞 / 切换点赞状态
     * @param targetId 帖子/评论id
     * @param targetType 1分享 2评论
     * userId 由 JWT 拦截器从 token 解析后放入 request attribute，前端不传参
     */
    @PostMapping
    public Result<Integer> like(@RequestParam Long targetId,
                                @RequestParam Integer targetType,
                                HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        Integer res = likeService.like(userId, targetId, targetType);
        return Result.ok(res);
    }

    /**
     * 查询是否点赞（需登录）
     */
    @GetMapping("/state")
    public Result<Boolean> getLikeState(@RequestParam Long targetId,
                                        @RequestParam Integer targetType,
                                        HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        boolean flag = likeService.hasLike(userId, targetId, targetType);
        return Result.ok(flag);
    }

    /**
     * 查询点赞数量
     */
    @GetMapping("/count")
    public Result<Long> getLikeCount(@RequestParam Long targetId,
                                     @RequestParam Integer targetType) {
        Long count = likeService.getLikeCount(targetId, targetType);
        return Result.ok(count);
    }
}
