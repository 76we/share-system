package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.we.sharesystem.common.BusinessException;
import com.we.sharesystem.entity.Friendship;
import com.we.sharesystem.entity.User;
import com.we.sharesystem.mapper.FriendshipMapper;
import com.we.sharesystem.mapper.UserMapper;
import com.we.sharesystem.service.FriendshipService;
import com.we.sharesystem.service.NotificationService;
import com.we.sharesystem.service.UserService;
import com.we.sharesystem.vo.FriendSearchVO;
import com.we.sharesystem.vo.FriendVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FriendshipServiceImpl implements FriendshipService {

    private final FriendshipMapper friendshipMapper;
    private final UserMapper userMapper;
    private final UserService userService;
    private final NotificationService notificationService;

    @Override
    public List<FriendSearchVO> searchUser(Long meId, String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return Collections.emptyList();
        }
        // 模糊匹配昵称或用户名，排除自己
        List<User> users = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .like(User::getNickname, kw)
                        .or().like(User::getUsername, kw)
                        .ne(User::getId, meId)
                        .eq(User::getStatus, 1)
                        .orderByAsc(User::getId)
                        .last("limit 50"));
        if (users.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> targetIds = users.stream().map(User::getId).toList();
        // 查我与此批人的关系，建立 otherId -> relation 映射
        Map<Long, Integer> relationMap = relationOfOthers(meId, targetIds);

        return users.stream().map(u -> {
            FriendSearchVO vo = new FriendSearchVO();
            vo.setId(u.getId());
            vo.setUsername(u.getUsername());
            vo.setNickname(u.getNickname());
            vo.setAvatar(u.getAvatar());
            vo.setBio(u.getBio());
            vo.setRelation(relationMap.getOrDefault(u.getId(), 0));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void request(Long meId, Long targetId) {
        if (meId.equals(targetId)) {
            throw new BusinessException("不能添加自己为好友");
        }
        User target = userMapper.selectById(targetId);
        if (target == null) {
            throw new BusinessException("用户不存在");
        }
        // 已有关系（申请中或已好友）则幂等返回，不重复插
        Friendship exist = findPair(meId, targetId);
        if (exist != null) {
            if (exist.getStatus() == 1) {
                throw new BusinessException("你俩已经是好友了");
            }
            return; // 申请中，幂等
        }
        Friendship f = new Friendship();
        f.setUserA(Math.min(meId, targetId));
        f.setUserB(Math.max(meId, targetId));
        f.setFromUser(meId);
        f.setStatus(0);
        f.setCreateTime(LocalDateTime.now());
        f.setUpdateTime(LocalDateTime.now());
        friendshipMapper.insert(f);
        // 通知对方：我向你申请
        notificationService.onFriendRequest(meId, targetId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void accept(Long meId, Long applicantId) {
        // 只有申请发起人的对方才能同意：from_user=applicantId 且 包含 meId
        Friendship f = findPair(meId, applicantId);
        if (f == null || f.getStatus() == 1) {
            throw new BusinessException("没有可同意的申请");
        }
        f.setStatus(1);
        f.setUpdateTime(LocalDateTime.now());
        friendshipMapper.updateById(f);
        // 通知申请人：你同意了 TA 的申请（actorId=meId, targetId=applicantId）
        notificationService.onFriendAccepted(meId, applicantId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long meId, Long applicantId) {
        Friendship f = findPair(meId, applicantId);
        if (f == null || f.getStatus() == 1) {
            throw new BusinessException("没有可拒绝的申请");
        }
        // 只有"对方对我发起"的申请我才能拒绝；若是我发起的，拒绝无意义，走撤回即可
        // 这里统一删除申请记录（无论谁发起，当前用户都终止这条待处理关系）
        friendshipMapper.deleteById(f.getId());
    }

    @Override
    public boolean isFriend(Long meId, Long otherId) {
        if (meId == null || otherId == null || meId.equals(otherId)) {
            return false;
        }
        Friendship f = findPair(meId, otherId);
        return f != null && f.getStatus() == 1;
    }

    @Override
    public List<FriendVO> myFriends(Long meId) {
        List<Friendship> list = friendshipMapper.selectList(
                new LambdaQueryWrapper<Friendship>()
                        .and(w -> w.eq(Friendship::getUserA, meId).or().eq(Friendship::getUserB, meId))
                        .eq(Friendship::getStatus, 1)
                        .orderByDesc(Friendship::getUpdateTime));
        return toFriendVO(list, meId, 1);
    }

    @Override
    public List<FriendVO> incomingRequests(Long meId) {
        // 我收到的：from_user != meId 且包含 meId，status=0
        List<Friendship> list = friendshipMapper.selectList(
                new LambdaQueryWrapper<Friendship>()
                        .and(w -> w.eq(Friendship::getUserA, meId).or().eq(Friendship::getUserB, meId))
                        .eq(Friendship::getStatus, 0)
                        .ne(Friendship::getFromUser, meId)
                        .orderByDesc(Friendship::getCreateTime));
        return toFriendVO(list, meId, 0);
    }

    @Override
    public List<FriendVO> mySentRequests(Long meId) {
        // 我发出的：from_user = meId, status=0
        List<Friendship> list = friendshipMapper.selectList(
                new LambdaQueryWrapper<Friendship>()
                        .eq(Friendship::getFromUser, meId)
                        .eq(Friendship::getStatus, 0)
                        .orderByDesc(Friendship::getCreateTime));
        return toFriendVO(list, meId, 0);
    }

    // ==================== 私有工具 ====================

    /** 查 (meId, otherId) 这一对的关系记录（任意方向） */
    private Friendship findPair(Long a, Long b) {
        Long low = Math.min(a, b);
        Long high = Math.max(a, b);
        return friendshipMapper.selectOne(
                new LambdaQueryWrapper<Friendship>()
                        .eq(Friendship::getUserA, low)
                        .eq(Friendship::getUserB, high)
                        .last("limit 1"));
    }

    /**
     * 计算我(meId)与一批人(otherIds)的关系：
     * 0无 1好友 2我申请中(from=我,status=0) 3对方申请我(from=对方,status=0)
     */
    private Map<Long, Integer> relationOfOthers(Long meId, List<Long> otherIds) {
        if (otherIds == null || otherIds.isEmpty()) {
            return Collections.emptyMap();
        }
        // 一次查出与这批人相关的所有关系记录
        List<Friendship> rel = friendshipMapper.selectList(
                new LambdaQueryWrapper<Friendship>()
                        .and(w -> w
                                .eq(Friendship::getUserA, meId).in(Friendship::getUserB, otherIds)
                                .or()
                                .eq(Friendship::getUserB, meId).in(Friendship::getUserA, otherIds)));
        Map<Long, Integer> map = new java.util.HashMap<>();
        for (Friendship f : rel) {
            Long other = f.getUserA().equals(meId) ? f.getUserB() : f.getUserA();
            if (!otherIds.contains(other)) {
                continue;
            }
            int r;
            if (f.getStatus() == 1) {
                r = 1;
            } else {
                r = f.getFromUser().equals(meId) ? 2 : 3;
            }
            map.put(other, r);
        }
        return map;
    }

    /** 把一批好友关系记录转成 VO；otherId 由 meId 反推（对方 = 不是我的那一个） */
    private List<FriendVO> toFriendVO(List<Friendship> list, Long meId, int relation) {
        if (list.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> ids = list.stream()
                .flatMap(f -> java.util.stream.Stream.of(f.getUserA(), f.getUserB()))
                .collect(Collectors.toSet());
        Map<Long, User> users = userService.getUserMapByIds(ids);
        List<FriendVO> result = new ArrayList<>();
        for (Friendship f : list) {
            Long other = f.getUserA().equals(meId) ? f.getUserB() : f.getUserA();
            User u = users.get(other);
            FriendVO vo = new FriendVO();
            vo.setId(other);
            if (u != null) {
                vo.setUsername(u.getUsername());
                vo.setNickname(u.getNickname());
                vo.setAvatar(u.getAvatar());
                vo.setBio(u.getBio());
            }
            vo.setRelation(relation);
            vo.setFromUser(f.getFromUser());
            result.add(vo);
        }
        return result;
    }
}
