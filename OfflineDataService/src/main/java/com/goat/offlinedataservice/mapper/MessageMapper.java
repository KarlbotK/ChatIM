package com.goat.offlinedataservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.goat.common.model.dto.SessionReadPosition;
import com.goat.offlinedataservice.model.entity.Message;
import com.goat.offlinedataservice.model.dto.SessionUnreadCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    @Select("""
            <script>
            SELECT message_id, client_message_id, sender_id, session_id, type, content,
                   reply_id, session_type, created_time, updated_time
            FROM (
                SELECT message_id, client_message_id, sender_id, session_id, type, content,
                       reply_id, session_type, created_time, updated_time,
                       ROW_NUMBER() OVER (
                           PARTITION BY session_id
                           ORDER BY created_time DESC, message_id DESC
                       ) AS row_num
                FROM message
                WHERE session_id IN
                <foreach collection="sessionIds" item="sessionId" open="(" separator="," close=")">
                    #{sessionId}
                </foreach>
            ) ranked
            WHERE row_num = 1
            </script>
            """)
    List<Message> selectLatestBySessionIds(@Param("sessionIds") List<Long> sessionIds);

    @Select("""
            <script>
            SELECT session_id, COUNT(*) AS unread_count
            FROM message
            WHERE sender_id != #{userId}
              AND (
                <foreach collection="positions" item="position" separator=" OR ">
                    (session_id = #{position.sessionId}
                    <if test="position.lastReadMessageId != null">
                        AND message_id &gt; #{position.lastReadMessageId}
                    </if>
                    )
                </foreach>
              )
            GROUP BY session_id
            </script>
            """)
    List<SessionUnreadCount> selectUnreadCounts(
            @Param("userId") Long userId,
            @Param("positions") List<SessionReadPosition> positions
    );
}
