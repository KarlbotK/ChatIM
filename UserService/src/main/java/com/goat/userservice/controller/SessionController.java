package com.goat.userservice.controller;

import com.goat.common.common.BaseResponse;
import com.goat.common.common.ErrorCode;
import com.goat.common.common.ResultUtils;
import com.goat.common.constant.CommonConstant;
import com.goat.common.exception.BusinessException;
import com.goat.common.exception.ThrowUtils;
import com.goat.common.utils.AuthTokenUtil;
import com.goat.common.utils.JwtUtil;
import com.goat.userservice.model.dto.request.MarkSessionReadRequest;
import com.goat.userservice.model.dto.response.SessionListResponse;
import com.goat.userservice.model.dto.response.SessionReadResponse;
import com.goat.userservice.service.SessionService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final StringRedisTemplate stringRedisTemplate;

    @GetMapping("/list")
    public BaseResponse<SessionListResponse> listSessions(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return ResultUtils.success(sessionService.listSessions(
                resolveAuthenticatedUserId(request),
                cursor,
                limit
        ));
    }

    @PostMapping("/{sessionId}/read")
    public BaseResponse<SessionReadResponse> markRead(
            @PathVariable Long sessionId,
            @Valid @RequestBody MarkSessionReadRequest readRequest,
            HttpServletRequest request) {
        return ResultUtils.success(sessionService.markRead(
                resolveAuthenticatedUserId(request),
                sessionId,
                readRequest.getLastReadMessageId()
        ));
    }

    private Long resolveAuthenticatedUserId(HttpServletRequest request) {
        String accessToken = AuthTokenUtil.extract(request.getHeader("Authorization"));
        if (accessToken == null) {
            accessToken = AuthTokenUtil.extract(request.getHeader("Access-Token"));
        }
        Claims claims = JwtUtil.parse(accessToken);
        ThrowUtils.throwIf(claims == null || StringUtils.isBlank(claims.getSubject()),
                ErrorCode.NOT_LOGIN_ERROR);
        String storedToken = stringRedisTemplate.opsForValue()
                .get(CommonConstant.ACCESS_TOKEN_PREFIX + claims.getSubject());
        ThrowUtils.throwIf(!accessToken.equals(storedToken), ErrorCode.NOT_LOGIN_ERROR);
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
    }
}
