package com.we.sharesystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("photo")
public class Photo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long albumId;
    private String url;
    private String caption;
    private String orientation;
    private Integer sortOrder;
    private LocalDateTime createTime;
}
