package com.we.sharesystem.controller;

import com.we.sharesystem.common.Result;
import com.we.sharesystem.service.FriendshipService;
import com.we.sharesystem.vo.FriendSearchVO;
import com.we.sharesystem.vo.FriendVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 好友关系（双向，双方同意）。
 * 最小闭环：搜人 → 申请 → 对方同意/拒绝 → 成好友。
 * 全部需登录，userId 由 JWT 拦截器注入。
 */
@RestController
@RequestMapping("/friendships")
@RequiredArgsConstructor
public class FriendController {

    private final FriendshipService friendshipService;

    private Long meId(HttpServletRequest request) {
        return (Long) request.getAttribute("userId");
    }

    /** GET /friendships/search?keyword= —— 模糊搜人（昵称/用户名），排除自己，标注与我的关系 */
    @GetMapping("/search")
    public Result<List<FriendSearchVO>> search(HttpServletRequest request, @RequestParam String keyword) {
        return Result.ok(friendshipService.searchUser(meId(request), keyword));
    }

    /** POST /friendships/request?userId= —— 向 TA 发起好友申请 */
    @PostMapping("/request")
    public Result<Void> request(HttpServletRequest request, @RequestParam Long userId) {
        friendshipService.request(meId(request), userId);
        return Result.ok(null);
    }

    /** POST /friendships/accept?userId= —— 同意 userId 对我的申请 */
    @PostMapping("/accept")
    public Result<Void> accept(HttpServletRequest request, @RequestParam Long userId) {
        friendshipService.accept(meId(request), userId);
        return Result.ok(null);
    }

    /** POST /friendships/reject?userId= —— 拒绝 userId 对我的申请 */
    @PostMapping("/reject")
    public Result<Void> reject(HttpServletRequest request, @RequestParam Long userId) {
        friendshipService.reject(meId(request), userId);
        return Result.ok(null);
    }

    /** GET /friendships —— 我的好友列表（status=1） */
    @GetMapping
    public Result<List<FriendVO>> myFriends(HttpServletRequest request) {
        return Result.ok(friendshipService.myFriends(meId(request)));
    }

    /** GET /friendships/incoming —— 我收到的待处理申请 */
    @GetMapping("/incoming")
    public Result<List<FriendVO>> incoming(HttpServletRequest request) {
        return Result.ok(friendshipService.incomingRequests(meId(request)));
    }

    /** GET /friendships/sent —— 我发出的待处理申请 */
    @GetMapping("/sent")
    public Result<List<FriendVO>> sent(HttpServletRequest request) {
        return Result.ok(friendshipService.mySentRequests(meId(request)));
    }
}
