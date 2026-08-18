package com.goat.redpacketservice.consumer;

import java.util.Map;

import com.goat.redpacketservice.config.KafkaConfig;
import com.goat.redpacketservice.constant.KafkaConstant;
import com.goat.redpacketservice.service.RedPacketService;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 红包Kafka消费者
 * <p>
 * 负责处理红包领取记录和完成事件
 *
 * @author goat
 */
@Component
@Slf4j
public class RedPacketConsumer {

    @Autowired
    private RedPacketService redPacketService;

    /**
     * 消费红包领取事件
     * <p>
     * Topic: topic-redpacket-receive
     * <p>
     * 消息格式：
     * <pre>
     * {
     *     "userId": 1829109273758666752,
     *     "redPacketId": 1847283058810687488,
     *     "receivedAmount": 100,
     *     "receiveTime": 1678886400000
     * }
     * </pre>
     * <p>
     * 处理逻辑：
     * 1. 插入领取记录到数据库
     * 2. 增加用户余额
     * 3. 记录余额变动日志
     *
     * @param message Kafka消息
     */
    @KafkaListener(
            topics = KafkaConfig.TOPIC_REDPACKET_RECEIVE,
            groupId = KafkaConstant.GROUP_RECEIVE_HANDLER,
            concurrency = KafkaConstant.DEFAULT_CONCURRENCY
    )
    public void handleRedPacketReceive(String message) {
        try {
            log.info("收到红包领取事件: {}", message);

            // 解析消息
            Map<String, Object> eventData = JSONUtil.toBean(message, Map.class);
            Long userId = Long.parseLong(eventData.get("userId").toString());
            Long redPacketId = Long.parseLong(eventData.get("redPacketId").toString());
            Long receivedAmount = Long.parseLong(eventData.get("receivedAmount").toString());
            Long receiveTime = Long.parseLong(eventData.get("receiveTime").toString());

            // 调用服务层处理领取记录
            redPacketService.handleRedPacketReceive(userId, redPacketId, receivedAmount, receiveTime);

            log.info("红包领取事件处理成功，红包ID: {}, 用户ID: {}", redPacketId, userId);

        } catch (Exception e) {
            log.error("处理红包领取事件失败: {}", message, e);
            // 注意：这里可以根据需要决定是否抛出异常来触发重试
            // 如果抛出异常，Kafka会根据配置进行重试
        }
    }

    /**
     * 消费红包领完事件
     * <p>
     * Topic: topic-redpacket-completed
     * <p>
     * 消息格式：
     * <pre>
     * {
     *     "redPacketId": 1847283058810687488
     * }
     * </pre>
     * <p>
     * 处理逻辑：
     * 1. 更新红包状态为"已领取完"
     * 2. 清理Redis缓存（金额池、领取记录、过期ZSET）
     *
     * @param message Kafka消息
     */
    @KafkaListener(
            topics = KafkaConfig.TOPIC_REDPACKET_COMPLETED,
            groupId = KafkaConstant.GROUP_COMPLETED_HANDLER,
            concurrency = KafkaConstant.DEFAULT_CONCURRENCY
    )
    public void handleRedPacketCompleted(String message) {
        try {
            log.info("收到红包领完事件: {}", message);

            // 解析消息
            Map<String, Object> eventData = JSONUtil.toBean(message, Map.class);
            Long redPacketId = Long.parseLong(eventData.get("redPacketId").toString());

            // 调用服务层处理领完事件
            redPacketService.handleRedPacketCompleted(redPacketId);

            log.info("红包领完事件处理成功，红包ID: {}", redPacketId);

        } catch (Exception e) {
            log.error("处理红包领完事件失败: {}", message, e);
        }
    }
}