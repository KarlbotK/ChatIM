package com.goat.offlinedataservice.consumer;

import cn.hutool.json.JSONUtil;
import com.goat.common.model.dto.MessageRequest;
import com.goat.common.constant.CommonConstant;
import com.goat.common.model.vo.MessageDeliveryRecord;

import com.goat.offlinedataservice.model.dto.MessagePersistResult;
import com.goat.offlinedataservice.service.MessageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ConsumerOfflineService {

    @Resource
    private MessageService messageService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @KafkaListener(topics = CommonConstant.KAFKA_MESSAGE_TOPIC_STORE, groupId = "infinite-chat-store-group")
    public void consume(String message){
        try {
            log.info("收到消息存储事件: {}", message);
            MessageRequest messageRequest = JSONUtil.toBean(message, MessageRequest.class);
            MessagePersistResult persistResult = messageService.saveMessageToMySQL(messageRequest);
            MessageDeliveryRecord record = persistResult.delivery();
            String recordJson = JSONUtil.toJsonStr(record);
            stringRedisTemplate.opsForValue().set(
                    CommonConstant.MESSAGE_DELIVERY_PREFIX
                            + record.getSenderId() + ":" + record.getClientMessageId(),
                    recordJson,
                    CommonConstant.MESSAGE_DELIVERY_TTL_DAYS,
                    TimeUnit.DAYS
            );
            kafkaTemplate.send(
                    CommonConstant.KAFKA_MESSAGE_ACK_TOPIC,
                    record.getSenderId().toString(),
                    recordJson
            ).whenComplete((result, failure) -> {
                if (failure != null) {
                    log.error("消息持久化 ACK 发送失败，messageId={}", record.getMessageId(), failure);
                }
            });
            if (persistResult.created()) {
                kafkaTemplate.send(
                        CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH,
                        record.getSessionId().toString(),
                        message
                ).whenComplete((result, failure) -> {
                    if (failure != null) {
                        log.error("持久化消息实时推送事件发送失败，messageId={}", record.getMessageId(), failure);
                    }
                });
            }
            log.info("消息存储事件处理成功，messageId={}", record.getMessageId());
        } catch (Exception e) {
            log.error("消息存储事件处理失败: {}", message, e);
            throw new IllegalStateException("消息存储事件处理失败", e);
        }
    }
}
