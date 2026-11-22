package com.we.sharesystem.vo;

import lombok.Data;

import java.util.List;

/**
 * 好友墙聚合的一段：某位好友近期发布的照片。
 * 前端据此渲染"好友的照片"，每位好友一节，节内是拍立得网格。
 * 照片的 ownerName/ownerAvatar 已填充，可直接展示"这是谁拍的"。
 */
@Data
public class FriendWallVO {
    /** 好友用户 id */
    private Long friendId;
    /** 好友昵称 */
    private String friendName;
    /** 好友头像 */
    private String friendAvatar;
    /** 该好友近期照片（按时间倒序，已填充点赞数/状态与作者信息） */
    private List<WallPhotoVO> photos;
    /** 该好友照片总数（近期取 limit 张，这里是该好友可见照片总数） */
    private Integer totalCount;
}
