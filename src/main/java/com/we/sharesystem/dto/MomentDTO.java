package com.we.sharesystem.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MomentDTO {
    private String title;

    @NotBlank(message = "内容不能为空")
    private String content;

    private Long topicId;
    /** 自定义话题名（前端"其它"输入），传了则按名称查/建话题，优先于 topicId */
    private String topicName;
    private String location;
    private String mood;
}
