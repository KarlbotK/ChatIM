package com.goat.userservice.model.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateSessionPreferenceRequest {

    @NotNull(message = "设置值不能为空")
    private Boolean enabled;
}
