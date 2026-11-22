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
    /** 作者 id，用于从动态进入 TA 的照片墙 */
    private Long userId;
    private String nickname;
    private String avatar;
    private List<String> images;
    private  Integer likeCount;
    private Integer commentCount;
    private Integer collectCount;
    private LocalDateTime createTime;
}
