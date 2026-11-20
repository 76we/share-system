package com.we.sharesystem.controller;

import com.we.sharesystem.common.Result;
import com.we.sharesystem.dto.CommentAddDTO;
import com.we.sharesystem.service.MomentCommentService;
import com.we.sharesystem.utils.JwtUtil;
import com.we.sharesystem.vo.CommentVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/comment")
public class CommentController {

    @Resource
    private MomentCommentService commentService;

    @Resource
    private JwtUtil jwtUtil;

    /**
     * 获取动态评论列表（树形）
     */
    @GetMapping("/list")
    public Result<List<CommentVO>> list(@RequestParam Long momentId) {
        return Result.ok(commentService.getCommentTree(momentId));
    }

    /**
     * 新增评论/回复
     */
    @PostMapping("/add")
    public Result<?> add(@RequestBody @Valid CommentAddDTO dto,
                         @RequestHeader("token") String token) {
        Long userId = jwtUtil.getUserId(token);
        commentService.addComment(dto, userId);
        return Result.ok("评论成功");
    }

    /**
     * 删除评论
     */
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id,
                            @RequestHeader("token") String token) {
        Long userId = jwtUtil.getUserId(token);
        commentService.deleteComment(id, userId);
        return Result.ok("删除成功");
    }
}
