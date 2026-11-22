package com.we.sharesystem.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.vo.PhotoMonthGroupVO;
import com.we.sharesystem.vo.WallPhotoVO;

import java.util.List;

/** 照片墙服务：把用户发布动态时带的图片聚合成"我的视觉日记" */
public interface PhotoWallService {

    /** 我的照片墙：当前用户所有已发布动态的图片，按时间倒序分页 */
    Page<WallPhotoVO> myPhotos(Long userId, Integer page, Integer size);

    /** 那年今日：当前用户在往年同一月日发布的照片，按时间倒序分页 */
    Page<WallPhotoVO> anniversaryPhotos(Long userId, Integer page, Integer size);

    /** 访问 TA 的照片墙；本人访问不受可见性限制，他人访问需对方设为公开 */
    Page<WallPhotoVO> otherPhotos(Long ownerId, Long viewerId, Integer page, Integer size);

    /** 我的照片墙——按月分区间（不分页，供月份导航视图） */
    List<PhotoMonthGroupVO> myPhotosGrouped(Long userId);

    /** 那年今日——按月分区间 */
    List<PhotoMonthGroupVO> anniversaryPhotosGrouped(Long userId);

    /** 访问 TA 的照片墙——按月分区间（含可见性校验） */
    List<PhotoMonthGroupVO> otherPhotosGrouped(Long ownerId, Long viewerId);

    /** 年度回忆册：某一年发布的照片，按月分区间 */
    List<PhotoMonthGroupVO> yearPhotosGrouped(Long userId, Integer year);

    /** 读取我的照片墙可见性：0=私密 1=公开 */
    Integer getVisibility(Long userId);

    /** 设置我的照片墙可见性：0=私密 1=公开 */
    void setVisibility(Long userId, Integer visibility);
}
