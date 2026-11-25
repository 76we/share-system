package com.we.sharesystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("moment_image")
public class MomentImage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long momentId;
    private String url;
    /** 原图像素宽（转正后、缩放前）。老数据为 null，前端需回退到固定宽高比 */
    private Integer width;
    /** 原图像素高 */
    private Integer height;
    private Integer sortOrder;
}
