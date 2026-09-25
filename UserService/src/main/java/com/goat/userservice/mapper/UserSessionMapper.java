package com.goat.userservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.goat.userservice.model.entity.UserSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserSessionMapper extends BaseMapper<UserSession> {

    @Update("""
            UPDATE user_session
            SET last_read_message_id = GREATEST(COALESCE(last_read_message_id, 0), #{lastReadMessageId}),
                updated_time = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND session_id = #{sessionId}
              AND status = 0
              AND (last_read_message_id IS NULL OR last_read_message_id < #{lastReadMessageId})
            """)
    int advanceReadPosition(
            @Param("userId") Long userId,
            @Param("sessionId") Long sessionId,
            @Param("lastReadMessageId") Long lastReadMessageId
    );
}
