package com.goat.offlinedataservice.client;


import com.goat.common.model.dto.SessionReadPosition;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "UserService")
public interface UserServiceClient {

    @GetMapping("/api/user/get/sessions")
    List<Long> getSessionIdsByUserId(@RequestParam("userId")  Long userId);

    @GetMapping("/internal/session/read-positions")
    List<SessionReadPosition> getReadPositions(@RequestParam("userId") Long userId);

}
