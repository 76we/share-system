package com.we.sharesystem.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 照片墙中的一张照片。
 * 归属不新增字段：由来源动态 moment.userId 推导，即"我的墙"只含我发布的动态图片。
 */
@Data
public class WallPhotoVO {
    /** 图片 id（moment_image.id），点赞时作为 targetId，targetType=3 */
    private Long id;
    /** 来源动态 id，点击图片可跳回原动态 */
    private Long momentId;
    private String url;
    /** 图片说明，取自来源动态的标题（无标题则用正文） */
    private String caption;
    /** 来源动态发布时间，供"那年今日"等召回功能使用 */
    private LocalDateTime createTime;
    /** 点赞数（targetType=3） */
    private Integer likeCount;
    /** 当前登录用户是否已点赞 */
    private Boolean liked;
    /** "那年今日"专用：距今几年（1=去年的今天）。普通照片墙为 null */
    private Integer yearsAgo;
}
