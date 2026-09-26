package com.goat.userservice.controller;

import com.goat.common.common.BaseResponse;
import com.goat.common.common.ResultUtils;
import com.goat.userservice.model.dto.request.MarkSessionReadRequest;
import com.goat.userservice.model.dto.request.UpdateSessionPreferenceRequest;
import com.goat.userservice.model.dto.response.SessionDetailResponse;
import com.goat.userservice.model.dto.response.SessionListResponse;
import com.goat.userservice.model.dto.response.SessionReadResponse;
import com.goat.userservice.model.dto.response.SessionPreferenceResponse;
import com.goat.userservice.service.SessionService;
import com.goat.userservice.utils.AuthenticatedUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final AuthenticatedUserResolver authenticatedUserResolver;

    @GetMapping("/list")
    public BaseResponse<SessionListResponse> listSessions(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return ResultUtils.success(sessionService.listSessions(
                authenticatedUserResolver.resolve(request),
                cursor,
                limit
        ));
    }

    @GetMapping("/{sessionId}")
    public BaseResponse<SessionDetailResponse> getSessionDetail(
            @PathVariable Long sessionId,
            HttpServletRequest request) {
        return ResultUtils.success(sessionService.getSessionDetail(
                authenticatedUserResolver.resolve(request),
                sessionId
        ));
    }

    @PostMapping("/{sessionId}/read")
    public BaseResponse<SessionReadResponse> markRead(
            @PathVariable Long sessionId,
            @Valid @RequestBody MarkSessionReadRequest readRequest,
            HttpServletRequest request) {
        return ResultUtils.success(sessionService.markRead(
                authenticatedUserResolver.resolve(request),
                sessionId,
                readRequest.getLastReadMessageId()
        ));
    }

    @PostMapping("/{sessionId}/pin")
    public BaseResponse<SessionPreferenceResponse> updatePinned(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateSessionPreferenceRequest preferenceRequest,
            HttpServletRequest request) {
        return ResultUtils.success(sessionService.updatePinned(
                authenticatedUserResolver.resolve(request),
                sessionId,
                preferenceRequest.getEnabled()
        ));
    }

    @PostMapping("/{sessionId}/mute")
    public BaseResponse<SessionPreferenceResponse> updateMuted(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateSessionPreferenceRequest preferenceRequest,
            HttpServletRequest request) {
        return ResultUtils.success(sessionService.updateMuted(
                authenticatedUserResolver.resolve(request),
                sessionId,
                preferenceRequest.getEnabled()
        ));
    }

    @DeleteMapping("/{sessionId}")
    public BaseResponse<SessionPreferenceResponse> hideSession(
            @PathVariable Long sessionId,
            HttpServletRequest request) {
        return ResultUtils.success(sessionService.hideSession(
                authenticatedUserResolver.resolve(request),
                sessionId
        ));
    }
}
