package com.goat.userservice.model.dto.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GroupProfileResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    private String name;

    private String announcement;

    private String avatar;

    private String avatarObjectName;

    private Long updatedTime;
}
