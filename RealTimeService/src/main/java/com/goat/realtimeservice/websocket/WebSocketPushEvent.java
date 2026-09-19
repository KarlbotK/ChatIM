package com.goat.realtimeservice.websocket;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RealTimeService 实例之间转发的 WebSocket 推送事件。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketPushEvent {

    /** 目标用户 ID */
    private Long userId;

    /** 发给前端的完整 JSON */
    private String message;
}
