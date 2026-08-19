package com.goat.userservice.controller;

import com.goat.common.common.BaseResponse;
import com.goat.common.common.ErrorCode;
import com.goat.common.common.ResultUtils;
import com.goat.common.exception.BusinessException;
import com.goat.userservice.model.dto.request.InviteGroupRequest;
import com.goat.userservice.model.dto.response.CreateGroupResponse;
import com.goat.userservice.model.dto.request.CreateGroupRequest;
import com.goat.userservice.model.dto.response.InviteGroupResponse;
import com.goat.userservice.service.GroupService;
import com.goat.userservice.service.SessionService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("api/group")
public class GroupController {
    private final SessionService sessionService;
    private final GroupService groupService;

    public GroupController(SessionService sessionService,
                           GroupService groupService) {

        this.sessionService = sessionService;
        this.groupService=groupService;
    }


    @PostMapping
    public BaseResponse<?> createGroup(@Valid @RequestBody CreateGroupRequest request) {
        try {
            CreateGroupResponse response = sessionService.createGroup(request);
            return ResultUtils.success(response);
        } catch (BusinessException e) {
            log.error("创建群聊失败，原因：{}", e.getMessage());
            return ResultUtils.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("创建群聊失败，原因：{}", e.getMessage(), e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR);
        }
    }

    // GroupController.java
    @PostMapping("/invite")
    public BaseResponse<?> inviteGroup(@Valid @RequestBody InviteGroupRequest request) {
        try {
            InviteGroupResponse response = groupService.inviteGroup(request);
            return ResultUtils.success(response);
        } catch (BusinessException e) {
            log.error("群聊邀请失败，原因：{}", e.getMessage());
            return ResultUtils.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("群聊邀请失败，原因：{}", e.getMessage(), e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR);
        }
    }
}
