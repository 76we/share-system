package com.we.sharesystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification")
public class Notification {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 接收者 */
    private Long userId;
    /** 发起人 */
    private Long actorId;
    /** 1赞动态 2赞照片 3评论动态 4回复评论 */
    private Integer type;
    /** 动态id / 照片id */
    private Long targetId;
    private String content;
    /** 0未读 1已读 */
    private Integer isRead;
    private LocalDateTime createTime;
}
