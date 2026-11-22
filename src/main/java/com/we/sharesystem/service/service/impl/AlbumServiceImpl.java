package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.we.sharesystem.common.BusinessException;
import com.we.sharesystem.entity.Album;
import com.we.sharesystem.entity.Photo;
import com.we.sharesystem.mapper.AlbumMapper;
import com.we.sharesystem.mapper.PhotoMapper;
import com.we.sharesystem.service.AlbumService;
import com.we.sharesystem.vo.AlbumVO;
import com.we.sharesystem.vo.PhotoVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AlbumServiceImpl implements AlbumService {

    @Autowired
    private AlbumMapper albumMapper;

    @Autowired
    private PhotoMapper photoMapper;

    @Override
    public List<AlbumVO> listAlbums(Long userId) {
        List<Album> albums = albumMapper.selectList(
                new LambdaQueryWrapper<Album>()
                        .eq(Album::getUserId, userId)
                        .orderByAsc(Album::getSort)
                        .orderByDesc(Album::getUpdateTime));
        if (albums.isEmpty()) {
            return Collections.emptyList();
        }

        // 只取当前用户相册下的照片，一次性查出再分组
        List<Long> albumIds = albums.stream().map(Album::getId).toList();
        List<Photo> allPhotos = photoMapper.selectList(
                new LambdaQueryWrapper<Photo>()
                        .in(Photo::getAlbumId, albumIds)
                        .orderByAsc(Photo::getSortOrder)
                        .orderByAsc(Photo::getId));
        Map<Long, List<Photo>> byAlbum = allPhotos.stream()
                .collect(Collectors.groupingBy(Photo::getAlbumId));

        return albums.stream().map(album -> {
            AlbumVO vo = new AlbumVO();
            vo.setId(album.getId());
            vo.setTitle(album.getTitle());
            vo.setDescription(album.getDescription());
            vo.setUpdateTime(album.getUpdateTime());
            List<Photo> photos = byAlbum.getOrDefault(album.getId(), Collections.emptyList());
            vo.setPhotoCount(photos.size());
            vo.setCovers(photos.stream().limit(3).map(Photo::getUrl).collect(Collectors.toList()));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public List<PhotoVO> listPhotos(Long albumId, Long userId) {
        checkOwner(albumId, userId);
        List<Photo> photos = photoMapper.selectList(
                new LambdaQueryWrapper<Photo>()
                        .eq(Photo::getAlbumId, albumId)
                        .orderByAsc(Photo::getSortOrder)
                        .orderByAsc(Photo::getId));
        return photos.stream().map(photo -> {
            PhotoVO vo = new PhotoVO();
            vo.setId(photo.getId());
            vo.setUrl(photo.getUrl());
            vo.setCaption(photo.getCaption());
            vo.setOrientation(photo.getOrientation());
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createAlbum(Long userId, String title, String description) {
        if (title == null || title.isBlank()) {
            throw new BusinessException("相册标题不能为空");
        }
        Album album = new Album();
        album.setUserId(userId);
        album.setTitle(title.trim());
        album.setDescription(description == null || description.isBlank() ? null : description.trim());
        album.setSort(0);
        album.setCreateTime(LocalDateTime.now());
        album.setUpdateTime(LocalDateTime.now());
        albumMapper.insert(album);
        return album.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAlbum(Long albumId, Long userId, String title, String description) {
        Album album = getOwnedAlbum(albumId, userId);
        if (title != null && !title.isBlank()) album.setTitle(title.trim());
        if (description != null) album.setDescription(description.isBlank() ? null : description.trim());
        album.setUpdateTime(LocalDateTime.now());
        albumMapper.updateById(album);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAlbum(Long albumId, Long userId) {
        getOwnedAlbum(albumId, userId);
        photoMapper.delete(new LambdaQueryWrapper<Photo>().eq(Photo::getAlbumId, albumId));
        albumMapper.deleteById(albumId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addPhoto(Long albumId, Long userId, String photoUrl, String caption) {
        getOwnedAlbum(albumId, userId);
        if (photoUrl == null || photoUrl.isBlank()) {
            throw new BusinessException("照片地址不能为空");
        }
        // 同一相册内去重，避免重复加入
        Long exists = photoMapper.selectCount(
                new LambdaQueryWrapper<Photo>()
                        .eq(Photo::getAlbumId, albumId)
                        .eq(Photo::getUrl, photoUrl.trim()));
        if (exists != null && exists > 0) {
            throw new BusinessException("这张照片已经在相册里了");
        }
        Integer maxSort = photoMapper.selectList(
                        new LambdaQueryWrapper<Photo>()
                                .select(Photo::getSortOrder)
                                .eq(Photo::getAlbumId, albumId)
                                .orderByDesc(Photo::getSortOrder))
                .stream().map(Photo::getSortOrder).filter(java.util.Objects::nonNull)
                .findFirst().orElse(-1);
        Photo photo = new Photo();
        photo.setAlbumId(albumId);
        photo.setUrl(photoUrl.trim());
        photo.setCaption(caption == null || caption.isBlank() ? null : caption.trim());
        photo.setSortOrder(maxSort + 1);
        photo.setCreateTime(LocalDateTime.now());
        photoMapper.insert(photo);

        Album album = albumMapper.selectById(albumId);
        album.setUpdateTime(LocalDateTime.now());
        albumMapper.updateById(album);
        return photo.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removePhoto(Long photoId, Long userId) {
        Photo photo = photoMapper.selectById(photoId);
        if (photo == null) {
            throw new BusinessException("照片不存在");
        }
        checkOwner(photo.getAlbumId(), userId);
        photoMapper.deleteById(photoId);
    }

    /** 校验相册归属，不属于当前用户则拒绝 */
    private void checkOwner(Long albumId, Long userId) {
        getOwnedAlbum(albumId, userId);
    }

    private Album getOwnedAlbum(Long albumId, Long userId) {
        if (albumId == null) {
            throw new BusinessException("相册不存在");
        }
        Album album = albumMapper.selectById(albumId);
        if (album == null || !userId.equals(album.getUserId())) {
            throw new BusinessException("相册不存在或无权访问");
        }
        return album;
    }
}
