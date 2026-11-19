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
    private Integer sortOrder;
}
