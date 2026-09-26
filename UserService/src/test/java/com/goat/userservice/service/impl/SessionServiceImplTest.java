package com.goat.userservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.goat.common.exception.BusinessException;
import com.goat.userservice.client.OfflineMessageClient;
import com.goat.userservice.mapper.FriendMapper;
import com.goat.userservice.mapper.SessionMapper;
import com.goat.userservice.mapper.UserSessionMapper;
import com.goat.userservice.model.dto.response.SessionPreferenceResponse;
import com.goat.userservice.model.entity.Session;
import com.goat.userservice.model.entity.UserSession;
import com.goat.userservice.service.NotificationService;
import com.goat.userservice.utils.OssUtils;
import com.goat.userservice.utils.SessionCursorCodec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionServiceImplTest {

    private final SessionMapper sessionMapper = mock(SessionMapper.class);
    private final UserSessionMapper userSessionMapper = mock(UserSessionMapper.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final SessionServiceImpl service = new SessionServiceImpl(
            sessionMapper,
            mock(UserServiceImpl.class),
            mock(FriendMapper.class),
            userSessionMapper,
            notificationService,
            mock(OssUtils.class),
            mock(OfflineMessageClient.class),
            new SessionCursorCodec()
    );

    @Test
    void pinsAnActiveSession() {
        UserSession membership = membership(false, true, false);
        when(userSessionMapper.selectOne(any())).thenReturn(membership);
        when(sessionMapper.selectOne(any())).thenReturn(activeSession());
        when(userSessionMapper.update(isNull(), ArgumentMatchers.<Wrapper<UserSession>>any())).thenReturn(1);

        SessionPreferenceResponse response = service.updatePinned(101L, 9001L, true);

        assertTrue(response.isPinned());
        assertTrue(response.isMuted());
        assertFalse(response.isHidden());
        verify(notificationService).pushSessionPreferenceUpdated(101L, response);
    }

    @Test
    void updatesMuteWithoutChangingPinState() {
        UserSession membership = membership(true, false, false);
        when(userSessionMapper.selectOne(any())).thenReturn(membership);
        when(sessionMapper.selectOne(any())).thenReturn(activeSession());
        when(userSessionMapper.update(isNull(), ArgumentMatchers.<Wrapper<UserSession>>any())).thenReturn(1);

        SessionPreferenceResponse response = service.updateMuted(101L, 9001L, true);

        assertTrue(response.isPinned());
        assertTrue(response.isMuted());
        verify(notificationService).pushSessionPreferenceUpdated(101L, response);
    }

    @Test
    void hidesSessionWithoutDeletingMembership() {
        UserSession membership = membership(true, true, false);
        when(userSessionMapper.selectOne(any())).thenReturn(membership);
        when(sessionMapper.selectOne(any())).thenReturn(activeSession());
        when(userSessionMapper.update(isNull(), ArgumentMatchers.<Wrapper<UserSession>>any())).thenReturn(1);

        SessionPreferenceResponse response = service.hideSession(101L, 9001L);

        assertTrue(response.isHidden());
        assertTrue(response.isPinned());
        assertTrue(response.isMuted());
        verify(notificationService).pushSessionPreferenceUpdated(101L, response);
    }

    @Test
    void rejectsPreferenceChangesOutsideTheSession() {
        when(userSessionMapper.selectOne(any())).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.updatePinned(101L, 9001L, true));

        verify(userSessionMapper, never()).update(isNull(), ArgumentMatchers.<Wrapper<UserSession>>any());
        verify(notificationService, never()).pushSessionPreferenceUpdated(any(), any());
    }

    private UserSession membership(boolean pinned, boolean muted, boolean hidden) {
        UserSession membership = new UserSession();
        membership.setUserId(101L);
        membership.setSessionId(9001L);
        membership.setStatus(0);
        membership.setPinned(pinned);
        membership.setMuted(muted);
        membership.setHidden(hidden);
        return membership;
    }

    private Session activeSession() {
        Session session = new Session();
        session.setSessionId(9001L);
        session.setStatus(0);
        return session;
    }
}
