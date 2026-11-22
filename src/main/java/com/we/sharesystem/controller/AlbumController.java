package com.we.sharesystem.controller;

import com.we.sharesystem.common.Result;
import com.we.sharesystem.service.AlbumService;
import com.we.sharesystem.vo.AlbumVO;
import com.we.sharesystem.vo.PhotoVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/albums")
public class AlbumController {

    @Autowired
    private AlbumService albumService;

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("userId");
    }

    /** 我的相册列表（含封面前三张与照片数） */
    @GetMapping
    public Result<List<AlbumVO>> list(HttpServletRequest request) {
        return Result.ok(albumService.listAlbums(userId(request)));
    }

    /** 我的相册内全部照片 */
    @GetMapping("/{albumId}/photos")
    public Result<List<PhotoVO>> photos(@PathVariable Long albumId, HttpServletRequest request) {
        return Result.ok(albumService.listPhotos(albumId, userId(request)));
    }

    /** 新建相册 */
    @PostMapping
    public Result<Long> create(HttpServletRequest request,
                               @RequestParam String title,
                               @RequestParam(required = false) String description) {
        return Result.ok(albumService.createAlbum(userId(request), title, description));
    }

    /** 修改相册标题/简介 */
    @PutMapping("/{albumId}")
    public Result<Void> update(@PathVariable Long albumId, HttpServletRequest request,
                               @RequestParam(required = false) String title,
                               @RequestParam(required = false) String description) {
        albumService.updateAlbum(albumId, userId(request), title, description);
        return Result.ok(null);
    }

    /** 删除相册（连同照片） */
    @DeleteMapping("/{albumId}")
    public Result<Void> delete(@PathVariable Long albumId, HttpServletRequest request) {
        albumService.deleteAlbum(albumId, userId(request));
        return Result.ok(null);
    }

    /** 从照片墙选一张图加入相册 */
    @PostMapping("/{albumId}/photos")
    public Result<Long> addPhoto(@PathVariable Long albumId, HttpServletRequest request,
                                 @RequestParam String photoUrl,
                                 @RequestParam(required = false) String caption) {
        return Result.ok(albumService.addPhoto(albumId, userId(request), photoUrl, caption));
    }

    /** 删除相册内一张照片 */
    @DeleteMapping("/photos/{photoId}")
    public Result<Void> removePhoto(@PathVariable Long photoId, HttpServletRequest request) {
        albumService.removePhoto(photoId, userId(request));
        return Result.ok(null);
    }
}
