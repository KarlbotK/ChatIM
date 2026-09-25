package com.goat.userservice.utils;

import com.goat.common.common.ErrorCode;
import com.goat.common.constant.CommonConstant;
import com.goat.common.exception.BusinessException;
import com.goat.common.exception.ThrowUtils;
import com.goat.common.utils.AuthTokenUtil;
import com.goat.common.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthenticatedUserResolver {

    private final StringRedisTemplate stringRedisTemplate;

    public Long resolve(HttpServletRequest request) {
        String accessToken = AuthTokenUtil.extract(request.getHeader("Authorization"));
        if (accessToken == null) {
            accessToken = AuthTokenUtil.extract(request.getHeader("Access-Token"));
        }
        Claims claims = JwtUtil.parse(accessToken);
        ThrowUtils.throwIf(claims == null || StringUtils.isBlank(claims.getSubject()),
                ErrorCode.NOT_LOGIN_ERROR);
        String storedToken = stringRedisTemplate.opsForValue()
                .get(CommonConstant.ACCESS_TOKEN_PREFIX + claims.getSubject());
        ThrowUtils.throwIf(!accessToken.equals(storedToken), ErrorCode.NOT_LOGIN_ERROR);
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
    }
}
