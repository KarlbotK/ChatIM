package com.goat.common.utils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class AuthTokenUtil {

    private static final String BEARER_PREFIX = "Bearer ";

    private AuthTokenUtil() {
    }

    public static String extract(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String value = headerValue.trim();
        if (value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            value = value.substring(BEARER_PREFIX.length()).trim();
        }
        return value.isBlank() ? null : value;
    }

    public static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String webSocketTargetFingerprint(String target) {
        String value = target.contains("://") ? target : "ws://" + target;
        URI uri = URI.create(value);
        String authority = uri.getRawAuthority();
        String path = uri.getPath();
        if (authority == null || authority.isBlank()) {
            throw new IllegalArgumentException("WebSocket target has no authority");
        }
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return fingerprint(authority.toLowerCase(Locale.ROOT) + path);
    }
}
