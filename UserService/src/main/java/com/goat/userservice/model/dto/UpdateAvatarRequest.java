package com.goat.userservice.model.dto;


import lombok.Data;

@Data
public class UpdateAvatarRequest {

    private String uri;

    private Long userId;
}