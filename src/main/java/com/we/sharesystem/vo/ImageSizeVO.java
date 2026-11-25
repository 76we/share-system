package com.we.sharesystem.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单张图片的尺寸信息，与 MomentVO.images 同序。
 *
 * 为什么单独一个列表而不是把 images 改成对象数组：
 * images 是 List&lt;String&gt; 已经在前端很多地方用（详情页大图、发布页预览回显），
 * 改成对象会牵动一大片；新增一个同序的尺寸列表，前端按需取用，
 * 老接口的消费方即使不认识这个字段也不会坏。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageSizeVO {
    /** 与 images 中对应项的 **原图** URL 一致（可能是缩略图 URL，仅作对齐用） */
    private String url;
    private Integer width;
    private Integer height;
}
