package com.goat.redpacketservice.controller;

import com.goat.common.common.BaseResponse;
import com.goat.common.common.ResultUtils;
import com.goat.redpacketservice.annotation.PreventDuplicateSubmit;
import com.goat.redpacketservice.model.dto.RedPacketSendRequest;
import com.goat.redpacketservice.model.vo.RedPacketSendVO;
import com.goat.redpacketservice.service.RedPacketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 红包接口控制器
 *
 * @author goat
 */
@RestController
@RequestMapping("/api/chat/redPacket")
@Slf4j
public class RedPacketController {

    @Autowired
    private RedPacketService redPacketService;

    /**
     * 发送红包
     * @param request 红包发送请求
     * @return 红包发送结果（包含红包ID和消息ID）
     */
    @PostMapping("/send")
    @PreventDuplicateSubmit(expireSeconds = 3)
    public BaseResponse<RedPacketSendVO> sendRedPacket(@RequestBody RedPacketSendRequest request) {
        RedPacketSendVO result = redPacketService.sendRedPacket(request);
        log.info("红包发送成功，红包ID: {}, 消息ID: {}", result.getRedPacketId(), result.getMessageId());
        return ResultUtils.success(result);
    }
}