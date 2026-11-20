package com.we.sharesystem.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.we.sharesystem.entity.MomentComment;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface MomentCommentMapper extends BaseMapper<MomentComment> {

    // 根据动态ID查询所有正常评论
    @Select("select * from moment_comment where moment_id = #{momentId} and status = 1 order by create_time asc")
    List<MomentComment> listByMomentId(Long momentId);
}
