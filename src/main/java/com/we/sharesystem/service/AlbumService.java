package com.we.sharesystem.service;

import com.we.sharesystem.vo.AlbumVO;
import com.we.sharesystem.vo.PhotoVO;

import java.util.List;

public interface AlbumService {
    /** 相册列表（含封面前 3 张与照片数量） */
    List<AlbumVO> listAlbums();

    /** 指定相册下的全部照片 */
    List<PhotoVO> listPhotos(Long albumId);
}
