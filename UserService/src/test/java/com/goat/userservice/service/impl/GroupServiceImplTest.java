package com.goat.userservice.service.impl;

import com.goat.common.constant.SessionTypeConstant;
import com.goat.common.exception.BusinessException;
import com.goat.userservice.mapper.FriendMapper;
import com.goat.userservice.mapper.SessionMapper;
import com.goat.userservice.mapper.UserMapper;
import com.goat.userservice.mapper.UserSessionMapper;
import com.goat.userservice.model.dto.request.UpdateGroupProfileRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GroupServiceImplTest {

    private final SessionMapper sessionMapper = mock(SessionMapper.class);
    private final UserSessionMapper userSessionMapper = mock(UserSessionMapper.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final OssUtils ossUtils = mock(OssUtils.class);
    private final GroupServiceImpl service = new GroupServiceImpl(
            sessionMapper,
            userSessionMapper,
            mock(FriendMapper.class),
            notificationService,
            mock(UserSessionService.class),
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

    private Session activeGroup() {
        Session session = new Session();
        session.setSessionId(9001L);
        session.setName("原群名");
        session.setType(SessionTypeConstant.GROUP_TYPE);
        session.setStatus(0);
        return session;
    }

    private UserSession activeMembership(int role) {
        UserSession membership = new UserSession();
        membership.setUserId(101L);
        membership.setSessionId(9001L);
        membership.setRole(role);
        membership.setStatus(0);
        return membership;
    }
}
