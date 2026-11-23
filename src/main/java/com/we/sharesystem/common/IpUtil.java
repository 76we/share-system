package com.we.sharesystem.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 取真实客户端 IP。
 *
 * 为什么不能直接用 X-Forwarded-For 的第一段：
 * nginx 的 $proxy_add_x_forwarded_for 会把「客户端自带的 XFF」原样拼在最前面，
 * 于是第一段完全由攻击者控制 —— 每次换一个假 IP 就能绕过登录失败锁定、
 * 伪造浏览量去重标识。只有最后一段是离我们最近的代理追加的，可信。
 *
 * 生产 nginx 已用 $remote_addr 覆盖写入 X-Real-IP（客户端无法伪造），
 * 所以优先读它；没有该头时才退到 XFF 最后一段，再退到 remoteAddr。
 */
public final class IpUtil {

    private IpUtil() {
    }

    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String ip = trim(request.getHeader("X-Real-IP"));
        if (ip.isEmpty()) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] parts = xff.split(",");
                ip = trim(parts[parts.length - 1]);
            }
        }
        if (ip.isEmpty()) {
            ip = trim(request.getRemoteAddr());
        }
        if (ip.isEmpty()) {
            return "unknown";
        }
        // 本地联调：IPv6 回环统一成 127.0.0.1，避免 ::1 与 127.0.0.1 被当成两个 IP 计数
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
