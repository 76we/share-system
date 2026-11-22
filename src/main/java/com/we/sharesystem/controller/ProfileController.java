package com.we.sharesystem.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.common.Result;
import com.we.sharesystem.dto.PasswordUpdateDTO;
import com.we.sharesystem.dto.ProfileUpdateDTO;
import com.we.sharesystem.service.ProfileService;
import com.we.sharesystem.vo.MomentVO;
import com.we.sharesystem.vo.UserProfileVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 个人中心接口：所有接口都需登录，userId 由 JWT 拦截器从 token 解析 */
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    /** GET /profile —— 我的资料 + 统计 */
    @GetMapping
    public Result<UserProfileVO> me(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(profileService.getProfile(userId));
    }

    /** PUT /profile —— 编辑资料 */
    @PutMapping
    public Result<Void> update(HttpServletRequest request, @RequestBody @Valid ProfileUpdateDTO dto) {
        Long userId = (Long) request.getAttribute("userId");
        profileService.updateProfile(userId, dto);
        return Result.ok(null);
    }

    /** PUT /profile/password —— 修改密码 */
    @PutMapping("/password")
    public Result<Void> changePassword(HttpServletRequest request, @RequestBody @Valid PasswordUpdateDTO dto) {
        Long userId = (Long) request.getAttribute("userId");
        profileService.changePassword(userId, dto);
        return Result.ok(null);
    }

    /** GET /profile/moments —— 我发布的动态 */
    @GetMapping("/moments")
    public Result<Page<MomentVO>> myMoments(HttpServletRequest request,
                                            @RequestParam(defaultValue = "1") Integer page,
                                            @RequestParam(defaultValue = "10") Integer size) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(profileService.myMoments(userId, page, size));
    }

    /** GET /profile/user/{userId} —— TA 的资料 + 统计（屏蔽邮箱） */
    @GetMapping("/user/{userId}")
    public Result<UserProfileVO> publicProfile(@PathVariable Long userId) {
        return Result.ok(profileService.getPublicProfile(userId));
    }

    /** GET /profile/user/{userId}/moments —— TA 发布的动态 */
    @GetMapping("/user/{userId}/moments")
    public Result<Page<MomentVO>> publicMoments(@PathVariable Long userId,
                                                @RequestParam(defaultValue = "1") Integer page,
                                                @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(profileService.publicMoments(userId, page, size));
    }

    /** GET /profile/likes —— 我点赞的动态 */
    @GetMapping("/likes")
    public Result<Page<MomentVO>> myLikes(HttpServletRequest request,
                                          @RequestParam(defaultValue = "1") Integer page,
                                          @RequestParam(defaultValue = "10") Integer size) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(profileService.myLikedMoments(userId, page, size));
    }

    /** GET /profile/collects —— 我收藏的动态 */
    @GetMapping("/collects")
    public Result<Page<MomentVO>> myCollects(HttpServletRequest request,
                                             @RequestParam(defaultValue = "1") Integer page,
                                             @RequestParam(defaultValue = "10") Integer size) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.ok(profileService.myCollectedMoments(userId, page, size));
    }
}
