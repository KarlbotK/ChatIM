package com.goat.realtimeservice.service;

import cn.hutool.json.JSONUtil;
import com.goat.common.common.ResultUtils;
import com.goat.common.constant.CommonConstant;
import com.goat.common.constant.SessionTypeConstant;
import com.goat.common.model.dto.MessageBody;
import com.goat.common.model.dto.MessageRequest;
import com.goat.common.model.dto.validation.GroupMembershipResponse;
import com.goat.realtimeservice.client.UserServiceClient;
import com.goat.realtimeservice.websocket.ChannelManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageDeliveryServiceTest {
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private KafkaTemplate<String, String> kafkaTemplate;
    private UserServiceClient userServiceClient;
    private MessageDeliveryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        userServiceClient = mock(UserServiceClient.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new MessageDeliveryService(redisTemplate, kafkaTemplate, userServiceClient);
    }

    @Test
    void publishesStoreAndRealtimeTopicsWithoutWaitingForPersistence() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelManager.addUserChannel("101", channel);
        ChannelManager.addChannelUser("101", channel);
        try {
            MessageRequest request = new MessageRequest();
            request.setSessionId(9001L);
            request.setSenderId(999L);
            request.setType(0);
            request.setSessionType(SessionTypeConstant.GROUP_TYPE);
            request.setClientMessageId("client-parallel-1");
            MessageBody body = new MessageBody();
            body.setContent("并行消息");
            request.setBody(body);

            when(valueOperations.setIfAbsent(
                    anyString(), anyString(), anyLong(), eq(TimeUnit.DAYS)))
                    .thenReturn(true);
            when(userServiceClient.getSessionType(9001L))
                    .thenReturn(ResultUtils.success(SessionTypeConstant.GROUP_TYPE));
            when(userServiceClient.checkGroupMembership(101L, 9001L))
                    .thenReturn(ResultUtils.success(
                            GroupMembershipResponse.of(101L, 9001L, true, GroupMembershipResponse.ROLE_MEMBER)
                    ));

            CompletableFuture<SendResult<String, String>> pendingStore = new CompletableFuture<>();
            when(kafkaTemplate.send(eq(CommonConstant.KAFKA_MESSAGE_TOPIC_STORE), anyString(), anyString()))
                    .thenReturn(pendingStore);
            when(kafkaTemplate.send(eq(CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH), eq("9001"), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            service.accept(JSONUtil.toJsonStr(request), channel);

            verify(kafkaTemplate).send(
                    eq(CommonConstant.KAFKA_MESSAGE_TOPIC_STORE), anyString(), anyString());
            verify(kafkaTemplate).send(
                    eq(CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH), eq("9001"), anyString());
            assertFalse(pendingStore.isDone());
        } finally {
            ChannelManager.removeChannelUser(channel);
            ChannelManager.removeUserChannel("101", channel);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void acceptsStableImageMetadataOwnedByTheSender() {
        MessageRequest request = imageRequest("chat/9001/101/photo.png");

        assertNull(service.validateMedia(request));
    }

    @Test
    void rejectsAnImageObjectOwnedByAnotherMember() {
        MessageRequest request = imageRequest("chat/9001/202/photo.png");

        assertNotNull(service.validateMedia(request));
    }

    @Test
    void keepsLegacyUrlOnlyImagesCompatible() {
        MessageRequest request = imageRequest(null);
        request.getBody().setContent("https://legacy.example/image.png");

        assertNull(service.validateMedia(request));
    }

    private MessageRequest imageRequest(String objectName) {
        MessageBody body = new MessageBody();
        body.setContent("[图片]");
        body.setObjectName(objectName);
        body.setMediaContentType("image/png");
        body.setMediaWidth(1280);
        body.setMediaHeight(720);
        body.setMediaSize(1024L);
        body.setOriginalName("photo.png");
        MessageRequest request = new MessageRequest();
        request.setSessionId(9001L);
        request.setSenderId(101L);
        request.setType(1);
        request.setBody(body);
        return request;
    }
}
