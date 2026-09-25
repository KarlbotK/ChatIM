package com.goat.userservice.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WebSocketTicketResponse {

    private String ticket;

    private String nettyUri;

    private long expiresInSeconds;

    private long expiresAt;
}
