package com.goat.realtimeservice.websocket;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 统一处理本机推送和跨实例推送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketPushService {

    private final WebSocketRouteService routeService;

    /**
     * 向目标用户推送消息。目标在本机时直接写 Channel，否则转发到目标实例。
     *
     * @return 是否找到路由并完成本机写入或跨实例发布
     */
    public boolean pushToUser(Long userId, String message) {
        String targetInstanceId = routeService.findInstanceId(userId);
        if (targetInstanceId == null) {
            return false;
        }

        if (routeService.isLocal(targetInstanceId)) {
            return pushLocal(userId, message);
        }

        boolean published = routeService.publish(targetInstanceId, userId, message);
        if (!published) {
            log.warn("目标实例没有 Redis 推送订阅者，userId={}, targetInstanceId={}",
                    userId, targetInstanceId);
        } else {
            log.debug("WebSocket 消息已转发，userId={}, targetInstanceId={}",
                    userId, targetInstanceId);
        }
        return published;
    }

    /** 只从当前实例的 ChannelManager 查找并推送。 */
    public boolean pushLocal(Long userId, String message) {
        Channel channel = ChannelManager.getChannelByUserId(String.valueOf(userId));
        if (channel == null || !channel.isActive()) {
            return false;
        }

        channel.writeAndFlush(new TextWebSocketFrame(message))
                .addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        log.debug("WebSocket 本机推送成功，userId={}", userId);
                    } else {
                        log.warn("WebSocket 本机推送失败，userId={}, cause={}",
                                userId,
                                future.cause() == null ? "unknown" : future.cause().getMessage());
                    }
                });
        return true;
    }
}
