package com.we.sharesystem.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 个人中心：用户资料 + 创作统计 */
@Data
public class UserProfileVO {
    private Long id;
    private String username;
    private String nickname;
    private String avatar;
    private String email;
    private String bio;
    private LocalDateTime createTime;

    /** 发布动态数 */
    private Long momentCount;
    /** 获赞数：我的动态收到的点赞总数 */
    private Long likeCount;
    /** 收藏数：我的动态被收藏的总数 */
    private Long collectCount;
}
