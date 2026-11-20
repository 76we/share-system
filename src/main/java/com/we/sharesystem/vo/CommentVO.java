package com.we.sharesystem.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class CommentVO {
    // 评论基础字段
    private Long id;
    private Long momentId;
    private Long parentId;
    private Long replyUserId;
    private String content;
    private Integer likeCount;
    private LocalDateTime createTime;

    // 评论人信息
    private Long commentUserId;
    private String commentUsername;
    private String commentAvatar;

    // 被回复人信息
    private String replyUsername;

    // 子评论列表
    private List<CommentVO> children = new ArrayList<>();
}
