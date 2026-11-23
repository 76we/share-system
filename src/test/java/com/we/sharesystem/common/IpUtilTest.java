package com.we.sharesystem.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端 IP 取值。
 * 关键点：X-Forwarded-For 的第一段由客户端自带（nginx 的 $proxy_add_x_forwarded_for 会原样拼接），
 * 拿它当 IP 等于让攻击者随便换身份，所以这里断言「X-Real-IP 优先」与「XFF 取最后一段」。
 */
class IpUtilTest {

    @Test
    @DisplayName("有 X-Real-IP 时以它为准，忽略 XFF 里伪造的第一段")
    void realIpWins() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 7.7.7.7");
        request.addHeader("X-Real-IP", "203.0.113.9");

        assertThat(IpUtil.clientIp(request)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("没有 X-Real-IP 时取 XFF 最后一段，而不是可伪造的第一段")
    void fallsBackToLastXffHop() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2, 3.3.3.3");

        assertThat(IpUtil.clientIp(request)).isEqualTo("3.3.3.3");
    }

    @Test
    @DisplayName("没有任何代理头时用 remoteAddr")
    void fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.10");

        assertThat(IpUtil.clientIp(request)).isEqualTo("192.168.1.10");
    }

    @Test
    @DisplayName("IPv6 回环统一成 127.0.0.1，避免同一台机器被算成两个 IP")
    void ipv6LoopbackNormalized() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("0:0:0:0:0:0:0:1");

        assertThat(IpUtil.clientIp(request)).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("请求为 null 或地址全空时不抛异常")
    void neverThrows() {
        assertThat(IpUtil.clientIp(null)).isEqualTo("unknown");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("  ");
        assertThat(IpUtil.clientIp(request)).isEqualTo("unknown");
    }
}
