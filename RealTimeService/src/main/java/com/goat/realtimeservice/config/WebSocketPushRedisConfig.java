package com.goat.realtimeservice.config;

import com.goat.realtimeservice.websocket.WebSocketPushSubscriber;
import com.goat.realtimeservice.websocket.WebSocketRouteService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 配置。每个实例只订阅自己的推送 channel。
 */
@Configuration
@ConditionalOnProperty(prefix = "realtime.redis-push", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class WebSocketPushRedisConfig {

    private final WebSocketPushSubscriber pushSubscriber;

    @Value("${realtime.instance-id:realtime-1}")
    private String instanceId;

    @Bean
    public RedisMessageListenerContainer webSocketPushListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                pushSubscriber,
                new ChannelTopic(WebSocketRouteService.PUSH_CHANNEL_PREFIX + instanceId)
        );
        return container;
    }
}
