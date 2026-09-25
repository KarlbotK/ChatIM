package com.goat.userservice.model.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MarkSessionReadRequest {

    @NotNull(message = "最后已读消息不能为空")
    private Long lastReadMessageId;
}
