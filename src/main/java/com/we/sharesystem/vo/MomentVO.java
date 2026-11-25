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
    /** 与 images 同序的尺寸信息；老图为 null 的宽高，前端回退到固定比例 */
    private List<ImageSizeVO> imageSizes;
    private  Integer likeCount;
    private Integer commentCount;
    private Integer collectCount;
    /** 浏览量：以 Redis 去重计数为准，detail 接口每次返回实时值 */
    private Integer viewCount;
    private LocalDateTime createTime;
}
