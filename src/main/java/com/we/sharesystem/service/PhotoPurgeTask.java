package com.we.sharesystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.we.sharesystem.entity.FileUtil;
import com.we.sharesystem.entity.Moment;
import com.we.sharesystem.entity.MomentImage;
import com.we.sharesystem.mapper.MomentImageMapper;
import com.we.sharesystem.mapper.MomentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 图片回收任务。
 *
 * 背景：删除动态是软删（status=0），moment_image 行和磁盘文件全都留着，
 * 于是磁盘只增不减，被删动态的图片直链还长期可访问。这里把「已删除满 N 天」
 * 的动态图片真正清掉：
 * <ol>
 *   <li>删 moment_image 行</li>
 *   <li>删磁盘文件（原图 + _t 缩略图，由 FileUtil.delete 一并处理）</li>
 *   <li>moment.status 从 0 改成 -1，表示「已删且已清图」，下次扫描不再命中</li>
 * </ol>
 *
 * 不删 moment 行本身：保留记录，id 不会被复用，
 * 也避免历史通知/评论里的 momentId 指向不存在的行。
 *
 * 文件删除失败不会回滚已删的行 —— 那些文件会变成「无引用」，
 * 交给 deploy/photo-maintenance.sh 的孤儿清理兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhotoPurgeTask {

    /** 每轮处理多少条，避免一次跑太久把连接和 IO 占满 */
    private static final int BATCH = 200;

    private final MomentMapper momentMapper;
    private final MomentImageMapper momentImageMapper;
    private final FileUtil fileUtil;

    @Value("${app.photo.purge-enabled:true}")
    private boolean enabled;

    /** 软删后保留多少天再物理清图，留出误删恢复/申诉的窗口 */
    @Value("${app.photo.purge-days:30}")
    private int purgeDays;

    @Scheduled(cron = "${app.photo.purge-cron:0 40 3 * * ?}")
    public void purgeDeletedPhotos() {
        if (!enabled) {
            return;
        }
        LocalDateTime deadline = LocalDateTime.now().minusDays(purgeDays);
        int moments = 0;
        int files = 0;

        while (true) {
            List<Moment> batch = momentMapper.selectList(new LambdaQueryWrapper<Moment>()
                    .select(Moment::getId)
                    .eq(Moment::getStatus, 0)
                    // 用 update_time 而不是 create_time：它记录的是「什么时候被删的」
                    .lt(Moment::getUpdateTime, deadline)
                    .orderByAsc(Moment::getId)
                    .last("limit " + BATCH));
            if (batch.isEmpty()) {
                break;
            }

            for (Moment m : batch) {
                List<MomentImage> images = momentImageMapper.selectList(
                        new LambdaQueryWrapper<MomentImage>()
                                .eq(MomentImage::getMomentId, m.getId()));
                if (!images.isEmpty()) {
                    momentImageMapper.delete(new LambdaQueryWrapper<MomentImage>()
                            .eq(MomentImage::getMomentId, m.getId()));
                    fileUtil.deleteAll(images.stream().map(MomentImage::getUrl).toList());
                    files += images.size();
                }
                momentMapper.update(null, new LambdaUpdateWrapper<Moment>()
                        .eq(Moment::getId, m.getId())
                        .set(Moment::getStatus, -1));
                moments++;
            }

            if (batch.size() < BATCH) {
                break;
            }
        }

        if (moments > 0) {
            log.info("图片回收完成：{} 条已删除动态，清理 {} 张图片", moments, files);
        }
    }
}
