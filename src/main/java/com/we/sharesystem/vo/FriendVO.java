package com.we.sharesystem.vo;

import lombok.Data;

/**
 * 好友/申请列表项。
 * relation: 1=已同意(好友) 0=申请中（我发出的 or 我收到的）
 * fromUser: 申请发起人（用于区分是我发的还是对方发的）
 */
@Data
public class FriendVO {
    private Long id;
    private String username;
    private String nickname;
    private String avatar;
    private String bio;
    /** 0申请中 1已同意 */
    private Integer relation;
    /** 申请发起人 */
    private Long fromUser;
}
