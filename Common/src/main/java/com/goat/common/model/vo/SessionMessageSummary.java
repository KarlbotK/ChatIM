package com.goat.common.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMessageSummary {

    private Long sessionId;

    private MessageResponse lastMessage;

    private long unreadCount;
}
