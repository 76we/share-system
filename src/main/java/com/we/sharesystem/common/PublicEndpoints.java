package com.we.sharesystem.common;

import java.util.List;

/**
 * 匿名可访问的接口清单。
 *
 * <p>这是唯一事实来源：{@code WebConfig} 的拦截器按它放行，{@code OpenApiConfig}
 * 按同一个清单去掉文档里的锁标。分两处写的问题很实际 —— 今天放行了一个接口，
 * 忘了同步文档，面试官在 Swagger 上看到它带锁，就会以为「这接口要登录」，
 * 然后试一下发现能通，比没写文档更糟。
 *
 * <p>规则：Spring 的 Ant 风格，本项目只用到两种写法 ——
 * 精确路径（{@code /users/login}）和末尾通配（{@code /share/**}）。
 * 更复杂的通配（中间 {@code *}、{@code ?}）没有用到，也不建议加，
 * 因为 {@link #isPublic(String)} 只实现了这两种匹配。
 */
public final class PublicEndpoints {

    private PublicEndpoints() {
    }

    public static final List<String> PATTERNS = List.of(
            "/users/register",
            "/users/login",
            // 注册邮箱验证码：未登录也必须能请求
            "/users/email-code",
            // 找回密码：未登录也必须能请求
            "/users/password-code",
            "/users/reset-password",
            "/moments/list",
            "/moments/search",
            "/likes/count",
            "/collects/count",
            "/images/**",
            // 健康检查：给 systemd / 外部监控用，不需要登录。
            // 只暴露 health（见 application.yml 的 management.endpoints）
            "/actuator/**",
            // 分享落地页必须匿名可访问：微信爬虫不会带 token
            "/share/**",
            // 接口文档本身：HTML/JS/CSS 都是静态资源，拦下来只会得到一片空白
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/webjars/**",
            "/error"
    );

    /**
     * 判断某个 OpenAPI 里的路径是否匿名可访问。
     *
     * <p>注意传入的是路径模板（{@code /share/moment/{id}}），不是实际请求路径，
     * 所以只做字符串前缀/相等比较，不做变量段匹配 —— 对当前这套规则足够。
     */
    public static boolean isPublic(String path) {
        if (path == null) {
            return false;
        }
        for (String p : PATTERNS) {
            if (p.endsWith("/**")) {
                // "/share/**" -> 前缀 "/share"
                if (path.startsWith(p.substring(0, p.length() - 3))
                        || path.equals(p.substring(0, p.length() - 3))) {
                    return true;
                }
            } else if (p.equals(path)) {
                return true;
            }
        }
        return false;
    }
}
