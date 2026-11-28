package com.we.sharesystem.config;

import com.we.sharesystem.common.PublicEndpoints;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口文档配置。
 *
 * <p>访问地址：http://localhost:8080/swagger-ui/index.html
 * <br>规范原文（JSON）：http://localhost:8080/v3/api-docs
 *
 * <p>为什么要有这个类：springdoc 能把 Controller 扫描成 OpenAPI 规范，
 * 但它不知道你用什么方式做认证 —— 没有下面这段声明，Swagger UI 右上角就没有
 * Authorize 按钮，所有需要登录的接口在页面上都点不通，只能手动往每个请求里粘 header，
 * 那这个页面就废了一半。
 *
 * <p>这里声明的是一个 HTTP Bearer 方案，对应 LoginInterceptor 从
 * {@code Authorization: Bearer <token>} 里取 token 的做法。因为配了全局
 * SecurityRequirement，页面上每个接口都会带锁标 —— 包括 /moments/list 这类
 * 本来匿名可访问的接口。这是有意的取舍：让「哪些接口公开」由 excludePathPatterns
 * 单独维护，不在这里再写一遍，免得两处不同步。
 */
@Configuration
public class OpenApiConfig {

    /** 方案名，必须与 SecurityRequirement 里引用的名字一致 */
    private static final String SCHEME_NAME = "bearerAuth";

    /**
     * 分组显示名。
     *
     * <p>springdoc 默认拿 Controller 的类名当分组名，页面上会显示成
     * {@code moment-controller}、{@code photo-wall-controller} 这种。
     * 功能上没错，但这份文档是给别人看的，中英文混杂且按字母序排，
     * 读起来像一串内部代号。
     *
     * <p>这里用 LinkedHashMap 同时解决两件事：改名、定顺序。
     * 顺序按「第一次打开页面最可能点的」排：先登录，再看动态，然后才是相册话题。
     * 之所以不逐个 Controller 加 {@code @Tag}，是因为那样要改 12 个文件，
     * 而分组名是展示层的事，集中在这一处更好改。
     */
    private static final Map<String, String> TAG_NAMES = new LinkedHashMap<>();

    static {
        TAG_NAMES.put("user-controller", "用户与认证");
        TAG_NAMES.put("moment-controller", "动态");
        TAG_NAMES.put("comment-controller", "评论");
        TAG_NAMES.put("like-controller", "点赞");
        TAG_NAMES.put("collect-controller", "收藏");
        TAG_NAMES.put("friend-controller", "好友");
        TAG_NAMES.put("notification-controller", "通知");
        TAG_NAMES.put("album-controller", "相册");
        TAG_NAMES.put("topic-controller", "话题");
        TAG_NAMES.put("photo-wall-controller", "照片墙");
        TAG_NAMES.put("profile-controller", "个人中心");
        TAG_NAMES.put("share-controller", "分享页");
    }

    @Bean
    public OpenAPI shareSystemOpenAPI() {
        List<Tag> tags = TAG_NAMES.values().stream().map(n -> new Tag().name(n)).toList();
        return new OpenAPI()
                .info(new Info()
                        .title("尘叙阁 · share-system API")
                        .version("1.0.0")
                        .description("""
                                图文分享社区后端接口。

                                **怎么用**：先调 `POST /users/login` 拿到 token，
                                点右上角 Authorize 填入（只填 token 本身，不要带 `Bearer ` 前缀），
                                之后所有请求会自动带上 `Authorization` 头。

                                **注意**：生产环境默认关闭本文档（见 application-prod.yml），
                                需要时用环境变量 `SPRINGDOC_ENABLED=true` 临时打开，
                                并确保 nginx 侧有 IP 限制。
                                """))
                .tags(tags)
                .components(new Components()
                        .addSecuritySchemes(SCHEME_NAME, new SecurityScheme()
                                .name(SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 token")))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME));
    }

    /**
     * 两件事：把英文分组名换成中文；把匿名接口的鉴权锁标去掉。
     *
     * <p>为什么不在每个操作上逐个写 {@code @SecurityRequirement}：60 个接口里
     * 48 个都要鉴权，写 48 遍只会让 Controller 里堆满注解。这里用「全局声明 + 按白名单豁免」
     * 的做法，白名单直接复用 {@link PublicEndpoints}，与拦截器共用同一份配置。
     *
     * <p>置空用 {@code new ArrayList<>()} 而不是 {@code null}：
     * OpenAPI 规范里操作级别的 {@code security: []} 表示「覆盖全局、本操作无需鉴权」，
     * 而 {@code null} 表示「未声明」，会继续继承全局要求。
     */
    @Bean
    public GlobalOpenApiCustomizer customizeOperations() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> {
                boolean open = PublicEndpoints.isPublic(path);
                item.readOperations().forEach(op -> {
                    List<String> raw = op.getTags();
                    if (raw != null && !raw.isEmpty()) {
                        op.setTags(raw.stream().map(t -> TAG_NAMES.getOrDefault(t, t)).distinct().toList());
                    }
                    if (open) {
                        op.setSecurity(new ArrayList<>());
                    }
                });
            });
        };
    }
}
