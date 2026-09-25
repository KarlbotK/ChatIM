package com.goat.userservice.model.dto.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.goat.common.model.vo.MessageResponse;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SessionSummaryResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    private Integer sessionType;

    private String name;

    private String avatar;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long peerId;

    private MessageResponse lastMessage;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastMessageId;

    private Long lastMessageTime;

    private long unreadCount;

    private boolean pinned;

    private boolean muted;

    private Integer memberCount;

    private Integer currentUserRole;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastReadMessageId;

    private Long updatedTime;
}
