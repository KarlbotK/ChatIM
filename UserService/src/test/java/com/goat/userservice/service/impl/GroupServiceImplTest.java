package com.goat.userservice.service.impl;

import com.goat.common.constant.SessionTypeConstant;
import com.goat.common.exception.BusinessException;
import com.goat.userservice.mapper.FriendMapper;
import com.goat.userservice.mapper.SessionMapper;
import com.goat.userservice.mapper.UserMapper;
import com.goat.userservice.mapper.UserSessionMapper;
import com.goat.userservice.model.dto.request.UpdateGroupProfileRequest;
import com.goat.userservice.model.dto.request.GroupMemberTargetRequest;
import com.goat.userservice.model.dto.response.GroupManagementResponse;
import com.goat.userservice.model.dto.response.GroupProfileResponse;
import com.goat.userservice.model.entity.Session;
import com.goat.userservice.model.entity.UserSession;
import com.goat.userservice.service.NotificationService;
import com.goat.userservice.service.UserSessionService;
import com.goat.userservice.utils.GroupMemberCursorCodec;
import com.goat.userservice.utils.OssUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class GroupServiceImplTest {

    private final SessionMapper sessionMapper = mock(SessionMapper.class);
    private final UserSessionMapper userSessionMapper = mock(UserSessionMapper.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final OssUtils ossUtils = mock(OssUtils.class);
    private final UserSessionService userSessionService = mock(UserSessionService.class);
    private final GroupServiceImpl service = new GroupServiceImpl(
            sessionMapper,
            userSessionMapper,
            mock(FriendMapper.class),
            notificationService,
            userSessionService,
            ossUtils,
            mock(UserMapper.class),
            new GroupMemberCursorCodec()
    );

    @Test
    void administratorCanUpdateAnnouncement() {
        Session group = activeGroup();
        UserSession administrator = activeMembership(1);
        when(sessionMapper.selectOne(any())).thenReturn(group);
        when(userSessionMapper.selectOne(any())).thenReturn(administrator);
        when(userSessionMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(sessionMapper.updateById(any(Session.class))).thenReturn(1);
        UpdateGroupProfileRequest request = new UpdateGroupProfileRequest();
        request.setAnnouncement("  周六九点集合  ");

        GroupProfileResponse response = service.updateProfile(101L, 9001L, request);

        assertEquals("周六九点集合", response.getAnnouncement());
        ArgumentCaptor<Session> saved = ArgumentCaptor.forClass(Session.class);
        verify(sessionMapper).updateById(saved.capture());
        assertEquals("周六九点集合", saved.getValue().getAnnouncement());
    }

    @Test
    void ordinaryMemberCannotRenameGroup() {
        when(sessionMapper.selectOne(any())).thenReturn(activeGroup());
        when(userSessionMapper.selectOne(any())).thenReturn(activeMembership(2));
        UpdateGroupProfileRequest request = new UpdateGroupProfileRequest();
        request.setName("不能修改的名称");

        assertThrows(BusinessException.class, () -> service.updateProfile(101L, 9001L, request));
    }

    @Test
    void ordinaryMemberCannotRequestAvatarUpload() {
        when(sessionMapper.selectOne(any())).thenReturn(activeGroup());
        when(userSessionMapper.selectOne(any())).thenReturn(activeMembership(2));

        assertThrows(BusinessException.class,
                () -> service.createAvatarUpload(101L, 9001L, "avatar.png"));
        verifyNoInteractions(ossUtils);
    }

    @Test
    void ordinaryMemberCanLeaveGroup() {
        UserSession member = activeMembership(2);
        UserSession owner = membership(202L, 0);
        when(sessionMapper.selectOne(any())).thenReturn(activeGroup());
        when(userSessionMapper.selectOne(any())).thenReturn(member);
        when(userSessionMapper.selectList(any())).thenReturn(List.of(member, owner));
        when(userSessionMapper.update(any(), any())).thenReturn(1);
        when(userSessionService.getGroupMemberCount(9001L)).thenReturn(1);

        GroupManagementResponse response = service.leaveGroup(101L, 9001L);

        assertEquals("left", response.getAction());
        assertEquals(101L, response.getAffectedUserId());
        assertEquals(1, response.getMemberCount());
        verify(notificationService).pushGroupManagementUpdated(101L, response);
        verify(notificationService).pushGroupManagementUpdated(202L, response);
    }

    @Test
    void ownerMustTransferBeforeLeaving() {
        when(sessionMapper.selectOne(any())).thenReturn(activeGroup());
        when(userSessionMapper.selectOne(any())).thenReturn(activeMembership(0));

        assertThrows(BusinessException.class, () -> service.leaveGroup(101L, 9001L));
        verifyNoInteractions(notificationService);
    }

    @Test
    void administratorCannotRemoveAnotherAdministrator() {
        when(sessionMapper.selectOne(any())).thenReturn(activeGroup());
        when(userSessionMapper.selectOne(any()))
                .thenReturn(activeMembership(1), membership(202L, 1));

        assertThrows(BusinessException.class, () -> service.removeMember(101L, 9001L, 202L));
        verifyNoInteractions(notificationService);
    }

    @Test
    void ownerCanTransferOwnership() {
        UserSession owner = activeMembership(0);
        UserSession member = membership(202L, 2);
        when(sessionMapper.selectOne(any())).thenReturn(activeGroup());
        when(userSessionMapper.selectOne(any())).thenReturn(owner, member);
        when(userSessionMapper.update(any(), any())).thenReturn(1);
        when(userSessionMapper.selectList(any())).thenReturn(List.of(owner, member));
        when(userSessionService.getGroupMemberCount(9001L)).thenReturn(2);
        GroupMemberTargetRequest request = new GroupMemberTargetRequest();
        request.setUserId(202L);

        GroupManagementResponse response = service.transferOwner(101L, 9001L, request);

        assertEquals("owner_transferred", response.getAction());
        assertEquals(2, response.getActorUserRole());
        assertEquals(0, response.getAffectedUserRole());
        verify(userSessionMapper, times(2)).update(any(), any());
    }

    @Test
    void ownerCanDissolveGroup() {
        UserSession owner = activeMembership(0);
        when(sessionMapper.selectOne(any())).thenReturn(activeGroup());
        when(userSessionMapper.selectOne(any())).thenReturn(owner);
        when(userSessionMapper.selectList(any())).thenReturn(List.of(owner));
        when(sessionMapper.updateById(any(Session.class))).thenReturn(1);
        when(userSessionMapper.update(any(), any())).thenReturn(1);

        GroupManagementResponse response = service.dissolveGroup(101L, 9001L);

        assertEquals("dissolved", response.getAction());
        assertEquals(true, response.isDissolved());
        verify(notificationService).pushGroupManagementUpdated(101L, response);
    }

    private Session activeGroup() {
        Session session = new Session();
        session.setSessionId(9001L);
        session.setName("原群名");
        session.setType(SessionTypeConstant.GROUP_TYPE);
        session.setStatus(0);
        return session;
    }

    private UserSession activeMembership(int role) {
        return membership(101L, role);
    }

    private UserSession membership(Long userId, int role) {
        UserSession membership = new UserSession();
        membership.setUserId(userId);
        membership.setSessionId(9001L);
        membership.setRole(role);
        membership.setStatus(0);
        return membership;
    }
}
