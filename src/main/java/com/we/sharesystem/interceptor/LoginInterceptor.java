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
        // 同路径的 PUT/DELETE（更新/删除）仍需登录，由后续 token 校验拦截
        if ("GET".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().matches(".*/moments/\\d+")) {
            return true;
        }
        // 话题列表为公开只读接口：GET /topics 放行；
        // 同路径的 POST /topics（新增话题）仍需登录，不再整段排除
        if ("GET".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().matches(".*/topics(/?|/\\d+)")) {
            return true;
        }
        String token = request.getHeader("Authorization");
        // 兼容两种写法：裸 token（前端现有实现）与标准 "Bearer xxx"（curl / Postman / 第三方调用）
        if (token != null) {
            token = token.trim();
            if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
                token = token.substring(7).trim();
            }
        }
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
}
