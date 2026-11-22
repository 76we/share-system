package com.we.sharesystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user")
public class User {
    @TableId(type= IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String nickname;
    private String avatar;
    private String email;
    private String bio;
    private Integer status;
    /** 照片墙可见性：0=私密（默认，仅自己可见），1=公开，2=仅好友 */
    private Integer wallVisibility;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
