package com.goat.offlinedataservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.goat.offlinedataservice.model.entity.Message;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

}