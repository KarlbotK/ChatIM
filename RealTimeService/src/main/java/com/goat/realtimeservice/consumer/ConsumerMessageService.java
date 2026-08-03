package com.goat.realtimeservice.consumer;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.goat.common.constant.SessionTypeConstant;
import com.goat.common.model.dto.MessageRequest;
import com.goat.common.model.vo.MessageResponse;
import com.goat.realtimeservice.client.UserServiceClient;
import com.goat.realtimeservice.websocket.ChannelManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
@Slf4j
public class ConsumerMessageService {

    @Resource
    private UserServiceClient userServiceClient;

    @KafkaListener(topics = "message-topic", groupId = "infinite-chat-push-group-0")
    public void consume(String message) {
        System.out.println("收到消息：" + message);
        MessageRequest messageRequest = JSONUtil.toBean(message, MessageRequest.class);
        System.out.println("收到消息：" + messageRequest);
        if (messageRequest.getSessionType() == SessionTypeConstant.SIGNAL_TYPE) {
            signalMessage(messageRequest);
        } else if (messageRequest.getSessionType() == SessionTypeConstant.GROUP_TYPE) {
            groupMessage(messageRequest);
        }
    }

    public void signalMessage(MessageRequest messageRequest) {
        MessageResponse messageResponse = createMessageResponse(messageRequest);
        pushMessageToUser(messageResponse, messageRequest.getSenderId());
        pushMessageToUser(messageResponse, messageRequest.getReceiverId());

    }

    public void groupMessage(MessageRequest messageRequest) {
        List<Long> receiveUserIds = userServiceClient.getUserIdBySessionId(messageRequest.getSessionId());
        MessageResponse messageResponse = createMessageResponse(messageRequest);
        for (Long receiveUserId : receiveUserIds) {
            pushMessageToUser(messageResponse, receiveUserId);
        }
    }

    public MessageResponse createMessageResponse(MessageRequest messageRequest) {
        MessageResponse messageResponse = new MessageResponse();
        BeanUtil.copyProperties(messageRequest, messageResponse);
        messageResponse.setCreatedTime(messageRequest.getCreatedTime().getTime());
        return messageResponse;

    }

    public void pushMessageToUser(MessageResponse messageResponse, Long receiverId) {
        Channel channel = ChannelManager.getChannelByUserId(receiverId.toString());
        if (channel != null) {
            TextWebSocketFrame frame = new TextWebSocketFrame(JSONUtil.toJsonStr(messageResponse));
            channel.writeAndFlush(frame).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    log.info("消息发送成功: {}", messageResponse);
                } else {
                    log.info("消息发送失败: {}", future.cause() != null ? future.cause().getMessage() : "未知错误");
                }
            });
        } else {
            log.info("channel 不存在");
        }
    }

}
