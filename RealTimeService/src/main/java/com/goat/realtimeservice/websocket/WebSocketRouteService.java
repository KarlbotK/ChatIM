package com.goat.realtimeservice.websocket;

import cn.hutool.json.JSONUtil;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 维护 userId 到 RealTimeService 实例的共享路由，并负责实例间实时转发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketRouteService {

    public static final String ROUTE_KEY_PREFIX = "ws:route:";
    public static final String PUSH_CHANNEL_PREFIX = "ws:push:";
    private static final String ROUTE_SEPARATOR = "|";

    private static final DefaultRedisScript<Long> REMOVE_ROUTE_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """, Long.class);

    private static final DefaultRedisScript<Long> REFRESH_ROUTE_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('EXPIRE', KEYS[1], ARGV[2])
                    end
                    return 0
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;

    /** 每个 RealTimeService 实例必须配置不同的 ID。 */
    @Value("${realtime.instance-id:realtime-1}")
    private String instanceId;

    @Value("${realtime.route-ttl-seconds:60}")
    private long routeTtlSeconds;

    /** 连接认证成功后登记用户所在实例和具体 Channel。 */
    public void bind(String userId, Channel channel) {
        redisTemplate.opsForValue().set(
                routeKey(userId),
                routeValue(channel),
                routeTtlSeconds,
                TimeUnit.SECONDS
        );
        log.debug("WebSocket 路由已登记，userId={}, instanceId={}, channelId={}",
                userId, instanceId, channel.id().asLongText());
    }

    /** 心跳时只为当前 Channel 续期，避免旧连接续期新连接的路由。 */
    public void refresh(String userId, Channel channel) {
        redisTemplate.execute(
                REFRESH_ROUTE_SCRIPT,
                Collections.singletonList(routeKey(userId)),
                routeValue(channel),
                String.valueOf(routeTtlSeconds)
        );
    }

    /** 断线时只删除仍然属于当前 Channel 的路由。 */
    public void remove(String userId, Channel channel) {
        Long removed = redisTemplate.execute(
                REMOVE_ROUTE_SCRIPT,
                Collections.singletonList(routeKey(userId)),
                routeValue(channel)
        );
        log.debug("WebSocket 路由清理完成，userId={}, instanceId={}, channelId={}, removed={}",
                userId, instanceId, channel.id().asLongText(), removed);
    }

    /** 返回目标用户当前所在的实例 ID。 */
    public String findInstanceId(Long userId) {
        if (userId == null) {
            return null;
        }
        String value = redisTemplate.opsForValue().get(routeKey(String.valueOf(userId)));
        if (value == null || value.isBlank()) {
            return null;
        }
        int separatorIndex = value.indexOf(ROUTE_SEPARATOR);
        return separatorIndex >= 0 ? value.substring(0, separatorIndex) : value;
    }

    public boolean isLocal(String targetInstanceId) {
        return instanceId.equals(targetInstanceId);
    }

    /** 向目标 RealTimeService 实例的 Redis channel 发布转发事件。 */
    public boolean publish(String targetInstanceId, Long userId, String message) {
        WebSocketPushEvent event = new WebSocketPushEvent(userId, message);
        Long subscriberCount = redisTemplate.convertAndSend(
                PUSH_CHANNEL_PREFIX + targetInstanceId,
                JSONUtil.toJsonStr(event)
        );
        return subscriberCount != null && subscriberCount > 0;
    }

    private String routeKey(String userId) {
        return ROUTE_KEY_PREFIX + userId;
    }

    private String routeValue(Channel channel) {
        return instanceId + ROUTE_SEPARATOR + channel.id().asLongText();
    }
}
