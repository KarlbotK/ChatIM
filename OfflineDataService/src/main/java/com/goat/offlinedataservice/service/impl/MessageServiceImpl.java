package com.goat.offlinedataservice.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.goat.common.common.ErrorCode;
import com.goat.common.constant.CommonConstant;
import com.goat.common.constant.MessageTypeConstant;
import com.goat.common.constant.MessageDeliveryStatus;
import com.goat.common.exception.ThrowUtils;
import com.goat.common.model.dto.MessageBody;
import com.goat.common.model.dto.MessageRequest;
import com.goat.common.model.dto.SessionMessageSummaryRequest;
import com.goat.common.model.dto.SessionReadPosition;
import com.goat.common.model.vo.MessageResponse;
import com.goat.common.model.vo.MessageDeliveryRecord;
import com.goat.common.model.vo.SessionMessageSummary;
import com.goat.offlinedataservice.client.UserServiceClient;
import com.goat.offlinedataservice.mapper.MessageMapper;
import com.goat.offlinedataservice.model.dto.HistoryMessageRequest;
import com.goat.offlinedataservice.model.dto.MessagePersistResult;
import com.goat.offlinedataservice.model.dto.OfflineMessageRequest;
import com.goat.offlinedataservice.model.dto.OfflineSyncRequest;
import com.goat.offlinedataservice.model.dto.SessionUnreadCount;
import com.goat.offlinedataservice.model.entity.Message;
import com.goat.offlinedataservice.model.vo.OfflineSyncResponse;
import com.goat.offlinedataservice.service.MessageService;
import com.goat.offlinedataservice.utils.OfflineCursorCodec;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;


@Service
@Slf4j
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message>
    implements MessageService{
    @Override
    public MessagePersistResult saveMessageToMySQL(MessageRequest messageRequest) {
        Message message = new Message();
        BeanUtil.copyProperties(messageRequest, message);
        MessageBody body = messageRequest.getBody();
        Integer type = messageRequest.getType();
        // 红包消息：将整个 body 序列化为 JSON 存入 content
        if (type != null && type == MessageTypeConstant.RED_PACKET_MESSAGE) {
            message.setContent(JSON.toJSONString(messageRequest.getBody()));
        } else {
            message.setContent(body.getContent());
        }
        message.setReplyId(body.getReplyId());
        try {
            ThrowUtils.throwIf(!this.save(message), ErrorCode.SYSTEM_ERROR);
            return new MessagePersistResult(persistedRecord(message), true);
        } catch (DuplicateKeyException exception) {
            Message existing = getExistingMessage(messageRequest);
            if (existing == null) {
                throw exception;
            }
            log.info("重复消息复用原结果，senderId={}，clientMessageId={}，messageId={}",
                    messageRequest.getSenderId(),
                    messageRequest.getClientMessageId(),
                    existing.getMessageId());
            return new MessagePersistResult(persistedRecord(existing), false);
        }
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private MessageMapper messageMapper;

    @Resource
    private UserServiceClient userServiceClient;

    @Resource
    private OfflineCursorCodec offlineCursorCodec;

    @Override
    public List<SessionMessageSummary> getSessionMessageSummaries(SessionMessageSummaryRequest request) {
        if (request == null || request.getUserId() == null
                || request.getSessions() == null || request.getSessions().isEmpty()) {
            return Collections.emptyList();
        }

        List<SessionReadPosition> positions = request.getSessions().stream()
                .filter(Objects::nonNull)
                .filter(position -> position.getSessionId() != null)
                .toList();
        if (positions.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> sessionIds = positions.stream()
                .map(SessionReadPosition::getSessionId)
                .distinct()
                .toList();
        Map<Long, MessageResponse> latestMessages = convertToResponses(
                messageMapper.selectLatestBySessionIds(sessionIds)
        ).stream().collect(Collectors.toMap(MessageResponse::getSessionId, response -> response));
        Map<Long, Long> unreadCounts = messageMapper
                .selectUnreadCounts(request.getUserId(), positions)
                .stream()
                .collect(Collectors.toMap(
                        SessionUnreadCount::getSessionId,
                        count -> count.getUnreadCount() == null ? 0L : count.getUnreadCount()
                ));

        return sessionIds.stream()
                .map(sessionId -> SessionMessageSummary.builder()
                        .sessionId(sessionId)
                        .lastMessage(latestMessages.get(sessionId))
                        .unreadCount(unreadCounts.getOrDefault(sessionId, 0L))
                        .build())
                .toList();
    }

    @Override
    public boolean isMessageInSession(Long sessionId, Long messageId) {
        if (sessionId == null || messageId == null) {
            return false;
        }
        QueryWrapper<Message> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("session_id", sessionId)
                .eq("message_id", messageId);
        return messageMapper.selectCount(queryWrapper) > 0;
    }

    @Override
    public Map<Long, Long> getUnreadCounts(Long userId) {
        List<SessionReadPosition> positions = userServiceClient.getReadPositions(userId);
        if (positions == null || positions.isEmpty()) {
            return Collections.emptyMap();
        }
        SessionMessageSummaryRequest request = new SessionMessageSummaryRequest();
        request.setUserId(userId);
        request.setSessions(positions);
        return getSessionMessageSummaries(request).stream()
                .collect(Collectors.toMap(
                        SessionMessageSummary::getSessionId,
                        SessionMessageSummary::getUnreadCount
                ));
    }

    @Override
    public OfflineSyncResponse syncOfflineMessages(Long userId, OfflineSyncRequest request) {
        int requestedLimit = request.getLimit() == null ? CommonConstant.DEFAULT_LIMIT : request.getLimit();
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        OfflineCursorCodec.OfflineCursor cursor = offlineCursorCodec.decode(request.getCursor(), userId);
        List<Long> sessionIds = userServiceClient.getSessionIdsByUserId(userId);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return OfflineSyncResponse.builder()
                    .items(Collections.emptyList())
                    .nextCursor(request.getCursor())
                    .hasMore(false)
                    .serverTime(System.currentTimeMillis())
                    .build();
        }

        QueryWrapper<Message> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("session_id", sessionIds);
        if (cursor.createdTime() > 0 || cursor.messageId() > 0) {
            Date cursorTime = new Date(cursor.createdTime());
            queryWrapper.and(wrapper -> wrapper
                    .gt("created_time", cursorTime)
                    .or()
                    .eq("created_time", cursorTime)
                    .gt("message_id", cursor.messageId()));
        }
        queryWrapper.orderByAsc("created_time")
                .orderByAsc("message_id")
                .last("LIMIT " + (limit + 1));

        List<Message> rows = messageMapper.selectList(queryWrapper);
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        List<MessageResponse> items = convertToResponses(rows);
        String nextCursor = request.getCursor();
        if (!rows.isEmpty()) {
            Message last = rows.get(rows.size() - 1);
            nextCursor = offlineCursorCodec.encode(
                    userId,
                    last.getCreatedTime().getTime(),
                    last.getMessageId()
            );
        }

        return OfflineSyncResponse.builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .serverTime(System.currentTimeMillis())
                .build();
    }

    @Override
    public MessageDeliveryRecord getMessageStatus(Long senderId, String clientMessageId) {
        String redisValue = stringRedisTemplate.opsForValue().get(
                CommonConstant.MESSAGE_DELIVERY_PREFIX + senderId + ":" + clientMessageId
        );
        MessageDeliveryRecord cached = null;
        if (redisValue != null) {
            cached = JSON.parseObject(redisValue, MessageDeliveryRecord.class);
            if (!MessageDeliveryStatus.ACCEPTED.equals(cached.getStatus())) {
                return cached;
            }
        }

        try {
            QueryWrapper<Message> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("sender_id", senderId)
                    .eq("client_message_id", clientMessageId)
                    .last("LIMIT 1");
            Message message = messageMapper.selectOne(queryWrapper);
            if (message != null) {
                MessageDeliveryRecord persisted = persistedRecord(message);
                stringRedisTemplate.opsForValue().set(
                        CommonConstant.MESSAGE_DELIVERY_PREFIX + senderId + ":" + clientMessageId,
                        JSON.toJSONString(persisted),
                        CommonConstant.MESSAGE_DELIVERY_TTL_DAYS,
                        java.util.concurrent.TimeUnit.DAYS
                );
                return persisted;
            }
        } catch (RuntimeException exception) {
            if (cached != null) {
                log.warn("消息状态数据库确认失败，返回缓存状态，senderId={}，clientMessageId={}",
                        senderId, clientMessageId, exception);
                return cached;
            }
            throw exception;
        }

        if (cached != null) {
            return cached;
        }

        return MessageDeliveryRecord.builder()
                .clientMessageId(clientMessageId)
                .senderId(senderId)
                .status(MessageDeliveryStatus.NOT_FOUND)
                .build();
    }

    private Message getExistingMessage(MessageRequest request) {
        if (request.getClientMessageId() != null) {
            QueryWrapper<Message> clientMessageQuery = new QueryWrapper<>();
            clientMessageQuery.eq("sender_id", request.getSenderId())
                    .eq("client_message_id", request.getClientMessageId())
                    .last("LIMIT 1");
            Message existing = messageMapper.selectOne(clientMessageQuery);
            if (existing != null) {
                return existing;
            }
        }
        return messageMapper.selectById(request.getMessageId());
    }

    private MessageDeliveryRecord persistedRecord(Message message) {
        return MessageDeliveryRecord.builder()
                .clientMessageId(message.getClientMessageId())
                .messageId(message.getMessageId())
                .sessionId(message.getSessionId())
                .senderId(message.getSenderId())
                .status(MessageDeliveryStatus.PERSISTED)
                .createdTime(message.getCreatedTime() == null ? null : message.getCreatedTime().getTime())
                .build();
    }


    // ==================== 离线消息查询 ====================

    @Override
    public Map<Long, List<MessageResponse>> getOfflineMessages(OfflineMessageRequest request) {
        Long userId = request.getUserId();
        Long offlineTime = request.getOfflineTime();

        if (userId == null || offlineTime == null) {
            return Collections.emptyMap();
        }

        // 1. 获取用户的所有会话
        List<Long> sessionIds = userServiceClient.getSessionIdsByUserId(userId);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 2. 遍历每个会话，获取离线后的消息
        Map<Long, List<MessageResponse>> result = new HashMap<>();
        long hotBoundary = System.currentTimeMillis() - CommonConstant.SEVEN_DAYS_MILLIS;

        for (Long sessionId : sessionIds) {
            List<MessageResponse> messages = getMessagesAfter(sessionId, offlineTime, hotBoundary);
            if (!messages.isEmpty()) {
                result.put(sessionId, messages);
            }
        }

        log.info("用户 {} 离线消息查询完成，共 {} 个会话有新消息", userId, result.size());
        return result;
    }

    /**
     * 获取指定时间之后的消息（离线消息）
     */
    private List<MessageResponse> getMessagesAfter(Long sessionId, long afterTime, long hotBoundary) {
        List<MessageResponse> result = new ArrayList<>();

        // 1. 查 Redis（热数据）
        if (afterTime >= hotBoundary) {
            // 离线时间在热数据范围内，直接查 Redis
            List<MessageResponse> redisMessages = getMessagesFromRedisAfter(sessionId, afterTime);
            result.addAll(redisMessages);
        } else {
            // 离线时间在冷数据范围，需要同时查 Redis 和 MySQL
            // 先查 Redis 全部热数据
            List<MessageResponse> redisMessages = getMessagesFromRedisAfter(sessionId, hotBoundary);
            result.addAll(redisMessages);

            // 再查 MySQL 冷数据
            List<MessageResponse> mysqlMessages = getMessagesFromMySQLAfter(sessionId, afterTime, hotBoundary);
            result.addAll(mysqlMessages);
        }

        // 按时间正序（旧消息在前）
        result.sort(Comparator.comparing(MessageResponse::getCreatedTime));
        return result;
    }

    /**
     * 从 Redis 获取指定时间之后的消息
     */
    private List<MessageResponse> getMessagesFromRedisAfter(Long sessionId, long afterTime) {
        String key = CommonConstant.SESSION_KEY_REDIS + sessionId;
        // (afterTime, +inf] 开区间，不包含 afterTime 这一刻的消息
        Set<String> messageJsonSet = stringRedisTemplate.opsForZSet()
                .rangeByScore(key, afterTime + 1, Double.MAX_VALUE);

        if (messageJsonSet == null || messageJsonSet.isEmpty()) {
            return Collections.emptyList();
        }

        List<MessageResponse> messages = new ArrayList<>();
        for (String json : messageJsonSet) {
            messages.add(JSON.parseObject(json, MessageResponse.class));
        }
        return messages;
    }

    /**
     * 从 MySQL 获取冷数据区间的离线消息
     */
    private List<MessageResponse> getMessagesFromMySQLAfter(Long sessionId, long afterTime, long beforeTime) {
        QueryWrapper<Message> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("session_id", sessionId)
                .gt("created_time", new Date(afterTime))
                .lt("created_time", new Date(beforeTime))
                .orderByAsc("created_time");

        List<Message> messages = messageMapper.selectList(queryWrapper);
        return convertToResponses(messages);
    }

    // ==================== 历史消息查询（往上翻页） ====================

    @Override
    public List<MessageResponse> getHistoryMessages(HistoryMessageRequest request) {
        Long sessionId = request.getSessionId();
        Long beforeTime = request.getBeforeTime();
        int limit = request.getLimit() != null ? request.getLimit() : CommonConstant.DEFAULT_LIMIT;

        if (sessionId == null || beforeTime == null) {
            return Collections.emptyList();
        }

        long hotBoundary = System.currentTimeMillis() - CommonConstant.SEVEN_DAYS_MILLIS;

        List<MessageResponse> result = new ArrayList<>();

        // 1. 如果 beforeTime 在热数据范围内，先查 Redis
        if (beforeTime > hotBoundary) {
            List<MessageResponse> redisMessages = getMessagesFromRedisBefore(sessionId, beforeTime, limit);
            result.addAll(redisMessages);
        }

        // 2. Redis 不够，再查 MySQL
        if (result.size() < limit) {
            int remaining = limit - result.size();
            // MySQL 查询的 beforeTime：取 Redis 最早消息的时间，或者原始 beforeTime
            long mysqlBeforeTime = beforeTime > hotBoundary ? hotBoundary : beforeTime;

            List<MessageResponse> mysqlMessages = getMessagesFromMySQLBefore(sessionId, mysqlBeforeTime, remaining);
            result.addAll(mysqlMessages);
        }

        // 按时间倒序（新消息在前，符合往上翻页的习惯）
        result.sort(Comparator.comparing(MessageResponse::getCreatedTime).reversed());
        return result;
    }

    /**
     * 从 Redis 获取指定时间之前的消息
     */
    private List<MessageResponse> getMessagesFromRedisBefore(Long sessionId, long beforeTime, int limit) {
        String key = CommonConstant.SESSION_KEY_REDIS + sessionId;
        // [0, beforeTime) 左闭右开
        Set<String> messageJsonSet = stringRedisTemplate.opsForZSet()
                .reverseRangeByScore(key, 0, beforeTime - 1, 0, limit);

        if (messageJsonSet == null || messageJsonSet.isEmpty()) {
            return Collections.emptyList();
        }

        List<MessageResponse> messages = new ArrayList<>();
        for (String json : messageJsonSet) {
            messages.add(JSON.parseObject(json, MessageResponse.class));
        }
        return messages;
    }

    /**
     * 从 MySQL 获取指定时间之前的消息
     */
    private List<MessageResponse> getMessagesFromMySQLBefore(Long sessionId, long beforeTime, int limit) {
        QueryWrapper<Message> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("session_id", sessionId)
                .lt("created_time", new Date(beforeTime))
                .orderByDesc("created_time")
                .last("LIMIT " + limit);

        List<Message> messages = messageMapper.selectList(queryWrapper);
        return convertToResponses(messages);
    }

    // ==================== 数据转换 ====================

    private List<MessageResponse> convertToResponses(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }

        List<MessageResponse> responses = new ArrayList<>();
        for (Message msg : messages) {
            MessageResponse response = new MessageResponse();
            response.setMessageId(msg.getMessageId());
            response.setClientMessageId(msg.getClientMessageId());
            response.setSessionId(msg.getSessionId());
            response.setSenderId(msg.getSenderId());
            response.setType(msg.getType());
            response.setSessionType(msg.getSessionType());
            // MySQL 和 Redis 统一使用毫秒时间戳
            response.setCreatedTime(msg.getCreatedTime().getTime());

            MessageBody body = new MessageBody();
            body.setContent(msg.getContent());
            body.setReplyId(msg.getReplyId());
            response.setBody(body);

            responses.add(response);
        }
        return responses;
    }


}
