package com.goat.offlinedataservice.utils;

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
public class OfflineCursorCodec {

    private static final String VERSION = "v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    public String encode(Long userId, long createdTime, long messageId) {
        String payload = String.join(":",
                VERSION,
                String.valueOf(userId),
                String.valueOf(createdTime),
                String.valueOf(messageId)
        );
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes)
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payloadBytes));
    }

    public OfflineCursor decode(String cursor, Long expectedUserId) {
        if (cursor == null || cursor.isBlank()) {
            return new OfflineCursor(0L, 0L);
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
            if (payloadParts.length != 4 || !VERSION.equals(payloadParts[0])) {
                throw invalidCursor();
            }
            Long userId = Long.valueOf(payloadParts[1]);
            if (!expectedUserId.equals(userId)) {
                throw invalidCursor();
            }
            long createdTime = Long.parseLong(payloadParts[2]);
            long messageId = Long.parseLong(payloadParts[3]);
            if (createdTime < 0 || messageId < 0) {
                throw invalidCursor();
            }
            return new OfflineCursor(createdTime, messageId);
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
            throw new IllegalStateException("无法生成离线同步游标签名", exception);
        }
    }

    private BusinessException invalidCursor() {
        return new BusinessException(ErrorCode.SYNC_CURSOR_INVALID);
    }

    public record OfflineCursor(long createdTime, long messageId) {
    }
}
