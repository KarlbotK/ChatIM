package com.goat.userservice.model.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class GroupMemberTargetRequest {

    @NotNull(message = "成员ID不能为空")
    @Positive(message = "成员ID格式错误")
    private Long userId;
}
