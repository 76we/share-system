package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.common.BusinessException;
import com.we.sharesystem.entity.Moment;
import com.we.sharesystem.entity.MomentImage;
import com.we.sharesystem.entity.Notification;
import com.we.sharesystem.entity.User;
import com.we.sharesystem.mapper.MomentImageMapper;
import com.we.sharesystem.mapper.MomentMapper;
import com.we.sharesystem.mapper.NotificationMapper;
import com.we.sharesystem.service.NotificationService;
import com.we.sharesystem.service.UserService;
import com.we.sharesystem.vo.NotificationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;
    private final MomentMapper momentMapper;
    private final MomentImageMapper momentImageMapper;
    private final UserService userService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onLike(Long actorId, Long targetId, Integer targetType) {
        // 只处理动态(1)和照片(3)的点赞
        if (targetType == null || (targetType != 1 && targetType != 3)) {
            return;
        }
        Long ownerId;
        int type;
        String content;
        if (targetType == 1) {
            Moment moment = momentMapper.selectById(targetId);
            if (moment == null) return;
            ownerId = moment.getUserId();
            type = 1;
            content = "赞了你的动态" + (moment.getTitle() == null || moment.getTitle().isBlank()
                    ? "" : "《" + moment.getTitle() + "》");
        } else {
            MomentImage image = momentImageMapper.selectById(targetId);
            if (image == null) return;
            Moment moment = momentMapper.selectById(image.getMomentId());
            if (moment == null) return;
            ownerId = moment.getUserId();
            type = 2;
            content = "赞了你的照片";
        }
        if (actorId.equals(ownerId)) return; // 不通知自己

        // 同一人同一目标只保留一条：已存在则刷新为未读，避免重复刷屏
        Notification exist = notificationMapper.selectOne(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, ownerId)
                        .eq(Notification::getActorId, actorId)
                        .eq(Notification::getType, type)
                        .eq(Notification::getTargetId, targetId)
                        .last("limit 1"));
        if (exist != null) {
            exist.setIsRead(0);
            exist.setCreateTime(LocalDateTime.now());
            exist.setContent(content);
            notificationMapper.updateById(exist);
            return;
        }
        insert(ownerId, actorId, type, targetId, content);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onComment(Long actorId, Long momentId, Long replyUserId, String content) {
        Moment moment = momentMapper.selectById(momentId);
        if (moment == null) return;
        // 回复评论 → 通知被回复人；一级评论 → 通知动态作者
        Long receiver = (replyUserId != null && !replyUserId.equals(actorId))
                ? replyUserId : moment.getUserId();
        if (receiver.equals(actorId)) return; // 不通知自己

        int type = (replyUserId != null) ? 4 : 3;
        String summary = content == null ? "" : content.trim();
        if (summary.length() > 60) summary = summary.substring(0, 60) + "…";
        String text = (type == 4 ? "回复了你：" : "评论了你的动态：") + summary;
        insert(receiver, actorId, type, momentId, text);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onFriendRequest(Long actorId, Long targetId) {
        if (actorId == null || targetId == null || actorId.equals(targetId)) return;
        insert(targetId, actorId, 5, actorId, "向你发起了好友申请");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onFriendAccepted(Long actorId, Long targetId) {
        if (actorId == null || targetId == null || actorId.equals(targetId)) return;
        insert(targetId, actorId, 6, actorId, "同意了你的好友申请");
    }

    @Override
    public Page<NotificationVO> listMine(Long userId, Integer page, Integer size) {
        Page<Notification> p = notificationMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .orderByDesc(Notification::getCreateTime)
                        .orderByDesc(Notification::getId));
        Page<NotificationVO> result = new Page<>(page, size, p.getTotal());
        if (p.getRecords().isEmpty()) {
            result.setRecords(Collections.emptyList());
            return result;
        }
        Set<Long> actorIds = p.getRecords().stream().map(Notification::getActorId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> users = userService.getUserMapByIds(actorIds);
        List<NotificationVO> vos = p.getRecords().stream().map(n -> {
            NotificationVO vo = new NotificationVO();
            vo.setId(n.getId());
            vo.setType(n.getType());
            vo.setTargetId(n.getTargetId());
            vo.setContent(n.getContent());
            vo.setIsRead(n.getIsRead());
            vo.setCreateTime(n.getCreateTime());
            vo.setActorId(n.getActorId());
            User actor = users.get(n.getActorId());
            if (actor != null) {
                vo.setActorName(actor.getNickname() != null && !actor.getNickname().isBlank()
                        ? actor.getNickname() : actor.getUsername());
                vo.setActorAvatar(actor.getAvatar());
            }
            return vo;
        }).collect(Collectors.toList());
        result.setRecords(vos);
        return result;
    }

    @Override
    public Long unreadCount(Long userId) {
        Long count = notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .eq(Notification::getIsRead, 0));
        return count == null ? 0L : count;
    }

    @Override
    public void markRead(Long notificationId, Long userId) {
        Notification n = notificationMapper.selectById(notificationId);
        if (n == null || !userId.equals(n.getUserId())) {
            throw new BusinessException("通知不存在或无权访问");
        }
        n.setIsRead(1);
        notificationMapper.updateById(n);
    }

    @Override
    public void markAllRead(Long userId) {
        Notification update = new Notification();
        update.setIsRead(1);
        notificationMapper.update(update,
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .eq(Notification::getIsRead, 0));
    }

    private void insert(Long ownerId, Long actorId, int type, Long targetId, String content) {
        Notification n = new Notification();
        n.setUserId(ownerId);
        n.setActorId(actorId);
        n.setType(type);
        n.setTargetId(targetId);
        n.setContent(content);
        n.setIsRead(0);
        n.setCreateTime(LocalDateTime.now());
        notificationMapper.insert(n);
    }
}
