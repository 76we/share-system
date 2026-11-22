package com.we.sharesystem.vo;

import lombok.Data;

/**
 * 搜人结果项。
 * relation: 0=无关系 1=已是好友 2=我发出的待处理申请 3=对方对我发出的待处理申请
 */
@Data
public class FriendSearchVO {
    private Long id;
    private String username;
    private String nickname;
    private String avatar;
    private String bio;
    /** 与我的关系：0无 1好友 2我申请中 3对方申请我 */
    private Integer relation;
}
