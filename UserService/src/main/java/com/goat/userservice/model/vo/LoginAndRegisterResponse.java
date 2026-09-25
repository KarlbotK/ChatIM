package com.goat.userservice.model.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class LoginAndRegisterResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String email;

    private String nickname;

    private String avatar;

    private Integer gender;

    private String description;

    private String accessToken;

    private String refreshToken;

    private String nettyUri;

    private Long offlineTime;
}
