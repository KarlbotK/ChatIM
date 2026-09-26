package com.goat.userservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.goat.userservice.model.entity.UserSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface UserSessionMapper extends BaseMapper<UserSession> {

    @Select("""
            SELECT user_id, session_id, role, status, created_time, updated_time,
                   last_read_message_id, pinned, muted, hidden
            FROM user_session
            WHERE session_id = #{sessionId}
              AND status = 0
            ORDER BY role, created_time, user_id
            LIMIT #{limit}
            """)
    List<UserSession> selectActiveGroupMembersFromStart(
            @Param("sessionId") Long sessionId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT user_id, session_id, role, status, created_time, updated_time,
                   last_read_message_id, pinned, muted, hidden
            FROM user_session
            WHERE session_id = #{sessionId}
              AND status = 0
              AND (
                    role > #{cursorRole}
                    OR (
                        role = #{cursorRole}
                        AND UNIX_TIMESTAMP(created_time) * 1000 > #{cursorJoinedTime}
                    )
                    OR (
                        role = #{cursorRole}
                        AND UNIX_TIMESTAMP(created_time) * 1000 = #{cursorJoinedTime}
                        AND user_id > #{cursorMemberId}
                    )
              )
            ORDER BY role, created_time, user_id
            LIMIT #{limit}
            """)
    List<UserSession> selectActiveGroupMembersAfter(
            @Param("sessionId") Long sessionId,
            @Param("cursorRole") int cursorRole,
            @Param("cursorJoinedTime") long cursorJoinedTime,
            @Param("cursorMemberId") long cursorMemberId,
            @Param("limit") int limit
    );

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

    @Update("""
            UPDATE user_session
            SET hidden = 0,
                updated_time = CURRENT_TIMESTAMP
            WHERE session_id = #{sessionId}
              AND status = 0
              AND hidden = 1
            """)
    int revealHiddenSession(@Param("sessionId") Long sessionId);
}
