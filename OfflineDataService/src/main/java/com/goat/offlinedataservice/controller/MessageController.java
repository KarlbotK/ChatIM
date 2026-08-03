package com.goat.offlinedataservice.controller;

import java.util.List;
import java.util.Map;

import com.goat.common.common.BaseResponse;
import com.goat.common.common.ResultUtils;
import com.goat.common.model.vo.MessageResponse;
import com.goat.offlinedataservice.model.dto.HistoryMessageRequest;
import com.goat.offlinedataservice.model.dto.OfflineMessageRequest;

import com.goat.offlinedataservice.service.MessageService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/message")
public class MessageController {

    @Resource
    private MessageService messageService;

    /**
     * 获取离线消息（用户上线后调用）
     *
     * @return Map<sessionId, List<消息>>
     */
    @PostMapping("/offline")
    public BaseResponse<Map<Long, List<MessageResponse>>> getOfflineMessages(
            @RequestBody OfflineMessageRequest request) {
        return ResultUtils.success(messageService.getOfflineMessages(request));
    }

    /**
     * 获取历史消息（往上翻页）
     */
    @PostMapping("/history")
    public BaseResponse<List<MessageResponse>> getHistoryMessages(
            @RequestBody HistoryMessageRequest request) {
        return ResultUtils.success(messageService.getHistoryMessages(request));
    }
}
