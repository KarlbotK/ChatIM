package com.goat.offlinedataservice.controller;

import java.util.List;
import java.util.Map;

import com.goat.common.common.BaseResponse;
import com.goat.common.common.ErrorCode;
import com.goat.common.common.ResultUtils;
import com.goat.common.constant.CommonConstant;
import com.goat.common.exception.ThrowUtils;
import com.goat.common.exception.BusinessException;
import com.goat.common.model.vo.MessageDeliveryRecord;
import com.goat.common.model.vo.MessageResponse;
import com.goat.common.utils.AuthTokenUtil;
import com.goat.common.utils.JwtUtil;
import com.goat.offlinedataservice.model.dto.HistoryMessageRequest;
import com.goat.offlinedataservice.model.dto.OfflineMessageRequest;

import com.goat.offlinedataservice.service.MessageService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import io.jsonwebtoken.Claims;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/message")
public class MessageController {

    @Resource
    private MessageService messageService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 获取离线消息（用户上线后调用）
     *
     * @return Map<sessionId, List<消息>>
     */
    @PostMapping("/offline")
    public BaseResponse<Map<Long, List<MessageResponse>>> getOfflineMessages(
            @RequestBody OfflineMessageRequest request) {
        return ResultUtils.success(messageService.getOfflineMessages(request));
    }

    /**
     * 获取历史消息（往上翻页）
     */
    @PostMapping("/history")
    public BaseResponse<List<MessageResponse>> getHistoryMessages(
            @RequestBody HistoryMessageRequest request) {
        return ResultUtils.success(messageService.getHistoryMessages(request));
    }

    @GetMapping("/status")
    public BaseResponse<MessageDeliveryRecord> getMessageStatus(
            @RequestParam String clientMessageId,
            HttpServletRequest request) {
        ThrowUtils.throwIf(StringUtils.isBlank(clientMessageId), ErrorCode.PARAMS_ERROR);
        Long senderId = resolveAuthenticatedUserId(request);
        return ResultUtils.success(messageService.getMessageStatus(senderId, clientMessageId));
    }

    private Long resolveAuthenticatedUserId(HttpServletRequest request) {
        String accessToken = AuthTokenUtil.extract(request.getHeader("Authorization"));
        if (accessToken == null) {
            accessToken = AuthTokenUtil.extract(request.getHeader("Access-Token"));
        }
        Claims claims = JwtUtil.parse(accessToken);
        ThrowUtils.throwIf(claims == null || StringUtils.isBlank(claims.getSubject()), ErrorCode.NOT_LOGIN_ERROR);

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
