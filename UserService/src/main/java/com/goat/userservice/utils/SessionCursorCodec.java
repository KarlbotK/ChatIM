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
public class SessionCursorCodec {

    private static final String VERSION = "v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    public String encode(Long userId, boolean pinned, long lastMessageTime, long sessionId) {
        String payload = String.join(":",
                VERSION,
                String.valueOf(userId),
                pinned ? "1" : "0",
                String.valueOf(lastMessageTime),
                String.valueOf(sessionId)
        );
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes)
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payloadBytes));
    }

    public SessionCursor decode(String cursor, Long expectedUserId) {
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
            if (payloadParts.length != 5 || !VERSION.equals(payloadParts[0])) {
                throw invalidCursor();
            }
            Long userId = Long.valueOf(payloadParts[1]);
            if (!expectedUserId.equals(userId)) {
                throw invalidCursor();
            }
            boolean pinned = switch (payloadParts[2]) {
                case "1" -> true;
                case "0" -> false;
                default -> throw invalidCursor();
            };
            long lastMessageTime = Long.parseLong(payloadParts[3]);
            long sessionId = Long.parseLong(payloadParts[4]);
            if (lastMessageTime < 0 || sessionId < 0) {
                throw invalidCursor();
            }
            return new SessionCursor(pinned, lastMessageTime, sessionId);
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
            throw new IllegalStateException("无法生成会话分页游标签名", exception);
        }
    }

    private BusinessException invalidCursor() {
        return new BusinessException(ErrorCode.SYNC_CURSOR_INVALID);
    }

    public record SessionCursor(boolean pinned, long lastMessageTime, long sessionId) {
    }
}
