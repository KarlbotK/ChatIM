package com.goat.userservice.controller;

import com.goat.common.model.dto.SessionReadPosition;
import com.goat.userservice.service.UserSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/session")
@RequiredArgsConstructor
public class InternalSessionController {

    private final UserSessionService userSessionService;

    @GetMapping("/read-positions")
    public List<SessionReadPosition> getReadPositions(@RequestParam Long userId) {
        return userSessionService.getReadPositions(userId);
    }
}
