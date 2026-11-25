package com.we.sharesystem.config;


import com.we.sharesystem.interceptor.LoginInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private LoginInterceptor loginInterceptor;

    /** 上传目录：与 FileUtil 共用同一个配置项，避免图片写在一处、映射指向另一处 */
    @Value("${file.upload-dir:D:/uploads/}")
    private String uploadDir;

    /**
     * 允许跨域的来源，逗号分隔。生产同域部署（nginx 反代）时可留空，即完全不开放跨域。
     */
    @Value("${app.cors.allowed-origins:}")
    private String corsOrigins;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
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
                        "/error"
                );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // addResourceLocations 要求以 / 结尾
        String location = uploadDir.endsWith("/") ? uploadDir : uploadDir + "/";
        registry.addResourceHandler("/images/**")
                .addResourceLocations("file:" + location);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (corsOrigins == null || corsOrigins.isBlank()) {
            // 未配置来源 = 不注册跨域规则（生产走 nginx 同源，不需要 CORS）
            return;
        }
        String[] origins = corsOrigins.split(",");
        for (int i = 0; i < origins.length; i++) {
            origins[i] = origins[i].trim();
        }
        registry.addMapping("/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
