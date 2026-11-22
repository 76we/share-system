package com.we.sharesystem.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.vo.NotificationVO;

public interface NotificationService {

    /**
     * 点赞埋点。type: 1=赞动态 2=赞照片。
     * targetId 语义随 type 变化：type=1 为动态id，type=2 为照片(moment_image.id)。
     * 内部自行解析接收者并跳过"自己赞自己"，重复点赞不再产生通知。
     */
    void onLike(Long actorId, Long targetId, Integer targetType);

    /** 评论埋点：一级评论通知动态作者，回复通知被回复人 */
    void onComment(Long actorId, Long momentId, Long replyUserId, String content);

    /** 我的通知列表（分页，含发起人信息） */
    Page<NotificationVO> listMine(Long userId, Integer page, Integer size);

    /** 未读条数 */
    Long unreadCount(Long userId);

    /** 标记单条已读（仅本人） */
    void markRead(Long notificationId, Long userId);

    /** 全部标记已读 */
    void markAllRead(Long userId);
}
