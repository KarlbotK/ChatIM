package com.goat.userservice.model.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GroupAvatarUploadResponse {

    private String uploadUrl;

    private String downloadUrl;

    private String objectName;

    private int expiresInSeconds;
}
