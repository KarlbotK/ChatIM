package com.goat.offlinedataservice.model.dto;

import lombok.Data;

@Data
public class SessionUnreadCount {

    private Long sessionId;

    private Long unreadCount;
}
