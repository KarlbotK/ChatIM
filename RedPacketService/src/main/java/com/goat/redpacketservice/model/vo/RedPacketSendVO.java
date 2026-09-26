package com.goat.redpacketservice.model.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 红包发送结果 VO
 *
 * @author goat
 */
@Data
public class RedPacketSendVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 红包 ID
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long redPacketId;

    /**
     * 消息 ID
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long messageId;
}
