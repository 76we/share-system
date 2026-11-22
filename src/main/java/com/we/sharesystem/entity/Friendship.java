package com.we.sharesystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 好友关系（双向对称）。
 * 约定：user_a < user_b，用 (user_a, user_b) 唯一，天然无方向、无重复。
 * from_user 记录是谁发起的申请，用于"同意/拒绝"和"我发出的申请"。
 */
@Data
@TableName("friendship")
public class Friendship {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 较小的用户 id */
    private Long userA;
    /** 较大的用户 id */
    private Long userB;
    /** 发起申请者 */
    private Long fromUser;
    /** 0=申请中 1=已同意 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
