package com.we.sharesystem.common;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感词 DFA：命中、未命中，以及「加空格/分隔符」这种最常见的绕过写法。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SensitiveWordFilterTest {

    private final SensitiveWordFilter filter = new SensitiveWordFilter();

    @BeforeAll
    void init() {
        // 单测不启动 Spring，手动触发 @PostConstruct 那一步把词库建树
        filter.init();
    }

    @Test
    @DisplayName("正常内容不命中")
    void normalTextPasses() {
        assertThat(filter.hit("今天去公园散步，拍了几张照片")).isFalse();
        assertThat(filter.hit("")).isFalse();
        assertThat(filter.hit((String) null)).isFalse();
    }

    @Test
    @DisplayName("命中文档里的敏感词")
    void hitsDictionaryWord() {
        assertThat(filter.hit("有兴趣的私聊，可以刷单")).isTrue();
    }

    @Test
    @DisplayName("加空格或分隔符的变体同样能拦住")
    void hitsObfuscatedVariant() {
        assertThat(filter.hit("刷 单")).isTrue();
        assertThat(filter.hit("刷-单")).isTrue();
        assertThat(filter.hit("刷.单")).isTrue();
    }

    @Test
    @DisplayName("命中时能返回具体词（仅用于日志排查）")
    void firstHitReturnsWord() {
        assertThat(filter.firstHit("这里有博彩广告")).isEqualTo("博彩");
    }
}
