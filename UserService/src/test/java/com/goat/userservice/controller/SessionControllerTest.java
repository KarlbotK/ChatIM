package com.goat.userservice.controller;

import com.goat.userservice.model.dto.request.UpdateSessionPreferenceRequest;
import com.goat.userservice.model.dto.response.SessionPreferenceResponse;
import com.goat.userservice.service.SessionService;
import com.goat.userservice.utils.AuthenticatedUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionControllerTest {

    private final SessionService sessionService = mock(SessionService.class);
    private final AuthenticatedUserResolver userResolver = mock(AuthenticatedUserResolver.class);
    private final HttpServletRequest httpRequest = mock(HttpServletRequest.class);
    private final SessionController controller = new SessionController(sessionService, userResolver);

    @Test
    void pinUsesAuthenticatedUser() {
        UpdateSessionPreferenceRequest request = new UpdateSessionPreferenceRequest();
        request.setEnabled(true);
        when(userResolver.resolve(httpRequest)).thenReturn(101L);
        when(sessionService.updatePinned(101L, 9001L, true)).thenReturn(
                SessionPreferenceResponse.builder().sessionId(9001L).pinned(true).build()
        );

        controller.updatePinned(9001L, request, httpRequest);

        verify(sessionService).updatePinned(101L, 9001L, true);
    }

    @Test
    void hideUsesAuthenticatedUser() {
        when(userResolver.resolve(httpRequest)).thenReturn(101L);
        when(sessionService.hideSession(101L, 9001L)).thenReturn(
                SessionPreferenceResponse.builder().sessionId(9001L).hidden(true).build()
        );

        controller.hideSession(9001L, httpRequest);

        verify(sessionService).hideSession(101L, 9001L);
    }
}
