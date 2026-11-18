package com.we.sharesystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.we.sharesystem.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
