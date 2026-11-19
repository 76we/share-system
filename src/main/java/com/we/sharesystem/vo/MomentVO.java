package com.we.sharesystem.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class MomentVO {
    private Long id;
    private String title;
    private String content;
    private String location;
    private String mood;
    private String nickname;
    private String avatar;
    private List<String> images;
    private  Integer likeCount;
    private Integer commentCount;
    private Integer collectCount;
    private LocalDateTime createTime;
}
