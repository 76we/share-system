package com.we.sharesystem.utils;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtUtil 单测：签发/解析、篡改、过期、缺版本号 claim。
 * 这块是鉴权的唯一入口，改一次就值得回归一次。
 */
class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-key-at-least-32-bytes-long!!";

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expire", 3_600_000L);
    }

    @Test
    @DisplayName("签发的 token 能取回 userId 与 tokenVersion")
    void generateAndParse() {
        String token = jwtUtil.generateToken(42L, 7);
        assertThat(jwtUtil.getUserId(token)).isEqualTo(42L);
        assertThat(jwtUtil.getTokenVersion(token)).isEqualTo(7);
    }

    @Test
    @DisplayName("tokenVersion 为 null 时按 0 签发")
    void nullTokenVersionBecomesZero() {
        String token = jwtUtil.generateToken(1L, null);
        assertThat(jwtUtil.getTokenVersion(token)).isZero();
    }

    @Test
    @DisplayName("篡改过的 token 必须解析失败")
    void tamperedTokenRejected() {
        String token = jwtUtil.generateToken(1L, 0);
        // 改最后一个字符，且保证确实改动了（避免"原本就是 x"导致测试偶发通过）
        char last = token.charAt(token.length() - 1);
        char replaced = last == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replaced;
        assertThatThrownBy(() -> jwtUtil.getUserId(tampered)).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("过期 token 必须解析失败")
    void expiredTokenRejected() {
        ReflectionTestUtils.setField(jwtUtil, "expire", -60_000L);
        String expired = jwtUtil.generateToken(1L, 0);
        assertThatThrownBy(() -> jwtUtil.getUserId(expired)).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("老 token 没有 tv claim 时按 0 处理，不至于让存量登录全部失效")
    void missingTokenVersionClaimDefaultsToZero() {
        String legacy = Jwts.builder()
                .setSubject("9")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();
        assertThat(jwtUtil.getUserId(legacy)).isEqualTo(9L);
        assertThat(jwtUtil.getTokenVersion(legacy)).isZero();
    }
}
