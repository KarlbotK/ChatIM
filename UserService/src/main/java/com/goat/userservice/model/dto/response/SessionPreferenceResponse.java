package com.goat.userservice.model.dto.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SessionPreferenceResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    private boolean pinned;

    private boolean muted;

    private boolean hidden;

    private Long updatedTime;
}
