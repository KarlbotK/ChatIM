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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageDeliveryService {

    private static final int MAX_CLIENT_MESSAGE_ID_LENGTH = 128;
    private static final long MAX_IMAGE_SIZE = 20L * 1024 * 1024;
    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );
    private static final DefaultRedisScript<Long> REPLACE_DELIVERY_RECORD = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3]); return 1 "
                    + "else return 0 end",
            Long.class
    );

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
        StoredDelivery existing = readStoredDelivery(deliveryKey);
        if (existing != null && !isRetryableFailure(existing.record())) {
            sendAck(channel, existing.record());
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
            if (existing == null) {
                reserve(deliveryKey, failure);
            } else {
                replaceRecord(deliveryKey, existing.json(), failure);
            }
            sendAck(channel, readRecord(deliveryKey, failure));
            return;
        }

        Long retryMessageId = existing == null ? null : existing.record().getMessageId();
        Long retryCreatedTime = existing == null ? null : existing.record().getCreatedTime();
        request.setMessageId(retryMessageId == null ? SnowflakeDynamicUtil.nextId() : retryMessageId);
        request.setCreatedTime(new Date(retryCreatedTime == null ? System.currentTimeMillis() : retryCreatedTime));
        MessageDeliveryRecord accepted = MessageDeliveryRecord.builder()
                .clientMessageId(clientMessageId)
                .messageId(request.getMessageId())
                .sessionId(request.getSessionId())
                .senderId(senderId)
                .status(MessageDeliveryStatus.ACCEPTED)
                .createdTime(request.getCreatedTime().getTime())
                .build();

        boolean claimed = existing == null
                ? reserve(deliveryKey, accepted)
                : replaceRecord(deliveryKey, existing.json(), accepted);
        if (!claimed) {
            sendAck(channel, readRecord(deliveryKey, accepted));
            return;
        }

        String messageJson = JSONUtil.toJsonStr(request);
        publishForPersistence(request, accepted, deliveryKey, channel, messageJson);
        publishForRealtimePush(request, messageJson);
    }

    /**
     * 存储链路与实时推送链路采用双 Topic 并行设计。完成校验后分别
     * 发起两个异步发送，两个 Future 互不等待；accepted 只表示存储
     * 事件已被 Kafka 接受，最终落库状态仍由 OfflineDataService 返回。
     */
    private void publishForPersistence(
            MessageRequest request,
            MessageDeliveryRecord accepted,
            String deliveryKey,
            Channel channel,
            String messageJson) {
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

    private void publishForRealtimePush(MessageRequest request, String messageJson) {
        try {
            kafkaTemplate.send(
                    CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH,
                    request.getSessionId().toString(),
                    messageJson
            ).whenComplete((result, failure) -> {
                if (failure != null) {
                    log.error("消息实时推送事件发送失败，messageId={}", request.getMessageId(), failure);
                }
            });
        } catch (Exception exception) {
            // 推送 Topic 失败不应阻止已经发起的存储链路；消息落库后可由
            // 离线同步恢复。这是双 Topic 并行方案明确接受的取舍。
            log.error("消息实时推送事件发送异常，messageId={}", request.getMessageId(), exception);
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

        DeliveryFailure mediaFailure = validateMedia(request);
        if (mediaFailure != null) {
            return mediaFailure;
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

    DeliveryFailure validateMedia(MessageRequest request) {
        if (request.getType() != MessageTypeConstant.IMAGE_MESSAGE
                || request.getBody().getObjectName() == null
                || request.getBody().getObjectName().isBlank()) {
            return null;
        }
        String objectName = request.getBody().getObjectName().trim();
        String expectedPrefix = "chat/" + request.getSessionId() + "/" + request.getSenderId() + "/";
        if (objectName.length() > 512
                || !objectName.startsWith(expectedPrefix)
                || objectName.contains("..")
                || objectName.contains("\\")) {
            return invalidMedia("图片对象标识无效");
        }
        String contentType = request.getBody().getMediaContentType();
        Integer width = request.getBody().getMediaWidth();
        Integer height = request.getBody().getMediaHeight();
        Long size = request.getBody().getMediaSize();
        if (!IMAGE_CONTENT_TYPES.contains(contentType)
                || width == null || width <= 0 || width > 10000
                || height == null || height <= 0 || height > 10000
                || size == null || size <= 0 || size > MAX_IMAGE_SIZE
                || (request.getBody().getOriginalName() != null
                    && request.getBody().getOriginalName().length() > 255)) {
            return invalidMedia("图片元数据无效");
        }
        String thumbnail = request.getBody().getThumbnailObjectName();
        if (thumbnail != null && !thumbnail.isBlank()
                && (!thumbnail.startsWith(expectedPrefix)
                    || thumbnail.length() > 512
                    || thumbnail.contains("..")
                    || thumbnail.contains("\\"))) {
            return invalidMedia("图片缩略图标识无效");
        }
        request.getBody().setObjectName(objectName);
        return null;
    }

    private DeliveryFailure invalidMedia(String message) {
        return new DeliveryFailure(ErrorCode.PARAMS_ERROR.getCode(), message);
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

    private boolean replaceRecord(String key, String expectedJson, MessageDeliveryRecord replacement) {
        Long replaced = stringRedisTemplate.execute(
                REPLACE_DELIVERY_RECORD,
                List.of(key),
                expectedJson,
                JSONUtil.toJsonStr(replacement),
                String.valueOf(TimeUnit.DAYS.toSeconds(CommonConstant.MESSAGE_DELIVERY_TTL_DAYS))
        );
        return Long.valueOf(1L).equals(replaced);
    }

    private StoredDelivery readStoredDelivery(String key) {
        String value = stringRedisTemplate.opsForValue().get(key);
        return value == null ? null : new StoredDelivery(value, JSONUtil.toBean(value, MessageDeliveryRecord.class));
    }

    private MessageDeliveryRecord readRecord(String key) {
        StoredDelivery stored = readStoredDelivery(key);
        return stored == null ? null : stored.record();
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
        MessageDeliveryRecord failure = failed(
                accepted.getClientMessageId(),
                accepted.getMessageId(),
                accepted.getSessionId(),
                errorCode
        );
        failure.setSenderId(accepted.getSenderId());
        if (replaceRecord(key, JSONUtil.toJsonStr(accepted), failure)) {
            sendAck(channel, failure);
            return;
        }
        sendAck(channel, readRecord(key, failure));
    }

    private boolean isRetryableFailure(MessageDeliveryRecord record) {
        if (!MessageDeliveryStatus.FAILED.equals(record.getStatus()) || record.getErrorCode() == null) {
            return false;
        }
        return record.getErrorCode() == ErrorCode.MESSAGE_PERSIST_FAILED.getCode()
                || record.getErrorCode() == ValidationError.SERVICE_UNAVAILABLE.getCode();
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

    record DeliveryFailure(int code, String message) {
    }

    private record StoredDelivery(String json, MessageDeliveryRecord record) {
    }
}
