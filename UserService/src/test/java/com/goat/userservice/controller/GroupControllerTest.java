package com.goat.userservice.controller;

import com.goat.userservice.model.dto.request.CreateGroupRequest;
import com.goat.userservice.model.dto.request.InviteGroupRequest;
import com.goat.userservice.model.dto.response.CreateGroupResponse;
import com.goat.userservice.model.dto.response.InviteGroupResponse;
import com.goat.userservice.model.dto.response.GroupManagementResponse;
import com.goat.userservice.service.GroupService;
import com.goat.userservice.service.SessionService;
import com.goat.userservice.utils.AuthenticatedUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupControllerTest {

    private final SessionService sessionService = mock(SessionService.class);
    private final GroupService groupService = mock(GroupService.class);
    private final AuthenticatedUserResolver userResolver = mock(AuthenticatedUserResolver.class);
    private final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    private final GroupController controller = new GroupController(sessionService, groupService, userResolver);

    @Test
    void createGroupUsesAuthenticatedUserInsteadOfBodyCreator() {
        CreateGroupRequest request = new CreateGroupRequest();
        request.setCreatorId(999L);
        request.setMemberIds(List.of(2L));
        when(userResolver.resolve(httpRequest)).thenReturn(101L);
        when(sessionService.createGroup(request)).thenReturn(new CreateGroupResponse());

        controller.createGroup(request, httpRequest);

        assertEquals(101L, request.getCreatorId());
        verify(sessionService).createGroup(request);
    }

    @Test
    void inviteGroupUsesAuthenticatedUserInsteadOfBodyInviter() {
        InviteGroupRequest request = new InviteGroupRequest();
        request.setSessionId(9001L);
        request.setInviterId(999L);
        request.setInviteeIds(List.of(2L));
        when(userResolver.resolve(httpRequest)).thenReturn(101L);
        when(groupService.inviteGroup(request)).thenReturn(new InviteGroupResponse());

        controller.inviteGroup(request, httpRequest);

        assertEquals(101L, request.getInviterId());
        verify(groupService).inviteGroup(request);
    }

    @Test
    void leaveGroupUsesAuthenticatedUser() {
        GroupManagementResponse response = GroupManagementResponse.builder().action("left").build();
        when(userResolver.resolve(httpRequest)).thenReturn(101L);
        when(groupService.leaveGroup(101L, 9001L)).thenReturn(response);

        controller.leaveGroup(9001L, httpRequest);

        verify(groupService).leaveGroup(101L, 9001L);
    }
}
