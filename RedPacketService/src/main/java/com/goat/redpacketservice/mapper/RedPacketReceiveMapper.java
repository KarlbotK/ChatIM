package com.goat.redpacketservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.goat.redpacketservice.model.entity.RedPacketReceive;

import org.apache.ibatis.annotations.Mapper;

/**
 * 红包领取记录 Mapper
 */
@Mapper
public interface RedPacketReceiveMapper extends BaseMapper<RedPacketReceive> {

}