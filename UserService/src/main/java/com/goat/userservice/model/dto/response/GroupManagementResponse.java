package com.goat.userservice.model.dto.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GroupManagementResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long actorUserId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long affectedUserId;

    private String action;

    private Integer actorUserRole;

    private Integer affectedUserRole;

    private Integer memberCount;

    private boolean dissolved;

    private long updatedTime;
}
