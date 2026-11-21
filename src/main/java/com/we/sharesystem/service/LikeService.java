package com.we.sharesystem.service;

public interface LikeService {
    /**
     * 点赞 / 取消点赞
     * @param userId 用户id
     * @param targetId 目标id（帖子id/评论id）
     * @param targetType 1分享 2评论
     * @return 1=点赞成功，-1=取消点赞成功
     */
    Integer like(Long userId, Long targetId, Integer targetType);

    /**
     * 查询当前用户是否点赞
     */
    boolean hasLike(Long userId, Long targetId, Integer targetType);

    /**
     * 查询目标点赞总数
     */
    Long getLikeCount(Long targetId, Integer targetType);
}
