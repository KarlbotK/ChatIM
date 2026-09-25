package com.goat.offlinedataservice.controller;

import com.goat.common.model.dto.SessionMessageSummaryRequest;
import com.goat.common.model.vo.SessionMessageSummary;
import com.goat.offlinedataservice.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/message")
@RequiredArgsConstructor
public class InternalMessageController {

    private final MessageService messageService;

    @PostMapping("/session-summaries")
    public List<SessionMessageSummary> getSessionMessageSummaries(
            @RequestBody SessionMessageSummaryRequest request) {
        return messageService.getSessionMessageSummaries(request);
    }

    @GetMapping("/in-session")
    public boolean isMessageInSession(
            @RequestParam Long sessionId,
            @RequestParam Long messageId) {
        return messageService.isMessageInSession(sessionId, messageId);
    }
}
