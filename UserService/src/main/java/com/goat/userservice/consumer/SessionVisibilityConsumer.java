package com.goat.userservice.consumer;

import cn.hutool.json.JSONUtil;
import com.goat.common.constant.CommonConstant;
import com.goat.common.model.dto.MessageRequest;
import com.goat.userservice.service.UserSessionService;
import com.goat.userservice.service.NotificationService;
import com.goat.userservice.model.dto.response.SessionPreferenceResponse;
import com.goat.userservice.model.entity.UserSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class SessionVisibilityConsumer {

    private final UserSessionService userSessionService;
    private final NotificationService notificationService;

    @KafkaListener(
            topics = CommonConstant.KAFKA_MESSAGE_TOPIC_PUSH,
            groupId = "user-session-visibility-group"
    )
    public void revealSessionAfterNewMessage(String messageJson) {
        MessageRequest message = JSONUtil.toBean(messageJson, MessageRequest.class);
        if (message == null || message.getSessionId() == null) {
            log.warn("忽略缺少会话 ID 的消息可见性事件");
            return;
        }
        var revealed = userSessionService.revealHiddenSession(message.getSessionId());
        if (!revealed.isEmpty()) {
            log.info("新消息恢复隐藏会话，sessionId={}，affectedMemberships={}",
                    message.getSessionId(), revealed.size());
            revealed.forEach(membership -> notifyPreferenceRestored(message.getSessionId(), membership));
        }
    }

    private void notifyPreferenceRestored(Long sessionId, UserSession membership) {
        notificationService.pushSessionPreferenceUpdated(
                membership.getUserId(),
                SessionPreferenceResponse.builder()
                        .sessionId(sessionId)
                        .pinned(Boolean.TRUE.equals(membership.getPinned()))
                        .muted(Boolean.TRUE.equals(membership.getMuted()))
                        .hidden(false)
                        .updatedTime(System.currentTimeMillis())
                        .build()
        );
    }
}
