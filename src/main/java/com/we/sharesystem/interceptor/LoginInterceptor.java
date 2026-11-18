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
        String token = request.getHeader("Authorization");
        if(token==null || token.isBlank()){
            response.setStatus(401);
            return false;
        }
        try {
            Long userId = jwtUtil.getUserId(token);
            request.setAttribute("UserId",userId);
            return true;
        }catch (Exception e){
            response.setStatus(401);
            return false;
        }
    }
}
