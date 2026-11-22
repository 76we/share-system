package com.we.sharesystem.controller;

import com.we.sharesystem.service.MomentService;
import com.we.sharesystem.vo.MomentDetailVO;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * 分享落地页。
 *
 * 背景：微信 / QQ / 微博的链接预览由爬虫抓取，爬虫不执行 JS，
 * 所以 SPA 的 /detail/:id 被分享出去永远是一张白卡片。
 * 这里按 UA 分流：
 *   - 爬虫 → 返回带 og:title / og:description / og:image 的静态 HTML
 *   - 真人 → 302 到前端详情页，不让他看到这层中转页
 *
 * 分享链接形如 https://域名/api/share/moment/{id}（/api 前缀由 nginx 剥掉）。
 */
@RestController
@RequestMapping("/share")
public class ShareController {

    private static final String SITE_NAME = "尘叙阁";

    /** 常见抓取 UA 特征（小写比对）。命中即视为爬虫。 */
    private static final String[] CRAWLER_UA = {
            "micromessenger", "wechat", "qq/", "mqqbrowser", "weibo",
            "twitterbot", "facebookexternalhit", "telegrambot", "slackbot",
            "discordbot", "linkedinbot", "pinterest", "whatsapp", "line/",
            "baiduspider", "bytespider", "googlebot", "bingbot", "yandexbot"
    };

    @Resource
    private MomentService momentService;

    /** 对外可访问的站点根地址，例如 https://chenxu.example.com；留空则按请求自动推导 */
    @Value("${app.share.base-url:}")
    private String baseUrl;

    @GetMapping(value = "/moment/{id}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> shareMoment(@PathVariable Long id, HttpServletRequest request) {
        // 真人直接进详情页
        if (!isCrawler(request.getHeader("User-Agent"))) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, "/detail/" + id)
                    .build();
        }

        MomentDetailVO vo;
        try {
            vo = momentService.detail(id);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                    .body("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>内容不存在</title></head>"
                            + "<body style=\"font-family:sans-serif;text-align:center;padding-top:80px\">"
                            + "内容不存在或已删除</body></html>");
        }

        String origin = resolveBase(request);
        String title = buildTitle(vo);
        String desc = buildDesc(vo);
        String cover = buildCover(vo, origin);
        String pageUrl = origin + "/api/share/moment/" + id;

        StringBuilder sb = new StringBuilder(600);
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head>")
                .append("<meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(esc(title)).append(" · ").append(SITE_NAME).append("</title>")
                .append("<meta name=\"description\" content=\"").append(esc(desc)).append("\">")
                // Open Graph：微信 / QQ / 微博 走这一套
                .append("<meta property=\"og:type\" content=\"article\">")
                .append("<meta property=\"og:site_name\" content=\"").append(SITE_NAME).append("\">")
                .append("<meta property=\"og:title\" content=\"").append(esc(title)).append("\">")
                .append("<meta property=\"og:description\" content=\"").append(esc(desc)).append("\">")
                .append("<meta property=\"og:url\" content=\"").append(esc(pageUrl)).append("\">")
                .append("<meta property=\"og:locale\" content=\"zh_CN\">");
        if (!cover.isEmpty()) {
            // 微信用 og:image 生成卡片缩略，尺寸建议不小于 300x300
            sb.append("<meta property=\"og:image\" content=\"").append(esc(cover)).append("\">")
                    .append("<meta name=\"twitter:card\" content=\"summary_large_image\">")
                    .append("<meta name=\"twitter:image\" content=\"").append(esc(cover)).append("\">");
        }
        sb.append("</head><body>")
                .append("<p>").append(esc(title)).append("</p>")
                .append("<p>").append(esc(desc)).append("</p>")
                .append("</body></html>");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(sb.toString());
    }

    private boolean isCrawler(String ua) {
        if (ua == null || ua.isBlank()) {
            return false;
        }
        String lower = ua.toLowerCase(Locale.ROOT);
        for (String key : CRAWLER_UA) {
            if (lower.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** 站点根地址：优先配置项，其次按请求头推导（含 nginx 转发的原始 scheme） */
    private String resolveBase(HttpServletRequest request) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.replaceAll("/+$", "");
        }
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = request.getScheme();
        }
        int port = request.getServerPort();
        String portPart = (port == 80 || port == 443) ? "" : ":" + port;
        return scheme + "://" + request.getServerName() + portPart;
    }

    private String buildTitle(MomentDetailVO vo) {
        if (vo.getTitle() != null && !vo.getTitle().isBlank()) {
            return truncate(vo.getTitle().trim(), 40);
        }
        String content = vo.getContent();
        if (content != null && !content.isBlank()) {
            return truncate(content.replaceAll("\\s+", " ").trim(), 24);
        }
        return "一段未命名的分享";
    }

    private String buildDesc(MomentDetailVO vo) {
        String content = vo.getContent();
        String desc = (content == null) ? "" : content.replaceAll("\\s+", " ").trim();
        if (desc.isEmpty()) {
            desc = (vo.getNickname() == null ? "有人" : vo.getNickname()) + " 在尘叙阁留下了一段分享";
        }
        return truncate(desc, 80);
    }

    /** 封面取第一张图；没有图返回空（卡片退化为纯文字，不会破） */
    private String buildCover(MomentDetailVO vo, String origin) {
        List<String> images = vo.getImages();
        if (images == null || images.isEmpty()) {
            return "";
        }
        String first = images.get(0);
        if (first == null || first.isBlank()) {
            return "";
        }
        return first.startsWith("http") ? first : origin + first;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** HTML / 属性值转义：标题里带引号或尖括号会直接把 meta 标签撑破 */
    private String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
