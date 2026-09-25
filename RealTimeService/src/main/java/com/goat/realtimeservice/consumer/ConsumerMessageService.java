package com.goat.realtimeservice.consumer;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.goat.common.constant.SessionTypeConstant;
import com.goat.common.constant.CommonConstant;
import com.goat.common.model.dto.MessageRequest;
import com.goat.common.model.vo.MessageAckEvent;
import com.goat.common.model.vo.MessageDeliveryRecord;
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

    @KafkaListener(topics = CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH, groupId = "infinite-chat-push-group-0")
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

    @KafkaListener(topics = CommonConstant.KAFKA_MESSAGE_ACK_TOPIC, groupId = "infinite-chat-message-ack-group")
    public void consumeMessageAck(String message) {
        try {
            MessageDeliveryRecord record = JSONUtil.toBean(message, MessageDeliveryRecord.class);
            if (record.getSenderId() == null) {
                log.warn("忽略缺少 senderId 的消息 ACK: {}", message);
                return;
            }
            boolean routed = webSocketPushService.pushToUser(
                    record.getSenderId(),
                    JSONUtil.toJsonStr(MessageAckEvent.from(record))
            );
            if (!routed) {
                log.debug("发送者当前不在线，ACK 将由状态接口恢复，senderId={}", record.getSenderId());
            }
        } catch (Exception exception) {
            log.error("消息 ACK 处理失败: {}", message, exception);
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
