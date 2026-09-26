package com.goat.userservice.consumer;

import com.goat.userservice.service.UserSessionService;
import com.goat.userservice.service.NotificationService;
import com.goat.userservice.model.entity.UserSession;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.util.List;

class SessionVisibilityConsumerTest {

    @Test
    void revealsHiddenSessionAfterPersistedMessageEvent() {
        UserSessionService userSessionService = mock(UserSessionService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SessionVisibilityConsumer consumer = new SessionVisibilityConsumer(userSessionService, notificationService);
        UserSession hiddenMembership = new UserSession();
        hiddenMembership.setUserId(202L);
        hiddenMembership.setSessionId(9001L);
        hiddenMembership.setPinned(true);
        hiddenMembership.setMuted(true);
        when(userSessionService.revealHiddenSession(9001L)).thenReturn(List.of(hiddenMembership));

        consumer.revealSessionAfterNewMessage("{\"sessionId\":9001,\"senderId\":101}");

        verify(userSessionService).revealHiddenSession(9001L);
        verify(notificationService).pushSessionPreferenceUpdated(any(), any());
    }
}
