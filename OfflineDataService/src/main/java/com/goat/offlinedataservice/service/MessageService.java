package com.goat.offlinedataservice.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.goat.common.model.dto.MessageRequest;
import com.goat.common.model.dto.SessionMessageSummaryRequest;
import com.goat.common.model.vo.MessageResponse;
import com.goat.common.model.vo.MessageDeliveryRecord;
import com.goat.common.model.vo.SessionMessageSummary;
import com.goat.offlinedataservice.model.dto.HistoryMessageRequest;
import com.goat.offlinedataservice.model.dto.MessagePersistResult;
import com.goat.offlinedataservice.model.dto.OfflineMessageRequest;
import com.goat.offlinedataservice.model.dto.OfflineSyncRequest;
import com.goat.offlinedataservice.model.entity.Message;
import com.goat.offlinedataservice.model.vo.OfflineSyncResponse;

import java.util.List;
import java.util.Map;


public interface MessageService extends IService<Message> {
    MessagePersistResult saveMessageToMySQL(MessageRequest messageRequest);

    MessageDeliveryRecord getMessageStatus(Long senderId, String clientMessageId);

    OfflineSyncResponse syncOfflineMessages(Long userId, OfflineSyncRequest request);

    List<SessionMessageSummary> getSessionMessageSummaries(SessionMessageSummaryRequest request);

    boolean isMessageInSession(Long sessionId, Long messageId);

    Map<Long, Long> getUnreadCounts(Long userId);

    Map<Long, List<MessageResponse>> getOfflineMessages(OfflineMessageRequest request);

    List<MessageResponse> getHistoryMessages(HistoryMessageRequest request);
}
