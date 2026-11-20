package com.we.sharesystem.service;

import com.we.sharesystem.dto.CommentAddDTO;
import com.we.sharesystem.vo.CommentVO;

import java.util.List;

public interface MomentCommentService {
    /**
     * 新增评论/回复
     */
    void addComment(CommentAddDTO dto, Long loginUserId);

    /**
     * 获取动态评论树形列表
     */
    List<CommentVO> getCommentTree(Long momentId);

    /**
     * 逻辑删除评论
     */
    void deleteComment(Long commentId, Long loginUserId);
}
