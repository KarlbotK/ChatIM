package com.goat.realtimeservice.websocket;

import cn.hutool.json.JSONUtil;
import com.goat.common.constant.CommonConstant;
import com.goat.common.model.dto.MessageRequest;
import com.goat.common.model.entity.Message;
import com.goat.realtimeservice.constant.WebSocketConstant;
import com.goat.realtimeservice.utils.SnowflakeDynamicUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Date;

@Slf4j
@AllArgsConstructor
public class WebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private final StringRedisTemplate stringRedisTemplate;

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, TextWebSocketFrame textWebSocketFrame) {
        Channel channel = channelHandlerContext.channel();
        String msg = textWebSocketFrame.text();

        try {
            if (WebSocketConstant.HEARTBEAT_PING.equals(msg)) {
                // 心跳响应
                if (channel.isActive()) {
                    log.debug("Received heartbeat ping from {}", channel.id());
                    channel.writeAndFlush(new TextWebSocketFrame(WebSocketConstant.HEARTBEAT_PONG));
                }
            } else {
                // 业务消息
                if (channel.isActive()) {
                    sendMessageKafka(msg, channel);
                } else {
                    log.warn("Channel {} inactive, skip message: {}", channel.id(), msg);
                }
            }

        } catch (Exception e) {
            log.error("Error handling message from {}: {}", channel.id(), msg, e);
            channelHandlerContext.close();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {

        // 处理心跳
        if (evt instanceof IdleStateEvent event) {
            switch (event.state()) {
                case READER_IDLE:
                    log.error("读空闲超时");
                    ctx.close();
                    break;
                case WRITER_IDLE:
                    log.error("写空闲超时");
                case ALL_IDLE:
                    log.error("读写空闲超时");
            }
        }
    }

    public void sendMessageKafka(String msg, Channel channel) {
        MessageRequest messageRequest = JSONUtil.toBean(msg, MessageRequest.class);
        messageRequest.setMessageId(SnowflakeDynamicUtil.nextId());
        messageRequest.setCreatedTime(new Date());

        // 消息存储, 存储只存储一次，避免重复消费
        try {
            kafkaTemplate.send(CommonConstant.KAFKA_MESSAGE_TOPIC_STORE, JSONUtil.toJsonStr(messageRequest)).whenComplete((success, failure) -> {
                if (failure != null) {
                    log.error("消息存储事件发送失败，messageId: {}，原因: {}",
                            messageRequest.getMessageId(), failure.getMessage(), failure);
                } else {
                    log.info("消息存储事件发送成功，messageId: {}，offset: {}",
                            messageRequest.getMessageId(), success.getRecordMetadata().offset());
                }
            });
        } catch (Exception e) {
            log.error("消息存储事件发送异常，messageId: {}，原因: {}",
                    messageRequest.getMessageId(), e.getMessage(), e);
        }

        // 消息推送消息
        try {
            kafkaTemplate.send(CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH, messageRequest.getSessionId().toString(), JSONUtil.toJsonStr(messageRequest)).whenComplete((success, failure) -> {
                if (failure != null) {
                    log.error("消息推送事件发送失败，messageId: {}，原因: {}",
                            messageRequest.getMessageId(), failure.getMessage(), failure);
                } else {
                    log.info("消息推送事件发送成功，messageId: {}，offset: {}",
                            messageRequest.getMessageId(), success.getRecordMetadata().offset());
                }
            });
        } catch (Exception e) {
            log.error("消息推送事件发送异常，messageId: {}，原因: {}",
                    messageRequest.getMessageId(), e.getMessage(), e);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception caught in channel pipeline", cause);
        ctx.close();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        System.out.println("channel active");
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            clearChannel(ctx.channel());
            log.info("channel inactive: {}", ctx.channel().id());
        } finally {
            super.channelInactive(ctx);
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        System.out.println("handler added");
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        try {
            String userId = ChannelManager.getUserIdByChannel(ctx.channel());
            if (userId != null) {
                log.warn("handlerRemoved found stale mapping for user {}, cleaning up as fallback", userId);
                clearChannel(ctx.channel());
            } else {
                log.debug("handler removed: {}", ctx.channel().id());
            }
        } finally {
            super.handlerRemoved(ctx);
        }
    }

    public void clearChannel(Channel channel) {
        if (channel == null) {
            return;
        }

        String userId = ChannelManager.removeChannelUser(channel);
        if (userId == null) {
            return;
        }

        try {
            boolean removed = ChannelManager.removeUserChannel(userId, channel);
            if (removed) {
                saveOfflineTime(userId);
                log.info("channel mapping cleared: userId={}, channelId={}", userId, channel.id());
            } else {
                log.debug("skip clearing replaced channel mapping: userId={}, channelId={}", userId, channel.id());
            }
        } catch (Exception e) {
            log.error("clearChannel failed for channel: {}, userId: {}", channel.id(), userId, e);
        }
    }

    private void saveOfflineTime(String userId) {
        String key = CommonConstant.OFFLINE_KEY_REDIS + userId;
        String timestamp = String.valueOf(System.currentTimeMillis());
        stringRedisTemplate.opsForValue().set(key, timestamp);
        log.debug("记录用户离线时间: userId={}, timestamp={}", userId, timestamp);
    }

}
