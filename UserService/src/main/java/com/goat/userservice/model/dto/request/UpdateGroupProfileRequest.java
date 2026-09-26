package com.goat.userservice.model.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateGroupProfileRequest {

    @Size(max = 40, message = "群名称不能超过40个字符")
    private String name;

    @Size(max = 500, message = "群公告不能超过500个字符")
    private String announcement;
}
