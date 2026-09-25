package com.goat.offlinedataservice.model.dto;

import lombok.Data;

@Data
public class OfflineSyncRequest {

    private String cursor;

    private Integer limit;
}
