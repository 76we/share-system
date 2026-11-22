package com.we.sharesystem.vo;

import lombok.Data;

import java.util.List;

/**
 * 照片墙的一个"月份区间"：把该年该月的照片聚成一段。
 * 前端据此渲染月份导航 + 分区展示，每段内部仍是拍立得网格。
 */
@Data
public class PhotoMonthGroupVO {
    /** 年，如 2025 */
    private Integer year;
    /** 月，1-12 */
    private Integer month;
    /** 展示文案，如 "2025年11月"；时间为空时为 "未知时间" */
    private String label;
    /** 该月照片数 */
    private Integer count;
    /** 该月照片，按动态时间倒序 + 动态内图片顺序 */
    private List<WallPhotoVO> photos;
}
