package com.goat.userservice.utils;

import com.goat.common.common.ErrorCode;
import com.goat.common.constant.CommonConstant;
import com.goat.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Component
public class GroupMemberCursorCodec {

    private static final String VERSION = "v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    public String encode(
            Long requesterId,
            Long sessionId,
            int role,
            long joinedTime,
            long memberId) {
        String payload = String.join(":",
                VERSION,
                String.valueOf(requesterId),
                String.valueOf(sessionId),
                String.valueOf(role),
                String.valueOf(joinedTime),
                String.valueOf(memberId)
        );
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes)
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payloadBytes));
    }

    public GroupMemberCursor decode(String cursor, Long expectedRequesterId, Long expectedSessionId) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String[] cursorParts = cursor.split("\\.", -1);
            if (cursorParts.length != 2) {
                throw invalidCursor();
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(cursorParts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(cursorParts[1]);
            if (!MessageDigest.isEqual(signature, sign(payloadBytes))) {
                throw invalidCursor();
            }
            String[] payloadParts = new String(payloadBytes, StandardCharsets.UTF_8).split(":", -1);
            if (payloadParts.length != 6 || !VERSION.equals(payloadParts[0])) {
                throw invalidCursor();
            }
            Long requesterId = Long.valueOf(payloadParts[1]);
            Long sessionId = Long.valueOf(payloadParts[2]);
            if (!expectedRequesterId.equals(requesterId) || !expectedSessionId.equals(sessionId)) {
                throw invalidCursor();
            }
            int role = Integer.parseInt(payloadParts[3]);
            long joinedTime = Long.parseLong(payloadParts[4]);
            long memberId = Long.parseLong(payloadParts[5]);
            if (role < 0 || role > 2 || joinedTime < 0 || memberId <= 0) {
                throw invalidCursor();
            }
            return new GroupMemberCursor(role, joinedTime, memberId);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidCursor();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            byte[] secret = Base64.getDecoder().decode(CommonConstant.TOKEN_SECRET_KEY);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成群成员分页游标签名", exception);
        }
    }

    private BusinessException invalidCursor() {
        return new BusinessException(ErrorCode.SYNC_CURSOR_INVALID);
    }

    public record GroupMemberCursor(int role, long joinedTime, long memberId) {
    }
}
