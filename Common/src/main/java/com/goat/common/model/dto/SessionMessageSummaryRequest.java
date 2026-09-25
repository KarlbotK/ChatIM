package com.goat.common.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class SessionMessageSummaryRequest {

    private Long userId;

    private List<SessionReadPosition> sessions;
}
