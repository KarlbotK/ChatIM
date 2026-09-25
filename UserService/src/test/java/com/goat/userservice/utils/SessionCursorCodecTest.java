package com.goat.userservice.utils;

import com.goat.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCursorCodecTest {

    private final SessionCursorCodec codec = new SessionCursorCodec();

    @Test
    void roundTripsCursorForSameUser() {
        String encoded = codec.encode(101L, true, 1_760_000_000_000L, 9001L);

        SessionCursorCodec.SessionCursor decoded = codec.decode(encoded, 101L);

        assertTrue(decoded.pinned());
        assertEquals(1_760_000_000_000L, decoded.lastMessageTime());
        assertEquals(9001L, decoded.sessionId());
    }

    @Test
    void rejectsCursorOwnedByAnotherUser() {
        String encoded = codec.encode(101L, false, 1_760_000_000_000L, 9001L);

        assertThrows(BusinessException.class, () -> codec.decode(encoded, 202L));
    }

    @Test
    void rejectsTamperedCursor() {
        String encoded = codec.encode(101L, false, 1_760_000_000_000L, 9001L);
        char replacement = encoded.charAt(0) == 'A' ? 'B' : 'A';
        String tampered = replacement + encoded.substring(1);

        assertThrows(BusinessException.class, () -> codec.decode(tampered, 101L));
    }
}
