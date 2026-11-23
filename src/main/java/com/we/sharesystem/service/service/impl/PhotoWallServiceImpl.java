package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.common.BusinessException;
import com.we.sharesystem.common.PageParam;
import com.we.sharesystem.entity.Moment;
import com.we.sharesystem.entity.MomentImage;
import com.we.sharesystem.entity.User;
import com.we.sharesystem.mapper.MomentImageMapper;
import com.we.sharesystem.mapper.MomentMapper;
import com.we.sharesystem.mapper.UserMapper;
import com.we.sharesystem.service.FriendshipService;
import com.we.sharesystem.service.LikeService;
import com.we.sharesystem.service.PhotoWallService;
import com.we.sharesystem.vo.FriendWallVO;
import com.we.sharesystem.vo.FriendVO;
import com.we.sharesystem.vo.PhotoMonthGroupVO;
import com.we.sharesystem.vo.WallPhotoVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 照片墙实现。
 * 归属：照片来自用户自己发布的动态（moment.userId），不需要给图片表加 owner_id。
 * 来源：发布动态时写入 moment_image 的图片，自动成为照片墙内容，无需用户单独上传。
 * 互动：点赞复用 LikeService，targetType=3 表示"照片"，与动态(1)/评论(2)共用一套Redis+MySQL逻辑。
 * 权限：整面墙一个可见性（user.wall_visibility），0私密仅自己看，1公开他人可访问。
 */
@Service
@RequiredArgsConstructor
public class PhotoWallServiceImpl implements PhotoWallService {

    /** 照片点赞类型：1=动态 2=评论 3=照片 */
    private static final int PHOTO_LIKE_TYPE = 3;
    /** 与应用 jackson 时区保持一致 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final MomentMapper momentMapper;
    private final MomentImageMapper momentImageMapper;
    private final UserMapper userMapper;
    private final LikeService likeService;
    private final FriendshipService friendshipService;

    @Override
    public Page<WallPhotoVO> myPhotos(Long userId, Integer page, Integer size) {
        return photosByOwner(userId, userId, page, size);
    }

    @Override
    public Page<WallPhotoVO> anniversaryPhotos(Long userId, Integer page, Integer size) {
        LocalDate today = LocalDate.now(ZONE);
        int currentYear = today.getYear();
        // 往年同一月日：MONTH/DAY 匹配且年份 < 今年，按时间倒序（最近的回忆在前）
        List<Moment> moments = momentMapper.selectList(
                new LambdaQueryWrapper<Moment>()
                        .select(Moment::getId, Moment::getTitle, Moment::getContent, Moment::getCreateTime)
                        .eq(Moment::getUserId, userId)
                        .eq(Moment::getStatus, 1)
                        .apply("MONTH(create_time) = {0} AND DAY(create_time) = {1}",
                                today.getMonthValue(), today.getDayOfMonth())
                        .lt(Moment::getCreateTime,
                                LocalDate.of(currentYear, 1, 1).atStartOfDay())
                        .orderByDesc(Moment::getCreateTime));
        // 那年今日只用于"我的墙"，点赞状态按本人判断
        return assemble(moments, userId, page, size, true);
    }

    @Override
    public Page<WallPhotoVO> otherPhotos(Long ownerId, Long viewerId, Integer page, Integer size) {
        // 自己看自己：不受可见性限制，直接返回
        if (ownerId.equals(viewerId)) {
            return photosByOwner(ownerId, viewerId, page, size);
        }
        // 他人看：先看墙主是否存在，再按可见性判断是否放行
        User owner = userMapper.selectById(ownerId);
        if (owner == null) {
            throw new BusinessException("用户不存在");
        }
        assertCanView(owner, viewerId);
        // ownerId 决定看谁的照片，viewerId 决定点赞状态（我是否已赞）
        return photosByOwner(ownerId, viewerId, page, size);
    }

    @Override
    public Integer getVisibility(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return user.getWallVisibility() == null ? 0 : user.getWallVisibility();
    }

    // ==================== 按月分区间视图 ====================

    @Override
    public List<PhotoMonthGroupVO> myPhotosGrouped(Long userId) {
        List<Moment> moments = myMoments(userId);
        return groupByMonth(assembleAll(moments, userId, false));
    }

    @Override
    public List<PhotoMonthGroupVO> anniversaryPhotosGrouped(Long userId) {
        List<Moment> moments = anniversaryMoments(userId);
        return groupByMonth(assembleAll(moments, userId, true));
    }

    @Override
    public List<PhotoMonthGroupVO> otherPhotosGrouped(Long ownerId, Long viewerId) {
        List<Moment> moments;
        if (ownerId.equals(viewerId)) {
            moments = myMoments(ownerId);
        } else {
            User owner = userMapper.selectById(ownerId);
            if (owner == null) {
                throw new BusinessException("用户不存在");
            }
            assertCanView(owner, viewerId);
            moments = myMoments(ownerId);
        }
        return groupByMonth(assembleAll(moments, viewerId, false));
    }

    @Override
    public List<PhotoMonthGroupVO> yearPhotosGrouped(Long userId, Integer year) {
        if (year == null || year < 2000 || year > LocalDate.now(ZONE).getYear()) {
            throw new BusinessException("年份不合法");
        }
        List<Moment> moments = momentMapper.selectList(
                new LambdaQueryWrapper<Moment>()
                        .select(Moment::getId, Moment::getTitle, Moment::getContent, Moment::getCreateTime)
                        .eq(Moment::getUserId, userId)
                        .eq(Moment::getStatus, 1)
                        .apply("YEAR(create_time) = {0}", year)
                        .orderByDesc(Moment::getCreateTime));
        return groupByMonth(assembleAll(moments, userId, false));
    }

    // ==================== 好友墙聚合 ====================

    @Override
    public List<FriendWallVO> friendPhotos(Long userId, int limitPerFriend) {
        // 每个好友取几张由前端传，钳制一下：传 1000 会把整个好友圈的图片都拉出来
        limitPerFriend = PageParam.limit(limitPerFriend, 6, 1, 50);
        // 1) 取我所有好友
        List<FriendVO> friends = friendshipService.myFriends(userId);
        if (friends.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> friendIds = friends.stream().map(FriendVO::getId).toList();

        // 2) 一次批量取好友用户信息（原实现每个好友查一次，好友多时 2N 次查询）
        Map<Long, User> ownerMap = userMapper.selectBatchIds(friendIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        // 只保留照片墙可见的好友：0 私密不聚合；1 公开 / 2 仅好友放行（我们已是好友）
        List<FriendVO> visible = friends.stream()
                .filter(f -> {
                    User o = ownerMap.get(f.getId());
                    if (o == null) return false;
                    Integer vis = o.getWallVisibility() == null ? 0 : o.getWallVisibility();
                    return vis != 0;
                })
                .toList();
        if (visible.isEmpty()) {
            return Collections.emptyList();
        }

        // 3) 一次批量查这些好友的全部已发布动态（倒序）
        List<Long> visibleIds = visible.stream().map(FriendVO::getId).toList();
        List<Moment> moments = momentMapper.selectList(
                new LambdaQueryWrapper<Moment>()
                        .select(Moment::getId, Moment::getUserId, Moment::getTitle,
                                Moment::getContent, Moment::getCreateTime)
                        .in(Moment::getUserId, visibleIds)
                        .eq(Moment::getStatus, 1)
                        .orderByDesc(Moment::getCreateTime));
        if (moments.isEmpty()) {
            return Collections.emptyList();
        }

        // 4) 一次批量查这些动态的图片
        List<Long> momentIds = moments.stream().map(Moment::getId).toList();
        List<MomentImage> images = momentImageMapper.selectList(
                new LambdaQueryWrapper<MomentImage>()
                        .in(MomentImage::getMomentId, momentIds)
                        .orderByDesc(MomentImage::getId));
        if (images.isEmpty()) {
            return Collections.emptyList();
        }

        // 5) 内存里按动态分组，再按好友聚合，整个方法固定 4 次 DB 查询
        Map<Long, Moment> momentMap = moments.stream()
                .collect(Collectors.toMap(Moment::getId, m -> m, (a, b) -> a));
        Map<Long, List<MomentImage>> imagesByMoment = images.stream()
                .collect(Collectors.groupingBy(MomentImage::getMomentId));

        List<FriendWallVO> result = new ArrayList<>();
        for (FriendVO f : visible) {
            User owner = ownerMap.get(f.getId());
            List<WallPhotoVO> photos = new ArrayList<>();
            int total = 0;
            for (Moment m : moments) {
                if (!f.getId().equals(m.getUserId())) continue;
                List<MomentImage> imgs = imagesByMoment.getOrDefault(m.getId(), Collections.emptyList());
                total += imgs.size();
                for (MomentImage img : imgs) {
                    if (photos.size() >= limitPerFriend) continue;
                    WallPhotoVO vo = new WallPhotoVO();
                    vo.setId(img.getId());
                    vo.setMomentId(img.getMomentId());
                    vo.setUrl(img.getUrl());
                    vo.setCaption(captionOf(m));
                    vo.setCreateTime(m.getCreateTime());
                    vo.setOwnerId(f.getId());
                    vo.setOwnerName(f.getNickname() != null ? f.getNickname() : f.getUsername());
                    vo.setOwnerAvatar(f.getAvatar());
                    vo.setLikeCount(likeService.getLikeCount(img.getId(), PHOTO_LIKE_TYPE).intValue());
                    vo.setLiked(likeService.hasLike(userId, img.getId(), PHOTO_LIKE_TYPE));
                    photos.add(vo);
                }
            }
            if (photos.isEmpty()) continue;

            FriendWallVO fv = new FriendWallVO();
            fv.setFriendId(f.getId());
            fv.setFriendName(f.getNickname() != null ? f.getNickname() : f.getUsername());
            fv.setFriendAvatar(f.getAvatar());
            fv.setPhotos(photos);
            fv.setTotalCount(total);
            result.add(fv);
        }
        return result;
    }

    /** 查某用户全部已发布动态（照片原始来源），按时间倒序 */
    private List<Moment> myMoments(Long ownerId) {
        return momentMapper.selectList(
                new LambdaQueryWrapper<Moment>()
                        .select(Moment::getId, Moment::getTitle, Moment::getContent, Moment::getCreateTime)
                        .eq(Moment::getUserId, ownerId)
                        .eq(Moment::getStatus, 1)
                        .orderByDesc(Moment::getCreateTime));
    }

    /** 那年今日的原始动态（往年同一月日） */
    private List<Moment> anniversaryMoments(Long userId) {
        LocalDate today = LocalDate.now(ZONE);
        int currentYear = today.getYear();
        return momentMapper.selectList(
                new LambdaQueryWrapper<Moment>()
                        .select(Moment::getId, Moment::getTitle, Moment::getContent, Moment::getCreateTime)
                        .eq(Moment::getUserId, userId)
                        .eq(Moment::getStatus, 1)
                        .apply("MONTH(create_time) = {0} AND DAY(create_time) = {1}",
                                today.getMonthValue(), today.getDayOfMonth())
                        .lt(Moment::getCreateTime, LocalDate.of(currentYear, 1, 1).atStartOfDay())
                        .orderByDesc(Moment::getCreateTime));
    }

    /**
     * 把动态列表装配成完整照片列表（不分页），并填充点赞数与状态。
     * 与分页版 assemble 共用摊平逻辑，只是不截断。
     */
    private List<WallPhotoVO> assembleAll(List<Moment> moments, Long viewerId, boolean withYearsAgo) {
        if (moments.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> momentIds = moments.stream().map(Moment::getId).toList();
        List<MomentImage> images = momentImageMapper.selectList(
                new LambdaQueryWrapper<MomentImage>()
                        .in(MomentImage::getMomentId, momentIds)
                        .orderByAsc(MomentImage::getSortOrder)
                        .orderByAsc(MomentImage::getId));
        Map<Long, List<MomentImage>> byMoment = images.stream()
                .collect(Collectors.groupingBy(MomentImage::getMomentId));

        int currentYear = LocalDate.now(ZONE).getYear();
        List<WallPhotoVO> all = new ArrayList<>();
        for (Moment m : moments) {
            List<MomentImage> imgs = byMoment.getOrDefault(m.getId(), Collections.emptyList());
            for (MomentImage img : imgs) {
                WallPhotoVO vo = new WallPhotoVO();
                vo.setId(img.getId());
                vo.setMomentId(m.getId());
                vo.setUrl(img.getUrl());
                vo.setCaption(captionOf(m));
                vo.setCreateTime(m.getCreateTime());
                if (withYearsAgo && m.getCreateTime() != null) {
                    vo.setYearsAgo(currentYear - m.getCreateTime().getYear());
                }
                all.add(vo);
            }
        }
        for (WallPhotoVO vo : all) {
            vo.setLikeCount(likeService.getLikeCount(vo.getId(), PHOTO_LIKE_TYPE).intValue());
            vo.setLiked(likeService.hasLike(viewerId, vo.getId(), PHOTO_LIKE_TYPE));
        }
        return all;
    }

    /** 按 年-月 把照片切成区间；照片已按时间倒序，分组顺序天然是最近月份在前 */
    private List<PhotoMonthGroupVO> groupByMonth(List<WallPhotoVO> photos) {
        Map<String, List<WallPhotoVO>> buckets = new java.util.LinkedHashMap<>();
        for (WallPhotoVO p : photos) {
            int year = 0, month = 0;
            if (p.getCreateTime() != null) {
                year = p.getCreateTime().getYear();
                month = p.getCreateTime().getMonthValue();
            }
            String key = year + "-" + month;
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        List<PhotoMonthGroupVO> groups = new ArrayList<>();
        for (Map.Entry<String, List<WallPhotoVO>> e : buckets.entrySet()) {
            String[] parts = e.getKey().split("-");
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            PhotoMonthGroupVO g = new PhotoMonthGroupVO();
            g.setYear(year == 0 ? null : year);
            g.setMonth(month == 0 ? null : month);
            g.setLabel(year == 0 ? "未知时间" : year + "年" + month + "月");
            g.setCount(e.getValue().size());
            g.setPhotos(e.getValue());
            groups.add(g);
        }
        return groups;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setVisibility(Long userId, Integer visibility) {
        if (visibility == null || (visibility != 0 && visibility != 1 && visibility != 2)) {
            throw new BusinessException("可见性只能是 0（私密）、1（公开）或 2（仅好友）");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        user.setWallVisibility(visibility);
        userMapper.updateById(user);
    }

    /**
     * 他人访问照片墙时的可见性校验：
     * 0 私密 → 拒绝；1 公开 → 放行；2 仅好友 → 是好友放行，否则拒绝。
     * 本人看自己的场景由调用方（ownerId.equals(viewerId)）先拦下，不会走到这里。
     */
    private void assertCanView(User owner, Long viewerId) {
        Integer visibility = owner.getWallVisibility() == null ? 0 : owner.getWallVisibility();
        if (visibility == 1) {
            return;
        }
        if (visibility == 2) {
            if (friendshipService.isFriend(viewerId, owner.getId())) {
                return;
            }
            throw new BusinessException("TA 的照片墙仅对好友开放，先添加 TA 为好友吧");
        }
        // visibility == 0
        throw new BusinessException("TA 的照片墙是私密的，暂时无法查看");
    }

    /** 查某用户的照片并装配分页；ownerId=照片主人，viewerId=当前查看者（用于点赞状态） */
    private Page<WallPhotoVO> photosByOwner(Long ownerId, Long viewerId, Integer page, Integer size) {
        // 我发布的动态（status=1），按时间倒序。直接查库不走缓存，保证发布后立即出现在墙上
        List<Moment> moments = momentMapper.selectList(
                new LambdaQueryWrapper<Moment>()
                        .select(Moment::getId, Moment::getTitle, Moment::getContent, Moment::getCreateTime)
                        .eq(Moment::getUserId, ownerId)
                        .eq(Moment::getStatus, 1)
                        .orderByDesc(Moment::getCreateTime));
        return assemble(moments, viewerId, page, size, false);
    }

    /**
     * 把动态列表装配成照片墙分页结果，两个接口共用。
     *
     * @param viewerId    当前查看者（点赞状态按他判断，不一定是墙主）
     * @param withYearsAgo 是否计算"距今几年"（那年今日接口为 true）
     */
    private Page<WallPhotoVO> assemble(List<Moment> moments, Long viewerId,
                                       Integer page, Integer size, boolean withYearsAgo) {
        // 这里是所有分页照片墙的唯一入口，统一归一化：
        // page=0 / 负数原先会算出负的 from，subList 直接抛越界 → 500
        page = PageParam.page(page);
        size = PageParam.size(size, 12);
        if (moments.isEmpty()) {
            return new Page<>(page, size, 0);
        }

        // 批量取这些动态下的所有图片，避免逐条查询
        List<Long> momentIds = moments.stream().map(Moment::getId).toList();
        List<MomentImage> images = momentImageMapper.selectList(
                new LambdaQueryWrapper<MomentImage>()
                        .in(MomentImage::getMomentId, momentIds)
                        .orderByAsc(MomentImage::getSortOrder)
                        .orderByAsc(MomentImage::getId));
        Map<Long, List<MomentImage>> byMoment = images.stream()
                .collect(Collectors.groupingBy(MomentImage::getMomentId));

        // 按"动态时间倒序 + 动态内图片顺序"摊平成照片流
        int currentYear = LocalDate.now(ZONE).getYear();
        List<WallPhotoVO> all = new ArrayList<>();
        for (Moment m : moments) {
            List<MomentImage> imgs = byMoment.getOrDefault(m.getId(), Collections.emptyList());
            for (MomentImage img : imgs) {
                WallPhotoVO vo = new WallPhotoVO();
                vo.setId(img.getId());
                vo.setMomentId(m.getId());
                vo.setUrl(img.getUrl());
                vo.setCaption(captionOf(m));
                vo.setCreateTime(m.getCreateTime());
                if (withYearsAgo && m.getCreateTime() != null) {
                    vo.setYearsAgo(currentYear - m.getCreateTime().getYear());
                }
                all.add(vo);
            }
        }

        // 内存分页（照片墙一页通常十几张，避免按图片ID分页打乱动态内的顺序）
        long total = all.size();
        int from = (int) Math.min((long) (page - 1) * size, total);
        int to = (int) Math.min(from + (long) size, total);
        List<WallPhotoVO> slice = from >= to ? Collections.emptyList() : all.subList(from, to);

        // 点赞数与点赞状态复用 LikeService（targetType=3），缓存未命中时其内部会回填
        for (WallPhotoVO vo : slice) {
            vo.setLikeCount(likeService.getLikeCount(vo.getId(), PHOTO_LIKE_TYPE).intValue());
            vo.setLiked(likeService.hasLike(viewerId, vo.getId(), PHOTO_LIKE_TYPE));
        }

        Page<WallPhotoVO> result = new Page<>(page, size, total);
        result.setRecords(slice);
        return result;
    }

    /** 图片说明：优先标题，无标题用正文，都没有留空 */
    private String captionOf(Moment m) {
        if (m.getTitle() != null && !m.getTitle().isBlank()) {
            return m.getTitle();
        }
        return m.getContent() == null ? null : m.getContent();
    }
}
