package com.goat.realtimeservice.utils;

import com.goat.common.constant.CommonConstant;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class OnlineStatusUtil {

    private OnlineStatusUtil() {
    }

    public static boolean isUserOffline(StringRedisTemplate redisTemplate, Long userId) {
        String key = CommonConstant.OFFLINE_KEY_REDIS + userId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
