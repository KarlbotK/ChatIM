package com.goat.common.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageDeliveryRecord {

    private String clientMessageId;

    private Long messageId;

    private Long sessionId;

    private Long senderId;

    private String status;

    private Long createdTime;

    private Integer errorCode;

    private String errorMessage;
}
