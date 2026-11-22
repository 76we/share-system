package com.we.sharesystem.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.common.Result;
import com.we.sharesystem.service.PhotoWallService;
import com.we.sharesystem.vo.WallPhotoVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 照片墙：我的视觉日记。
 * 点赞走现有 /likes 接口，传 targetType=3（照片）、targetId=WallPhotoVO.id。
 */
@RestController
@RequestMapping("/profile/photos")
@RequiredArgsConstructor
public class PhotoWallController {

    private final PhotoWallService photoWallService;

    /** GET /profile/photos —— 我的照片墙（需登录，userId 由 JWT 拦截器注入） */
    @GetMapping
    public Result<Page<WallPhotoVO>> myPhotos(HttpServletRequest request,
                                              @RequestParam(defaultValue = "1") Integer page,
                                              @RequestParam(defaultValue = "12") Integer size) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(photoWallService.myPhotos(userId, page, size));
    }

    /** GET /profile/photos/anniversary —— 那年今日：往年同一天发布的照片 */
    @GetMapping("/anniversary")
    public Result<Page<WallPhotoVO>> anniversary(HttpServletRequest request,
                                                  @RequestParam(defaultValue = "1") Integer page,
                                                  @RequestParam(defaultValue = "12") Integer size) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(photoWallService.anniversaryPhotos(userId, page, size));
    }
}
