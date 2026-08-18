package com.goat.redpacketservice.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.goat.common.common.BaseResponse;
import com.goat.common.common.ResultUtils;
import com.goat.common.enums.ValidationError;
import com.goat.common.model.dto.validation.*;
import com.goat.common.model.vo.UserInfosResponse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * UserServiceClient 降级工厂
 *
 * 采用严格模式：服务不可用时返回错误响应，调用方应拒绝操作
 * 遵循「宁可误拦不可漏过」原则
 */

/*
熔断策略：
使用的是 Spring Cloud CircuitBreaker 对 Resilience4j 的默认值
触发熔断的判定方式：在 100 次调用窗口内，出现异常（连接异常 / 超时 / 5xx 等）的比例超过 50%，断路器进入 OPEN 状态，后续调用直接走 FallbackFactory；
60s 后进入 HALF_OPEN 放 10 次试探，全部成功则 CLOSED，否则继续 OPEN。
*/
@Component
@Slf4j
public class UserServiceClientFallbackFactory implements FallbackFactory<UserServiceClient> {

    @Override
    public UserServiceClient create(Throwable cause) {
        return new com.goat.redpacketservice.client.UserServiceClient() {

            @Override
            public BaseResponse<UserStatusResponse> getUserStatus(Long userId) {
                log.error("UserService 不可用，无法查询用户状态。userId={}, 原因: {}", userId, cause.getMessage());
                return new BaseResponse<>(
                        ValidationError.SERVICE_UNAVAILABLE.getCode(),
                        null,
                        "校验服务暂时不可用，请稍后重试"
                );
            }

            @Override
            public BaseResponse<GroupMemberCountResponse> getGroupMemberCount(Long sessionId) {
                log.error("UserService 不可用，无法获取群成员数量。sessionId={}, 原因: {}", sessionId, cause.getMessage());
                return new BaseResponse<>(
                        ValidationError.SERVICE_UNAVAILABLE.getCode(),
                        null,
                        "校验服务暂时不可用，请稍后重试"
                );
            }

            @Override
            public BaseResponse<GroupMembershipResponse> checkGroupMembership(Long userId, Long sessionId) {
                log.error("UserService 不可用，无法验证群成员资格。userId={}, sessionId={}, 原因: {}",
                        userId, sessionId, cause.getMessage());
                return new BaseResponse<>(
                        ValidationError.SERVICE_UNAVAILABLE.getCode(),
                        null,
                        "校验服务暂时不可用，请稍后重试"
                );
            }

            @Override
            public BaseResponse<MessageValidateResponse> validateSingleMessage(SingleMessageValidateRequest request) {
                log.error("UserService 不可用，无法校验单聊消息权限。senderId={}, receiverId={}, 原因: {}",
                        request.getSenderId(), request.getReceiverId(), cause.getMessage());
                return new BaseResponse<>(
                        ValidationError.SERVICE_UNAVAILABLE.getCode(),
                        null,
                        "校验服务暂时不可用，请稍后重试"
                );
            }

            @Override
            public BaseResponse<Map<Long, UserInfosResponse>> batchGetUserInfos(List<Long> userIds) {
                // 宽松降级：返回空 Map，让业务层展示 null 而非报错
                // 用户信息为空不影响核心业务（红包详情仍可返回，只是昵称/头像为空）
                log.warn("UserService 不可用，无法批量获取用户信息。userIds={}, 原因: {}",
                        userIds, cause.getMessage());
                return ResultUtils.success(new HashMap<>());
            }
        };
    }
}