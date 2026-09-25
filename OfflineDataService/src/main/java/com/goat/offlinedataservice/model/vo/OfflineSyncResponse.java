package com.goat.offlinedataservice.model.vo;

import com.goat.common.model.vo.MessageResponse;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OfflineSyncResponse {

    private List<MessageResponse> items;

    private String nextCursor;

    private boolean hasMore;

    private long serverTime;
}
