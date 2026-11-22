package com.we.sharesystem.service;

import com.we.sharesystem.vo.AlbumVO;
import com.we.sharesystem.vo.PhotoVO;

import java.util.List;

public interface AlbumService {
    /** 我的相册列表（含封面前 3 张与照片数量） */
    List<AlbumVO> listAlbums(Long userId);

    /** 指定相册下的全部照片（校验归属） */
    List<PhotoVO> listPhotos(Long albumId, Long userId);

    /** 新建相册 */
    Long createAlbum(Long userId, String title, String description);

    /** 修改相册标题/简介 */
    void updateAlbum(Long albumId, Long userId, String title, String description);

    /** 删除相册（连同相册内照片） */
    void deleteAlbum(Long albumId, Long userId);

    /** 从照片墙选一张图加入相册；photoUrl 来自 moment_image.url */
    Long addPhoto(Long albumId, Long userId, String photoUrl, String caption);

    /** 删除相册内一张照片 */
    void removePhoto(Long photoId, Long userId);
}
