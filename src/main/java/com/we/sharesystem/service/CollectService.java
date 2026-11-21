package com.we.sharesystem.service;

public interface CollectService {
    /**
     * 收藏 / 取消收藏
     * @param userId 用户id
     * @param momentId 动态id
     * @return 1=收藏成功，-1=取消收藏成功
     */
    Integer collect(Long userId, Long momentId);

    /** 当前用户是否已收藏该动态 */
    boolean hasCollect(Long userId, Long momentId);

    /** 该动态收藏总数 */
    Long getCollectCount(Long momentId);
}
