package com.goat.userservice.model.dto.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GroupMemberResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String nickname;

    private String groupNickname;

    private String avatar;

    private String description;

    private Integer role;

    private Integer status;

    private Long joinedTime;
}
