package com.goat.userservice.model.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatFileUploadResponse {
    private String uploadUrl;
    private String objectName;
    private Integer expiresInSeconds;
}
