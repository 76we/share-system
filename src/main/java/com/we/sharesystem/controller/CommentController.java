package com.we.sharesystem.controller;

import com.we.sharesystem.common.Result;
import com.we.sharesystem.dto.CommentAddDTO;
import com.we.sharesystem.service.MomentCommentService;
import com.we.sharesystem.vo.CommentVO;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 评论接口。
 * 路径统一为 /comments（与其它 Controller 一致，不再带 /api 前缀，
 * 由前端 baseURL '/api' + vite/nginx 转发剥掉一层）。
 * userId 一律从拦截器写入的 request attribute 取，不再各自解析 token。
 */
@RestController
@RequestMapping("/comments")
public class CommentController {

    @Resource
    private MomentCommentService commentService;

    /**
     * 获取动态评论列表（树形）
     */
    @GetMapping
    public Result<List<CommentVO>> list(@RequestParam Long momentId) {
        return Result.ok(commentService.getCommentTree(momentId));
    }

    /**
     * 新增评论/回复
     */
    @PostMapping
    public Result<?> add(@RequestBody @Valid CommentAddDTO dto, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        commentService.addComment(dto, userId);
        return Result.ok("评论成功");
    }

    /**
     * 删除评论
     */
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        commentService.deleteComment(id, userId);
        return Result.ok("删除成功");
    }
}
