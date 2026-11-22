package com.we.sharesystem.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

@Component
public class JwtUtil {

    /** token 里携带的用户密码版本号 claim：与 user.token_version 不一致即视为失效 */
    private static final String CLAIM_TOKEN_VERSION = "tv";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expire}")
    private Long expire;

    private Key key(){
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 token：除用户 id 外再带上密码版本号。
     * 用户改密码后 version +1，旧 token 里的 tv 对不上，拦截器直接拒绝，
     * 不需要维护一份黑名单。
     */
    public String generateToken(Long userId, Integer tokenVersion){
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim(CLAIM_TOKEN_VERSION, tokenVersion == null ? 0 : tokenVersion)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expire))
                .signWith(key(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Long getUserId(String token){
        return Long.valueOf(parse(token).getSubject());
    }

    /** 取 token 里的密码版本号；老 token 没有该 claim，按 0 处理（发版前签发的 token 不至于全部作废） */
    public Integer getTokenVersion(String token){
        Object tv = parse(token).get(CLAIM_TOKEN_VERSION);
        if (tv == null) {
            return 0;
        }
        if (tv instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(tv.toString());
    }

    private Claims parse(String token){
        return Jwts.parserBuilder()
                .setSigningKey(key())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
