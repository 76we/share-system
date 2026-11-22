package com.we.sharesystem.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.we.sharesystem.common.Result;
import com.we.sharesystem.service.NotificationService;
import com.we.sharesystem.vo.NotificationVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("userId");
    }

    /** 我的通知列表 */
    @GetMapping
    public Result<Page<NotificationVO>> list(HttpServletRequest request,
                                             @RequestParam(defaultValue = "1") Integer page,
                                             @RequestParam(defaultValue = "20") Integer size) {
        return Result.ok(notificationService.listMine(userId(request), page, size));
    }

    /** 未读条数（铃铛角标） */
    @GetMapping("/unread")
    public Result<Long> unread(HttpServletRequest request) {
        return Result.ok(notificationService.unreadCount(userId(request)));
    }

    /** 标记单条已读 */
    @PostMapping("/read")
    public Result<Void> read(HttpServletRequest request, @RequestParam Long id) {
        notificationService.markRead(id, userId(request));
        return Result.ok(null);
    }

    /** 全部标记已读 */
    @PostMapping("/read-all")
    public Result<Void> readAll(HttpServletRequest request) {
        notificationService.markAllRead(userId(request));
        return Result.ok(null);
    }
}
