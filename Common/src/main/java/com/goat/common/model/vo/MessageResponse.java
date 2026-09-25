package com.goat.common.model.vo;


import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.goat.common.model.dto.MessageBody;
import lombok.Data;

@Data
public class MessageResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;


    @JsonSerialize(using = ToStringSerializer.class)
    private Long senderId;


    private Integer type;


    private Integer sessionType;



    private Long createdTime;


    @JsonSerialize(using = ToStringSerializer.class)
    private Long messageId;


    private String clientMessageId;


    private String nickname;


    private String avatar;


    private Integer role;


    private MessageBody body;
}
