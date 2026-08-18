package com.goat.offlinedataservice.consumer;

import cn.hutool.json.JSONUtil;
import com.goat.common.model.dto.MessageRequest;

import com.goat.offlinedataservice.service.MessageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ConsumerOfflineService {

    @Resource
    private MessageService messageService;

    @KafkaListener(topics = "store-topic", groupId = "infinite-chat-store-group")
    public void consume(String message){
        try {
            log.info("收到消息存储事件: {}", message);
            MessageRequest messageRequest = JSONUtil.toBean(message, MessageRequest.class);
            messageService.saveMessageToMySQL(messageRequest);
            log.info("消息存储事件处理成功: {}", messageRequest);
        } catch (Exception e) {
            log.error("消息存储事件处理失败: {}", message, e);
        }
    }
}
