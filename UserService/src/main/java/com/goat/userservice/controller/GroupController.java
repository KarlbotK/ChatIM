package com.goat.userservice.controller;

import com.goat.common.common.BaseResponse;
import com.goat.common.common.ErrorCode;
import com.goat.common.common.ResultUtils;
import com.goat.common.exception.BusinessException;
import com.goat.userservice.model.dto.request.InviteGroupRequest;
import com.goat.userservice.model.dto.request.GroupMemberTargetRequest;
import com.goat.userservice.model.dto.request.UpdateGroupAvatarRequest;
import com.goat.userservice.model.dto.request.UpdateGroupProfileRequest;
import com.goat.userservice.model.dto.response.GroupAvatarUploadResponse;
import com.goat.userservice.model.dto.response.GroupManagementResponse;
import com.goat.userservice.model.dto.response.GroupProfileResponse;
import com.goat.userservice.model.dto.response.CreateGroupResponse;
import com.goat.userservice.model.dto.request.CreateGroupRequest;
import com.goat.userservice.model.dto.response.InviteGroupResponse;
import com.goat.userservice.model.dto.response.GroupMemberListResponse;
import com.goat.userservice.service.GroupService;
import com.goat.userservice.service.SessionService;
import com.goat.userservice.utils.AuthenticatedUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("api/group")
public class GroupController {
    private final SessionService sessionService;
    private final GroupService groupService;
    private final AuthenticatedUserResolver authenticatedUserResolver;

    public GroupController(SessionService sessionService,
                           GroupService groupService,
                           AuthenticatedUserResolver authenticatedUserResolver) {

        this.sessionService = sessionService;
        this.groupService=groupService;
        this.authenticatedUserResolver = authenticatedUserResolver;
    }


    @PostMapping
    public BaseResponse<?> createGroup(
            @Valid @RequestBody CreateGroupRequest request,
            HttpServletRequest httpRequest) {
        try {
            request.setCreatorId(authenticatedUserResolver.resolve(httpRequest));
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
    public BaseResponse<?> inviteGroup(
            @Valid @RequestBody InviteGroupRequest request,
            HttpServletRequest httpRequest) {
        try {
            request.setInviterId(authenticatedUserResolver.resolve(httpRequest));
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

    @GetMapping("/{sessionId}/members")
    public BaseResponse<GroupMemberListResponse> listMembers(
            @PathVariable Long sessionId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return ResultUtils.success(groupService.listMembers(
                authenticatedUserResolver.resolve(request),
                sessionId,
                cursor,
                limit
        ));
    }

    @PatchMapping("/{sessionId}")
    public BaseResponse<GroupProfileResponse> updateProfile(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateGroupProfileRequest profileRequest,
            HttpServletRequest request) {
        return ResultUtils.success(groupService.updateProfile(
                authenticatedUserResolver.resolve(request),
                sessionId,
                profileRequest
        ));
    }

    @GetMapping("/{sessionId}/avatar/upload-url")
    public BaseResponse<GroupAvatarUploadResponse> createAvatarUpload(
            @PathVariable Long sessionId,
            @RequestParam String fileName,
            HttpServletRequest request) {
        return ResultUtils.success(groupService.createAvatarUpload(
                authenticatedUserResolver.resolve(request),
                sessionId,
                fileName
        ));
    }

    @PutMapping("/{sessionId}/avatar")
    public BaseResponse<GroupProfileResponse> updateAvatar(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateGroupAvatarRequest avatarRequest,
            HttpServletRequest request) {
        return ResultUtils.success(groupService.updateAvatar(
                authenticatedUserResolver.resolve(request),
                sessionId,
                avatarRequest
        ));
    }

    @PostMapping("/{sessionId}/leave")
    public BaseResponse<GroupManagementResponse> leaveGroup(
            @PathVariable Long sessionId,
            HttpServletRequest request) {
        return ResultUtils.success(groupService.leaveGroup(
                authenticatedUserResolver.resolve(request),
                sessionId
        ));
    }

    @DeleteMapping("/{sessionId}/members/{userId}")
    public BaseResponse<GroupManagementResponse> removeMember(
            @PathVariable Long sessionId,
            @PathVariable Long userId,
            HttpServletRequest request) {
        return ResultUtils.success(groupService.removeMember(
                authenticatedUserResolver.resolve(request),
                sessionId,
                userId
        ));
    }

    @PostMapping("/{sessionId}/admins")
    public BaseResponse<GroupManagementResponse> addAdministrator(
            @PathVariable Long sessionId,
            @Valid @RequestBody GroupMemberTargetRequest targetRequest,
            HttpServletRequest request) {
        return ResultUtils.success(groupService.addAdministrator(
                authenticatedUserResolver.resolve(request),
                sessionId,
                targetRequest
        ));
    }

    @DeleteMapping("/{sessionId}/admins/{userId}")
    public BaseResponse<GroupManagementResponse> removeAdministrator(
            @PathVariable Long sessionId,
            @PathVariable Long userId,
            HttpServletRequest request) {
        return ResultUtils.success(groupService.removeAdministrator(
                authenticatedUserResolver.resolve(request),
                sessionId,
                userId
        ));
    }

    @PostMapping("/{sessionId}/transfer-owner")
    public BaseResponse<GroupManagementResponse> transferOwner(
            @PathVariable Long sessionId,
            @Valid @RequestBody GroupMemberTargetRequest targetRequest,
            HttpServletRequest request) {
        return ResultUtils.success(groupService.transferOwner(
                authenticatedUserResolver.resolve(request),
                sessionId,
                targetRequest
        ));
    }

    @DeleteMapping("/{sessionId}")
    public BaseResponse<GroupManagementResponse> dissolveGroup(
            @PathVariable Long sessionId,
            HttpServletRequest request) {
        return ResultUtils.success(groupService.dissolveGroup(
                authenticatedUserResolver.resolve(request),
                sessionId
        ));
    }
}
