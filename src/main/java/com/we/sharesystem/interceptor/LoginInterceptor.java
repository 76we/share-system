package com.we.sharesystem.interceptor;


import com.we.sharesystem.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS 预检请求（OPTIONS）不携带 Authorization，直接放行，
        // 否则浏览器预检失败，所有带自定义头的跨域请求都会被拒
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return true;
        }
        // 动态详情为公开只读接口：GET /moments/{id} 放行；
        // 同路径的 PUT/DELETE（更新/删除）仍需登录，由后续 token 校验拦截。
        // 带了合法 token 时仍解析出 userId（浏览量按用户去重需要），解析失败不影响访问
        if ("GET".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().matches(".*/moments/\\d+")) {
            attachUserIdIfPresent(request);
            return true;
        }
        // 话题列表为公开只读接口：GET /topics 放行；
        // 同路径的 POST /topics（新增话题）仍需登录，不再整段排除
        if ("GET".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().matches(".*/topics(/?|/\\d+)")) {
            return true;
        }
        // 评论列表为公开只读接口：GET /comments 放行（游客看动态详情也该看到评论）；
        // 同路径的 POST/DELETE（发表/删除评论）仍需登录
        if ("GET".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().matches(".*/comments(/?.*)")) {
            return true;
        }
        String token = resolveToken(request);
        if(token==null || token.isBlank()){
            response.setStatus(401);
            return false;
        }
        try {
            Long userId = jwtUtil.getUserId(token);
            request.setAttribute("userId",userId);
            return true;
        }catch (Exception e){
            response.setStatus(401);
            return false;
        }
    }

    /** 从 Authorization 头取 token，兼容裸 token 与标准的 "Bearer xxx" */
    private String resolveToken(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token != null) {
            token = token.trim();
            if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
                token = token.substring(7).trim();
            }
        }
        return token;
    }

    /** 公开接口上：带了合法 token 就顺带解析出 userId，失败静默忽略 */
    private void attachUserIdIfPresent(HttpServletRequest request) {
        String token = resolveToken(request);
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            request.setAttribute("userId", jwtUtil.getUserId(token));
        } catch (Exception ignored) {
            // token 失效/被改：当游客处理即可
        }
    }
}
