package com.goat.common.model.dto;


import lombok.Data;

@Data
public class MessageBody {


    private String content;


    private Long replyId;

    /**
     * Stable object storage identifier for media messages. Presigned URLs must
     * never be persisted in the message body.
     */
    private String objectName;

    private String mediaContentType;

    private Integer mediaWidth;

    private Integer mediaHeight;

    private Long mediaSize;

    private String thumbnailObjectName;

    private String originalName;

    /**
     * 红包ID（红包消息专用）
     */
    private String redPacketId;

    /**
     * 红包封面文案（红包消息专用）
     */
    private String redPacketWrapperText;


}
