package com.we.sharesystem.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.vo.WallPhotoVO;

/** 照片墙服务：把用户发布动态时带的图片聚合成"我的视觉日记" */
public interface PhotoWallService {

    /** 我的照片墙：当前用户所有已发布动态的图片，按时间倒序分页 */
    Page<WallPhotoVO> myPhotos(Long userId, Integer page, Integer size);
}
