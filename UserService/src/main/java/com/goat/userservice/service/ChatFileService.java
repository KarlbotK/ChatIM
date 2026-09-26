package com.goat.userservice.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.goat.common.common.ErrorCode;
import com.goat.common.constant.CommonConstant;
import com.goat.common.exception.ThrowUtils;
import com.goat.userservice.mapper.UserSessionMapper;
import com.goat.userservice.model.dto.response.ChatFileDownloadResponse;
import com.goat.userservice.model.dto.response.ChatFileUploadResponse;
import com.goat.userservice.model.entity.UserSession;
import com.goat.userservice.utils.OssUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatFileService {
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    private final UserSessionMapper userSessionMapper;
    private final OssUtils ossUtils;

    public ChatFileUploadResponse createUploadUrl(
            Long userId,
            Long sessionId,
            String fileName,
            String existingObjectName) {
        validateMembership(userId, sessionId);
        String objectName;
        if (existingObjectName != null && !existingObjectName.isBlank()) {
            objectName = validateUploadObjectName(userId, sessionId, existingObjectName);
        } else {
            String extension = imageExtension(fileName);
            objectName = "chat/" + sessionId + "/" + userId + "/"
                    + UUID.randomUUID() + "." + extension;
        }
        return ChatFileUploadResponse.builder()
                .uploadUrl(ossUtils.uploadUrl(
                        CommonConstant.BUCKET_NAME,
                        objectName,
                        CommonConstant.PICTURE_EXPIRE_TIME
                ))
                .objectName(objectName)
                .expiresInSeconds(CommonConstant.PICTURE_EXPIRE_TIME)
                .build();
    }

    public ChatFileDownloadResponse createDownloadUrl(Long userId, Long sessionId, String objectName) {
        validateMembership(userId, sessionId);
        String validatedObjectName = validateObjectName(sessionId, objectName);
        ThrowUtils.throwIf(!ossUtils.objectExists(CommonConstant.BUCKET_NAME, validatedObjectName),
                ErrorCode.NOT_FOUND_ERROR, "图片不存在或已被删除");
        return ChatFileDownloadResponse.builder()
                .downloadUrl(ossUtils.temporaryDownloadUrl(
                        CommonConstant.BUCKET_NAME,
                        validatedObjectName,
                        CommonConstant.PICTURE_EXPIRE_TIME
                ))
                .objectName(validatedObjectName)
                .expiresInSeconds(CommonConstant.PICTURE_EXPIRE_TIME)
                .build();
    }

    private void validateMembership(Long userId, Long sessionId) {
        ThrowUtils.throwIf(userId == null || sessionId == null, ErrorCode.PARAMS_ERROR);
        QueryWrapper<UserSession> query = new QueryWrapper<>();
        query.eq("user_id", userId)
                .eq("session_id", sessionId)
                .eq("status", 0);
        ThrowUtils.throwIf(userSessionMapper.selectCount(query) == 0,
                ErrorCode.MESSAGE_NOT_IN_SESSION);
    }

    private String validateObjectName(Long sessionId, String objectName) {
        ThrowUtils.throwIf(objectName == null || objectName.isBlank(),
                ErrorCode.PARAMS_ERROR, "图片对象标识不能为空");
        String normalized = objectName.trim();
        ThrowUtils.throwIf(!normalized.startsWith("chat/" + sessionId + "/")
                        || normalized.contains("..")
                        || normalized.contains("\\"),
                ErrorCode.NO_AUTH_ERROR, "图片对象不属于当前会话");
        imageExtension(normalized);
        return normalized;
    }

    private String validateUploadObjectName(Long userId, Long sessionId, String objectName) {
        String normalized = validateObjectName(sessionId, objectName);
        ThrowUtils.throwIf(!normalized.startsWith("chat/" + sessionId + "/" + userId + "/"),
                ErrorCode.NO_AUTH_ERROR, "不能覆盖其他用户上传的图片");
        return normalized;
    }

    private String imageExtension(String fileName) {
        String normalized = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.lastIndexOf('.');
        String extension = separator < 0 ? "" : normalized.substring(separator + 1);
        ThrowUtils.throwIf(!IMAGE_EXTENSIONS.contains(extension),
                ErrorCode.PARAMS_ERROR, "仅支持 JPG、PNG 或 WebP 图片");
        return "jpeg".equals(extension) ? "jpg" : extension;
    }
}
