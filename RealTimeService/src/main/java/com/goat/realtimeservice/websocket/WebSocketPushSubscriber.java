package com.goat.realtimeservice.websocket;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 订阅当前 RealTimeService 实例的 Redis 推送 channel。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketPushSubscriber implements MessageListener {

    private final WebSocketPushService pushService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            WebSocketPushEvent event = JSONUtil.toBean(body, WebSocketPushEvent.class);
            boolean pushed = pushService.pushLocal(event.getUserId(), event.getMessage());
            if (!pushed) {
                log.warn("跨实例 WebSocket 推送未找到本地连接，userId={}", event.getUserId());
            }
        } catch (Exception e) {
            log.error("处理跨实例 WebSocket 推送失败，消息={}", message, e);
        }
    }
}
