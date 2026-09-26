package com.goat.realtimeservice.service;

import com.goat.common.model.dto.MessageBody;
import com.goat.common.model.dto.MessageRequest;
import com.goat.realtimeservice.client.UserServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class MessageDeliveryServiceTest {
    private final MessageDeliveryService service = new MessageDeliveryService(
            mock(StringRedisTemplate.class),
            mock(KafkaTemplate.class),
            mock(UserServiceClient.class)
    );

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
