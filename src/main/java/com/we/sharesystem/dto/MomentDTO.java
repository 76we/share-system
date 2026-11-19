package com.we.sharesystem.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MomentDTO {
    private String title;

    @NotBlank(message = "内容不能为空")
    private String content;

    private Long topicId;
    private String location;
    private String mood;
}
