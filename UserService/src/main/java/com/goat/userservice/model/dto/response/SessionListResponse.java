package com.goat.userservice.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SessionListResponse {

    private List<SessionSummaryResponse> items;

    private String nextCursor;

    private boolean hasMore;

    private long serverTime;
}
