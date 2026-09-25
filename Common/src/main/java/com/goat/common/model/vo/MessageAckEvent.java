package com.goat.common.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageAckEvent {

    private String event;

    private MessageAckData data;

    public static MessageAckEvent from(MessageDeliveryRecord record) {
        return new MessageAckEvent("message-ack", MessageAckData.from(record));
    }
}
