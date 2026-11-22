package com.we.sharesystem.controller;

import com.we.sharesystem.common.Result;
import com.we.sharesystem.service.AlbumService;
import com.we.sharesystem.vo.AlbumVO;
import com.we.sharesystem.vo.PhotoVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/albums")
public class AlbumController {

    @Autowired
    private AlbumService albumService;

    /** 相册列表（含封面前三张与照片数） */
    @GetMapping
    public Result<List<AlbumVO>> list() {
        return Result.ok(albumService.listAlbums());
    }

    /** 相册内全部照片 */
    @GetMapping("/{albumId}/photos")
    public Result<List<PhotoVO>> photos(@PathVariable Long albumId) {
        return Result.ok(albumService.listPhotos(albumId));
    }
}
