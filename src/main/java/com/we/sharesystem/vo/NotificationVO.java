package com.we.sharesystem.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NotificationVO {
    private Long id;
    private Integer type;
    private Long targetId;
    private String content;
    private Integer isRead;
    private LocalDateTime createTime;
    /** 发起人信息 */
    private Long actorId;
    private String actorName;
    private String actorAvatar;
}
