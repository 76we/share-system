package com.we.sharesystem.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.common.Result;
import com.we.sharesystem.service.PhotoWallService;
import com.we.sharesystem.vo.FriendWallVO;
import com.we.sharesystem.vo.PhotoMonthGroupVO;
import com.we.sharesystem.vo.WallPhotoVO;

import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
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

    /** GET /profile/photos/groups —— 我的照片墙，按月分区间（月份导航视图） */
    @GetMapping("/groups")
    public Result<List<PhotoMonthGroupVO>> myPhotosGrouped(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(photoWallService.myPhotosGrouped(userId));
    }

    /** GET /profile/photos/anniversary/groups —— 那年今日，按月分区间 */
    @GetMapping("/anniversary/groups")
    public Result<List<PhotoMonthGroupVO>> anniversaryGrouped(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(photoWallService.anniversaryPhotosGrouped(userId));
    }

    /** GET /profile/photos/user/{userId}/groups —— 访问 TA 的照片墙，按月分区间 */
    @GetMapping("/user/{userId}/groups")
    public Result<List<PhotoMonthGroupVO>> otherPhotosGrouped(HttpServletRequest request,
                                                              @PathVariable Long userId) {
        Long viewerId = (Long) request.getAttribute("userId");
        return Result.ok(photoWallService.otherPhotosGrouped(userId, viewerId));
    }

    /** GET /profile/photos/year/{year}/groups —— 年度回忆册：某年照片按月分区间 */
    @GetMapping("/year/{year}/groups")
    public Result<List<PhotoMonthGroupVO>> yearGrouped(HttpServletRequest request,
                                                       @PathVariable Integer year) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(photoWallService.yearPhotosGrouped(userId, year));
    }

    /** GET /profile/photos/visibility —— 读取我的照片墙可见性（0私密 1公开 2仅好友） */
    @GetMapping("/visibility")
    public Result<Integer> getVisibility(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(photoWallService.getVisibility(userId));
    }

    /** PUT /profile/photos/visibility?visibility=2 —— 设置我的照片墙可见性（0私密 1公开 2仅好友） */
    @PutMapping("/visibility")
    public Result<Void> setVisibility(HttpServletRequest request,
                                      @RequestParam Integer visibility) {
        Long userId = (Long) request.getAttribute("userId");
        photoWallService.setVisibility(userId, visibility);
        return Result.ok(null);
    }

    /** GET /profile/photos/friends —— 好友墙聚合：每位好友近期照片（需登录） */
    @GetMapping("/friends")
    public Result<List<FriendWallVO>> friendPhotos(HttpServletRequest request,
                                                   @RequestParam(defaultValue = "6") Integer limit) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(photoWallService.friendPhotos(userId, limit));
    }

    /** GET /profile/photos/user/{userId} —— 访问 TA 的照片墙（私密则拒绝，本人不受限） */
    @GetMapping("/user/{userId}")
    public Result<Page<WallPhotoVO>> otherPhotos(HttpServletRequest request,
                                                 @PathVariable Long userId,
                                                 @RequestParam(defaultValue = "1") Integer page,
                                                 @RequestParam(defaultValue = "12") Integer size) {
        Long viewerId = (Long) request.getAttribute("userId");
        return Result.ok(photoWallService.otherPhotos(userId, viewerId, page, size));
    }
}
