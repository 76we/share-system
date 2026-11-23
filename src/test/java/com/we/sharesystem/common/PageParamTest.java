package com.we.sharesystem.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页参数归一化：page=0 / 负数曾经会把 (page-1)*size 算成负的 from，
 * 在照片墙里直接 subList 越界、在 SQL 里直接 offset 非法，用户看到的是 500。
 */
class PageParamTest {

    @Test
    @DisplayName("page 为 0、负数、null 一律归一为 1")
    void pageNeverBelowOne() {
        assertThat(PageParam.page(0)).isEqualTo(1);
        assertThat(PageParam.page(-5)).isEqualTo(1);
        assertThat(PageParam.page(null)).isEqualTo(1);
    }

    @Test
    @DisplayName("page 超过上限时截断，避免深翻页打库")
    void pageCappedAtMax() {
        assertThat(PageParam.page(PageParam.MAX_PAGE + 1)).isEqualTo(PageParam.MAX_PAGE);
    }

    @Test
    @DisplayName("size 缺失或非法时回落到默认值")
    void sizeFallsBackToDefault() {
        assertThat(PageParam.size(null, 12)).isEqualTo(12);
        assertThat(PageParam.size(0, 12)).isEqualTo(12);
        assertThat(PageParam.size(-1, 20)).isEqualTo(20);
    }

    @Test
    @DisplayName("size 超过上限时截断，防止 ?size=100000")
    void sizeCappedAtMax() {
        assertThat(PageParam.size(100_000, 10)).isEqualTo(PageParam.MAX_SIZE);
    }

    @Test
    @DisplayName("limit 型参数被钳制在 [min, max]")
    void limitClamped() {
        assertThat(PageParam.limit(null, 6, 1, 50)).isEqualTo(6);
        assertThat(PageParam.limit(0, 6, 1, 50)).isEqualTo(1);
        assertThat(PageParam.limit(999, 6, 1, 50)).isEqualTo(50);
        assertThat(PageParam.limit(8, 6, 1, 50)).isEqualTo(8);
    }
}
