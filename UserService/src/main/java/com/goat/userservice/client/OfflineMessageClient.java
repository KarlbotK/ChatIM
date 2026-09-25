package com.goat.userservice.client;

import com.goat.common.model.dto.SessionMessageSummaryRequest;
import com.goat.common.model.vo.SessionMessageSummary;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "OfflineDataService")
public interface OfflineMessageClient {

    @PostMapping("/internal/message/session-summaries")
    List<SessionMessageSummary> getSessionMessageSummaries(
            @RequestBody SessionMessageSummaryRequest request);

    @GetMapping("/internal/message/in-session")
    boolean isMessageInSession(
            @RequestParam("sessionId") Long sessionId,
            @RequestParam("messageId") Long messageId);
}
