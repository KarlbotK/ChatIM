package com.goat.userservice.controller;

import com.goat.common.common.BaseResponse;
import com.goat.common.common.ResultUtils;
import com.goat.userservice.model.dto.response.ChatFileDownloadResponse;
import com.goat.userservice.model.dto.response.ChatFileUploadResponse;
import com.goat.userservice.service.ChatFileService;
import com.goat.userservice.utils.AuthenticatedUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/file")
@RequiredArgsConstructor
public class ChatFileController {
    private final ChatFileService chatFileService;
    private final AuthenticatedUserResolver authenticatedUserResolver;

    @GetMapping("/{sessionId}/upload-url")
    public BaseResponse<ChatFileUploadResponse> createUploadUrl(
            @PathVariable Long sessionId,
            @RequestParam String fileName,
            @RequestParam(required = false) String objectName,
            HttpServletRequest request) {
        return ResultUtils.success(chatFileService.createUploadUrl(
                authenticatedUserResolver.resolve(request),
                sessionId,
                fileName,
                objectName
        ));
    }

    @GetMapping("/{sessionId}/download-url")
    public BaseResponse<ChatFileDownloadResponse> createDownloadUrl(
            @PathVariable Long sessionId,
            @RequestParam String objectName,
            HttpServletRequest request) {
        return ResultUtils.success(chatFileService.createDownloadUrl(
                authenticatedUserResolver.resolve(request),
                sessionId,
                objectName
        ));
    }
}
