package com.we.sharesystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户收藏关系（目前只收藏动态 moment） */
@Data
@TableName("user_collect")
public class UserCollect {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long momentId;
    private LocalDateTime createTime;
}
