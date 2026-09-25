package com.goat.offlinedataservice.model.dto;

import com.goat.common.model.vo.MessageDeliveryRecord;

public record MessagePersistResult(MessageDeliveryRecord delivery, boolean created) {
}
