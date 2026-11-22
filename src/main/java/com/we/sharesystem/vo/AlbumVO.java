package com.we.sharesystem.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AlbumVO {
    private Long id;
    private String title;
    private String description;
    /** 前 3 张照片，用于封面扇形叠放 */
    private List<String> covers;
    private Integer photoCount;
    private LocalDateTime updateTime;
}
