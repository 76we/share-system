package com.we.sharesystem.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class MomentDetailVO extends MomentVO {
    // 该动态的评论树
    private List<CommentVO> comments;
}
