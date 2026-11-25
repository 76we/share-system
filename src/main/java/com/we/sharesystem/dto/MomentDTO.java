package com.we.sharesystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MomentDTO {
    /** 与 moment.title 的 varchar(200) 对齐，超长会直接撞库报 500 */
    @Size(max = 200, message = "标题最多 200 字")
    private String title;

    @NotBlank(message = "内容不能为空")
    @Size(max = 2000, message = "正文最多 2000 字")
    private String content;

    private Long topicId;
    /** 自定义话题名（前端"其它"输入），传了则按名称查/建话题，优先于 topicId */
    private String topicName;
    /** 与 moment.location 的 varchar(128) 对齐；前端有 maxlength，但接口不能指望前端 */
    @Size(max = 128, message = "地点最多 128 字")
    private String location;
    /** 与 moment.mood 的 varchar(32) 对齐 */
    @Size(max = 32, message = "心情最多 32 字")
    private String mood;
}
