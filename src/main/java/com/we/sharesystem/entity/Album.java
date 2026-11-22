package com.we.sharesystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("album")
public class Album {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 相册主人 */
    private Long userId;
    private String title;
    private String description;
    private String cover;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
