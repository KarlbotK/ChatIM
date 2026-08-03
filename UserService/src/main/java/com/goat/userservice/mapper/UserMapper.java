package com.goat.userservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.goat.userservice.model.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author KARLK
 * @description 针对表【user(用户表)】的数据库操作Mapper
 * @createDate 2026-07-08 16:41:10
 * @Entity com.goat.initproject.entity.User
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
