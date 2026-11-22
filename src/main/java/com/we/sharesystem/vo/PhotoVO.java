package com.we.sharesystem.vo;

import lombok.Data;

@Data
public class PhotoVO {
    private Long id;
    private String url;
    private String caption;
    /** landscape / portrait，决定拍立得卡片的宽高比 */
    private String orientation;
}
