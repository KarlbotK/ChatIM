package com.goat.userservice.utils;

import com.goat.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroupMemberCursorCodecTest {

    private final GroupMemberCursorCodec codec = new GroupMemberCursorCodec();

    @Test
    void roundTripsCursorForSameRequesterAndSession() {
        String encoded = codec.encode(101L, 9001L, 1, 1_760_000_000_000L, 202L);

        GroupMemberCursorCodec.GroupMemberCursor decoded = codec.decode(encoded, 101L, 9001L);

        assertEquals(1, decoded.role());
        assertEquals(1_760_000_000_000L, decoded.joinedTime());
        assertEquals(202L, decoded.memberId());
    }

    @Test
    void rejectsCursorForAnotherRequester() {
        String encoded = codec.encode(101L, 9001L, 2, 1_760_000_000_000L, 202L);

        assertThrows(BusinessException.class, () -> codec.decode(encoded, 303L, 9001L));
    }

    @Test
    void rejectsCursorForAnotherSession() {
        String encoded = codec.encode(101L, 9001L, 2, 1_760_000_000_000L, 202L);

        assertThrows(BusinessException.class, () -> codec.decode(encoded, 101L, 9002L));
    }

    @Test
    void rejectsTamperedCursor() {
        String encoded = codec.encode(101L, 9001L, 2, 1_760_000_000_000L, 202L);
        char replacement = encoded.charAt(0) == 'A' ? 'B' : 'A';
        String tampered = replacement + encoded.substring(1);

        assertThrows(BusinessException.class, () -> codec.decode(tampered, 101L, 9001L));
    }
}
