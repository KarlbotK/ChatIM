package com.goat.userservice.model.dto.response;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class InviteGroupResponse implements Serializable {
    @Serial
    private static final long serialVersionUID=1L;

    private List<String> successIds;//成功邀请的用户ID列表

    private List<String> failedIds;//邀请失败的用户ID列表
}
