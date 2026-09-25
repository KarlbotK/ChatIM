package com.goat.realtimeservice.client;


import com.goat.common.common.BaseResponse;
import com.goat.common.model.dto.validation.GroupMembershipResponse;
import com.goat.common.model.dto.validation.MessageValidateResponse;
import com.goat.common.model.dto.validation.SingleMessageValidateRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;


import java.util.List;

@FeignClient(name = "UserService")
public interface UserServiceClient {

    @GetMapping("/api/user/get/receivers")
    List<Long> getUserIdBySessionId(@RequestParam("sessionId")  Long sessionId);

    @PostMapping("/api/internal/validation/single-message")
    BaseResponse<MessageValidateResponse> validateSingleMessage(
            @RequestBody SingleMessageValidateRequest request);

    @GetMapping("/api/internal/group/isMember")
    BaseResponse<GroupMembershipResponse> checkGroupMembership(
            @RequestParam("userId") Long userId,
            @RequestParam("sessionId") Long sessionId);

    @GetMapping("/api/internal/session/type")
    BaseResponse<Integer> getSessionType(@RequestParam("sessionId") Long sessionId);

}
