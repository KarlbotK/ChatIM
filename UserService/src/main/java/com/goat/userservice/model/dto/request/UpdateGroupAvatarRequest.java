package com.goat.userservice.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateGroupAvatarRequest {

    @NotBlank(message = "头像对象名不能为空")
    private String objectName;
}
