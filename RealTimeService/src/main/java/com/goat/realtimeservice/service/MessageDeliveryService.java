package com.goat.realtimeservice.service;

import cn.hutool.json.JSONUtil;
import com.goat.common.common.BaseResponse;
import com.goat.common.common.ErrorCode;
import com.goat.common.constant.CommonConstant;
import com.goat.common.constant.MessageDeliveryStatus;
import com.goat.common.constant.MessageTypeConstant;
import com.goat.common.constant.SessionTypeConstant;
import com.goat.common.enums.ValidationError;
import com.goat.common.model.dto.MessageRequest;
import com.goat.common.model.dto.validation.GroupMembershipResponse;
import com.goat.common.model.dto.validation.MessageValidateResponse;
import com.goat.common.model.dto.validation.SingleMessageValidateRequest;
import com.goat.common.model.vo.MessageAckEvent;
import com.goat.common.model.vo.MessageDeliveryRecord;
import com.goat.realtimeservice.client.UserServiceClient;
import com.goat.realtimeservice.utils.SnowflakeDynamicUtil;
import com.goat.realtimeservice.websocket.ChannelManager;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageDeliveryService {

    private static final int MAX_CLIENT_MESSAGE_ID_LENGTH = 128;

    private final StringRedisTemplate stringRedisTemplate;

    private final KafkaTemplate<String, String> kafkaTemplate;

    private final UserServiceClient userServiceClient;

    public void accept(String payload, Channel channel) {
        MessageRequest request;
        try {
            request = JSONUtil.toBean(payload, MessageRequest.class);
        } catch (Exception exception) {
            sendAck(channel, failed(null, null, null, ErrorCode.PARAMS_ERROR));
            return;
        }

        Long senderId = authenticatedUserId(channel);
        if (senderId == null) {
            sendAck(channel, failed(request.getClientMessageId(), null, request.getSessionId(), ErrorCode.NOT_LOGIN_ERROR));
            return;
        }
        request.setSenderId(senderId);

        String clientMessageId = request.getClientMessageId();
        if (clientMessageId == null || clientMessageId.isBlank()
                || clientMessageId.length() > MAX_CLIENT_MESSAGE_ID_LENGTH) {
            sendAck(channel, failed(clientMessageId, null, request.getSessionId(), ErrorCode.MESSAGE_CLIENT_ID_REQUIRED));
            return;
        }

        String deliveryKey = deliveryKey(senderId, clientMessageId);
        MessageDeliveryRecord existing = readRecord(deliveryKey);
        if (existing != null) {
            sendAck(channel, existing);
            return;
        }

        DeliveryFailure validationFailure = validate(request);
        if (validationFailure != null) {
            MessageDeliveryRecord failure = failed(
                    clientMessageId,
                    null,
                    request.getSessionId(),
                    validationFailure.code(),
                    validationFailure.message()
            );
            failure.setSenderId(senderId);
            reserve(deliveryKey, failure);
            sendAck(channel, readRecord(deliveryKey, failure));
            return;
        }

        request.setMessageId(SnowflakeDynamicUtil.nextId());
        request.setCreatedTime(new Date());
        MessageDeliveryRecord accepted = MessageDeliveryRecord.builder()
                .clientMessageId(clientMessageId)
                .messageId(request.getMessageId())
                .sessionId(request.getSessionId())
                .senderId(senderId)
                .status(MessageDeliveryStatus.ACCEPTED)
                .createdTime(request.getCreatedTime().getTime())
                .build();

        if (!reserve(deliveryKey, accepted)) {
            sendAck(channel, readRecord(deliveryKey, accepted));
            return;
        }

        String messageJson = JSONUtil.toJsonStr(request);
        try {
            kafkaTemplate.send(
                    CommonConstant.KAFKA_MESSAGE_TOPIC_STORE,
                    request.getMessageId().toString(),
                    messageJson
            ).whenComplete((result, failure) -> {
                if (failure != null) {
                    failAccepted(deliveryKey, accepted, channel, ErrorCode.MESSAGE_PERSIST_FAILED);
                    log.error("消息存储事件发送失败，messageId={}", request.getMessageId(), failure);
                    return;
                }

                sendAck(channel, accepted);
            });
        } catch (Exception exception) {
            failAccepted(deliveryKey, accepted, channel, ErrorCode.MESSAGE_PERSIST_FAILED);
            log.error("消息存储事件发送异常，messageId={}", request.getMessageId(), exception);
        }
    }

    private DeliveryFailure validate(MessageRequest request) {
        if (request.getSessionId() == null
                || request.getSessionType() == null
                || request.getType() == null
                || request.getBody() == null
                || !MessageTypeConstant.isChatMessage(request.getType())) {
            return new DeliveryFailure(ErrorCode.PARAMS_ERROR.getCode(), "消息参数不完整");
        }

        try {
            BaseResponse<Integer> sessionTypeResponse = userServiceClient.getSessionType(request.getSessionId());
            if (sessionTypeResponse == null
                    || sessionTypeResponse.getCode() != 200
                    || sessionTypeResponse.getData() == null) {
                return new DeliveryFailure(
                        ErrorCode.MESSAGE_NOT_IN_SESSION.getCode(),
                        ErrorCode.MESSAGE_NOT_IN_SESSION.getMessage()
                );
            }
            if (!request.getSessionType().equals(sessionTypeResponse.getData())) {
                return new DeliveryFailure(ErrorCode.PARAMS_ERROR.getCode(), "会话类型不匹配");
            }

            if (request.getSessionType() == SessionTypeConstant.SIGNAL_TYPE) {
                if (request.getReceiverId() == null) {
                    return new DeliveryFailure(ErrorCode.SIGNAL_TYPE_ERROR.getCode(), ErrorCode.SIGNAL_TYPE_ERROR.getMessage());
                }
                List<Long> members = userServiceClient.getUserIdBySessionId(request.getSessionId());
                if (members == null
                        || !members.contains(request.getSenderId())
                        || !members.contains(request.getReceiverId())) {
                    return new DeliveryFailure(ErrorCode.MESSAGE_NOT_IN_SESSION.getCode(), ErrorCode.MESSAGE_NOT_IN_SESSION.getMessage());
                }
                BaseResponse<MessageValidateResponse> response = userServiceClient.validateSingleMessage(
                        SingleMessageValidateRequest.of(
                                request.getSenderId(),
                                request.getReceiverId(),
                                request.getSessionId()
                        )
                );
                MessageValidateResponse result = response == null ? null : response.getData();
                if (response == null || response.getCode() != 200 || result == null) {
                    return unavailable();
                }
                if (!result.isAllowed()) {
                    ValidationError error = result.getErrorCode() == null
                            ? ValidationError.VALIDATION_FAILED
                            : ValidationError.fromCode(result.getErrorCode());
                    return new DeliveryFailure(error.getCode(), error.getMessage());
                }
                return null;
            }

            if (request.getSessionType() == SessionTypeConstant.GROUP_TYPE) {
                request.setReceiverId(null);
                BaseResponse<GroupMembershipResponse> response = userServiceClient.checkGroupMembership(
                        request.getSenderId(),
                        request.getSessionId()
                );
                GroupMembershipResponse result = response == null ? null : response.getData();
                if (response == null || response.getCode() != 200 || result == null) {
                    return unavailable();
                }
                if (!Boolean.TRUE.equals(result.getIsMember())) {
                    return new DeliveryFailure(
                            ValidationError.NOT_GROUP_MEMBER.getCode(),
                            ValidationError.NOT_GROUP_MEMBER.getMessage()
                    );
                }
                return null;
            }

            return new DeliveryFailure(ErrorCode.PARAMS_ERROR.getCode(), "不支持的会话类型");
        } catch (Exception exception) {
            log.warn("消息发送权限校验失败，sessionId={}，senderId={}",
                    request.getSessionId(), request.getSenderId(), exception);
            return unavailable();
        }
    }

    private DeliveryFailure unavailable() {
        return new DeliveryFailure(
                ValidationError.SERVICE_UNAVAILABLE.getCode(),
                ValidationError.SERVICE_UNAVAILABLE.getMessage()
        );
    }

    private boolean reserve(String key, MessageDeliveryRecord record) {
        Boolean created = stringRedisTemplate.opsForValue().setIfAbsent(
                key,
                JSONUtil.toJsonStr(record),
                CommonConstant.MESSAGE_DELIVERY_TTL_DAYS,
                TimeUnit.DAYS
        );
        return Boolean.TRUE.equals(created);
    }

    private MessageDeliveryRecord readRecord(String key) {
        String value = stringRedisTemplate.opsForValue().get(key);
        return value == null ? null : JSONUtil.toBean(value, MessageDeliveryRecord.class);
    }

    private MessageDeliveryRecord readRecord(String key, MessageDeliveryRecord fallback) {
        MessageDeliveryRecord record = readRecord(key);
        return record == null ? fallback : record;
    }

    private void failAccepted(
            String key,
            MessageDeliveryRecord accepted,
            Channel channel,
            ErrorCode errorCode) {
        MessageDeliveryRecord current = readRecord(key);
        if (current != null && MessageDeliveryStatus.PERSISTED.equals(current.getStatus())) {
            sendAck(channel, current);
            return;
        }
        MessageDeliveryRecord failure = failed(
                accepted.getClientMessageId(),
                accepted.getMessageId(),
                accepted.getSessionId(),
                errorCode
        );
        failure.setSenderId(accepted.getSenderId());
        stringRedisTemplate.opsForValue().set(
                key,
                JSONUtil.toJsonStr(failure),
                CommonConstant.MESSAGE_DELIVERY_TTL_DAYS,
                TimeUnit.DAYS
        );
        sendAck(channel, failure);
    }

    private MessageDeliveryRecord failed(
            String clientMessageId,
            Long messageId,
            Long sessionId,
            ErrorCode errorCode) {
        return failed(clientMessageId, messageId, sessionId, errorCode.getCode(), errorCode.getMessage());
    }

    private MessageDeliveryRecord failed(
            String clientMessageId,
            Long messageId,
            Long sessionId,
            int errorCode,
            String errorMessage) {
        return MessageDeliveryRecord.builder()
                .clientMessageId(clientMessageId)
                .messageId(messageId)
                .sessionId(sessionId)
                .status(MessageDeliveryStatus.FAILED)
                .createdTime(System.currentTimeMillis())
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }

    private Long authenticatedUserId(Channel channel) {
        String value = ChannelManager.getUserIdByChannel(channel);
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String deliveryKey(Long senderId, String clientMessageId) {
        return CommonConstant.MESSAGE_DELIVERY_PREFIX + senderId + ":" + clientMessageId;
    }

    private void sendAck(Channel channel, MessageDeliveryRecord record) {
        if (channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(
                    JSONUtil.toJsonStr(MessageAckEvent.from(record))
            ));
        }
    }

    private record DeliveryFailure(int code, String message) {
    }
}
