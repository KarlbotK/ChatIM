package com.goat.realtimeservice.consumer;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.goat.common.constant.SessionTypeConstant;
import com.goat.common.model.dto.MessageRequest;
import com.goat.common.model.vo.MessageResponse;
import com.goat.realtimeservice.client.UserServiceClient;
import com.goat.realtimeservice.websocket.WebSocketPushService;
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

    @Resource
    private WebSocketPushService webSocketPushService;

    @KafkaListener(topics = "message-topic", groupId = "infinite-chat-push-group-0")
    public void consume(String message) {
        try {
            log.info("收到消息推送事件：{}", message);
            MessageRequest messageRequest = JSONUtil.toBean(message, MessageRequest.class);
            if (messageRequest.getSessionType() == SessionTypeConstant.SIGNAL_TYPE) {
                signalMessage(messageRequest);
            } else if (messageRequest.getSessionType() == SessionTypeConstant.GROUP_TYPE) {
                groupMessage(messageRequest);
            }
        } catch (Exception e) {
            log.error("消息推送事件处理失败：{}", message, e);
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
        boolean routed = webSocketPushService.pushToUser(
                receiverId,
                JSONUtil.toJsonStr(messageResponse)
        );
        if (!routed) {
            log.info("用户不在线或没有 WebSocket 路由，userId={}", receiverId);
        }
    }

}
