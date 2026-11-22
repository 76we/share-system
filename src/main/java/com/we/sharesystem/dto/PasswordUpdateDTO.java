package com.we.sharesystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 个人中心：修改密码 */
@Data
public class PasswordUpdateDTO {
    @NotBlank(message = "请输入原密码")
    private String oldPassword;

    @NotBlank(message = "请输入新密码")
    @Size(min = 6, max = 64, message = "新密码长度 6-64 位")
    private String newPassword;
}
