package com.we.sharesystem.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 个人中心：编辑资料（username 不可改，只改昵称/头像/邮箱/简介） */
@Data
public class ProfileUpdateDTO {
    @Size(max = 64, message = "昵称最多 64 字")
    private String nickname;

    @Size(max = 255, message = "头像地址过长")
    private String avatar;

    @Size(max = 128, message = "邮箱最多 128 字")
    private String email;

    @Size(max = 500, message = "简介最多 500 字")
    private String bio;
}
