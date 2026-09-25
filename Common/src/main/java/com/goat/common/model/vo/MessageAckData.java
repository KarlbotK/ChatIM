package com.goat.common.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageAckData {

    private String clientMessageId;

    private Long messageId;

    private Long sessionId;

    private String stage;

    private Long createdTime;

    private Integer errorCode;

    private String errorMessage;

    public static MessageAckData from(MessageDeliveryRecord record) {
        return MessageAckData.builder()
                .clientMessageId(record.getClientMessageId())
                .messageId(record.getMessageId())
                .sessionId(record.getSessionId())
                .stage(record.getStatus())
                .createdTime(record.getCreatedTime())
                .errorCode(record.getErrorCode())
                .errorMessage(record.getErrorMessage())
                .build();
    }
}
