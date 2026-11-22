package com.we.sharesystem.service.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.we.sharesystem.entity.Album;
import com.we.sharesystem.entity.Photo;
import com.we.sharesystem.mapper.AlbumMapper;
import com.we.sharesystem.mapper.PhotoMapper;
import com.we.sharesystem.service.AlbumService;
import com.we.sharesystem.vo.AlbumVO;
import com.we.sharesystem.vo.PhotoVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
    public List<AlbumVO> listAlbums() {
        List<Album> albums = albumMapper.selectList(
                new LambdaQueryWrapper<Album>()
                        .orderByAsc(Album::getSort)
                        .orderByDesc(Album::getUpdateTime));
        if (albums.isEmpty()) {
            return Collections.emptyList();
        }

        // 一次性取出全部照片再分组，避免逐相册查询
        List<Photo> allPhotos = photoMapper.selectList(
                new LambdaQueryWrapper<Photo>()
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
    public List<PhotoVO> listPhotos(Long albumId) {
        if (albumId == null) {
            return Collections.emptyList();
        }
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
}
