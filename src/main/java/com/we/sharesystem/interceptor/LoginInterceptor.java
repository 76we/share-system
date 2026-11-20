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
        String token = request.getHeader("Authorization");
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
