package com.goat.common.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionReadPosition {

    private Long sessionId;

    private Long lastReadMessageId;
}
