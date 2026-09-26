package com.goat.userservice.model.dto.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SessionDetailResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    private Integer sessionType;

    private String name;

    private String avatar;

    private String avatarObjectName;

    private String announcement;

    private SessionParticipantResponse peer;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long ownerId;

    private Integer memberCount;

    private Integer currentUserRole;

    private boolean currentUserMember;

    private boolean pinned;

    private boolean muted;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long lastReadMessageId;

    private Long createdTime;

    private Long updatedTime;
}
