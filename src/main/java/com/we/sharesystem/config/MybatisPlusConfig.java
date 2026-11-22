package com.we.sharesystem.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    /** 单页最大条数：防止 size 被传成极大值（如 100000）拖垮数据库 */
    private static final long MAX_PAGE_SIZE = 50L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 超过上限自动截断为 50 条，属于 MyBatis-Plus 官方提供的全局兜底
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        // 页码超出总页数时返回空列表，而不是悄悄回到第一页
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
