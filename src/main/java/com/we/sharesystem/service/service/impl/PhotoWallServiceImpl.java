package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.entity.Moment;
import com.we.sharesystem.entity.MomentImage;
import com.we.sharesystem.mapper.MomentImageMapper;
import com.we.sharesystem.mapper.MomentMapper;
import com.we.sharesystem.service.LikeService;
import com.we.sharesystem.service.PhotoWallService;
import com.we.sharesystem.vo.WallPhotoVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
    private final LikeService likeService;

    @Override
    public Page<WallPhotoVO> myPhotos(Long userId, Integer page, Integer size) {
        // 我发布的动态（status=1），按时间倒序。直接查库不走缓存，保证发布后立即出现在墙上
        List<Moment> moments = momentMapper.selectList(
                new LambdaQueryWrapper<Moment>()
                        .select(Moment::getId, Moment::getTitle, Moment::getContent, Moment::getCreateTime)
                        .eq(Moment::getUserId, userId)
                        .eq(Moment::getStatus, 1)
                        .orderByDesc(Moment::getCreateTime));
        return assemble(moments, userId, page, size, false);
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
        return assemble(moments, userId, page, size, true);
    }

    /**
     * 把动态列表装配成照片墙分页结果，两个接口共用。
     *
     * @param withYearsAgo 是否计算"距今几年"（那年今日接口为 true）
     */
    private Page<WallPhotoVO> assemble(List<Moment> moments, Long userId,
                                       Integer page, Integer size, boolean withYearsAgo) {
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
            vo.setLiked(likeService.hasLike(userId, vo.getId(), PHOTO_LIKE_TYPE));
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
