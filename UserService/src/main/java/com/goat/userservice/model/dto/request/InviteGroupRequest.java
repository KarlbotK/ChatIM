package com.goat.userservice.model.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class InviteGroupRequest implements Serializable {
    @Serial
    private static final long serialVersionUID=1L;

    @NotNull(message = "会话ID不能为空")
    private Long sessionId;

    @NotNull(message = "邀请者ID不能为空")
    private Long inviterId;

    @NotNull(message = "被邀请者ID不能为空")
    private List<Long> inviteeIds;
}
