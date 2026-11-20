package com.we.sharesystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("moment_comment")
public class MomentComment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long momentId;

    private Long userId;

    private Long parentId;

    private Long replyUserId;

    private String content;

    private Integer likeCount;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

