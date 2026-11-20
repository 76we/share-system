package com.we.sharesystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CommentAddDTO {
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 500, message = "评论内容过长")
    private String content;

    @NotNull(message = "动态ID不能为空")
    private Long momentId;

    private Long parentId = 0L;

    private Long replyUserId;
}
