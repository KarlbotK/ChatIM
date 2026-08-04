package com.goat.userservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.goat.common.constant.CommonConstant;
import com.goat.common.common.ErrorCode;
import com.goat.userservice.constants.UserConstant;
import com.goat.userservice.loadbalancer.NettyServiceLocator;
import com.goat.userservice.model.dto.UpdateAvatarRequest;
import com.goat.userservice.model.dto.UserLoginCodeRequest;
import com.goat.userservice.model.dto.UserLoginPasswordRequest;
import com.goat.userservice.model.dto.UserRegisterRequest;
import com.goat.userservice.model.entity.User;
import com.goat.common.exception.ThrowUtils;
import com.goat.userservice.mapper.UserMapper;
import com.goat.userservice.model.vo.LoginAndRegisterResponse;
import com.goat.userservice.model.vo.TokenResponse;
import com.goat.userservice.model.vo.UploadUrlResponse;
import com.goat.userservice.service.UserService;
import com.goat.userservice.utils.EmailUtil;
import com.goat.common.utils.JwtUtil;
import com.goat.userservice.utils.OssUtils;
import com.goat.userservice.utils.RandomCodeUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import io.jsonwebtoken.Claims;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;


import java.util.concurrent.TimeUnit;

/**
* @author KARLK
* @description 针对表【user(用户表)】的数据库操作Service实现
* @createDate 2026-07-08 16:41:10
*/
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService{

    @Autowired
    private EmailUtil emailUtil;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private NettyServiceLocator serviceInstanceUtil;

    @Override
    public void sendCaptcha(String targetEmail) {
        String existingCode = stringRedisTemplate.opsForValue().get(targetEmail);
        ThrowUtils.throwIf(StringUtils.isNotBlank(existingCode), ErrorCode.LOGIN_ERROR_CODE);

        String randomCode = RandomCodeUtil.getRandomCode();
        emailUtil.sendEmail(targetEmail, randomCode);
        stringRedisTemplate.opsForValue().set(targetEmail, randomCode, UserConstant.CAPTCHA_EXPIRE_TIME, TimeUnit.MINUTES);
    }

    @Override
    public LoginAndRegisterResponse register(UserRegisterRequest userRegisterRequest) {

        // 校验验证码
        String email = userRegisterRequest.getEmail();
        String code = userRegisterRequest.getCode();
        String redisCode = stringRedisTemplate.opsForValue().get(email);
        ThrowUtils.throwIf(StringUtils.isBlank(redisCode) || !redisCode.equals(code), ErrorCode.LOGIN_ERROR_CODE);

        // 校验用户是否存在
        User user = getUser(email);
        ThrowUtils.throwIf(user != null, ErrorCode.USER_ALREADY_EXISTS);

        //判断两次密码是否一致
        ThrowUtils.throwIf(!userRegisterRequest.getConfirmPassword().equals(userRegisterRequest.getPassword()), ErrorCode.LOGIN_ERROR);

        // 生成密码
        String password = userRegisterRequest.getPassword();
        String encryptedPassword = DigestUtils.md5DigestAsHex((UserConstant.PASSWORD_SALT + password).getBytes());

        LoginAndRegisterResponse loginAndRegisterResponse = new LoginAndRegisterResponse();

        // 使用 synchronized 关键字保证并发安全
        synchronized (email.intern()) {
            Snowflake snowflake = IdUtil.getSnowflake(UserConstant.WORKER_ID, UserConstant.DATA_CENTER_ID);
            User newUser = new User();
            newUser.setUserId(snowflake.nextId());
            newUser.setEmail(email);
            newUser.setPassword(encryptedPassword);
            newUser.setNickname(userRegisterRequest.getNickname());
            boolean saveUser = this.save(newUser);
            ThrowUtils.throwIf(!saveUser, ErrorCode.SYSTEM_ERROR);
            BeanUtil.copyProperties(getUser(email), loginAndRegisterResponse);
        }
        stringRedisTemplate.delete(email);
        return createJwt(loginAndRegisterResponse);
    }
    private User getUser(String email) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("email", email);
        return this.getOne(queryWrapper);
    }

    @Override
    public LoginAndRegisterResponse loginPassword(UserLoginPasswordRequest userLoginPasswordRequest) {
        String email = userLoginPasswordRequest.getEmail();
        String password = userLoginPasswordRequest.getPassword();

        User user = getUser(email);
        ThrowUtils.throwIf(user == null, ErrorCode.USER_NOT_EXISTS);

        String encryptedPassword = DigestUtils.md5DigestAsHex((UserConstant.PASSWORD_SALT + password).getBytes());
        ThrowUtils.throwIf(!encryptedPassword.equals(user.getPassword()), ErrorCode.LOGIN_ERROR);

        LoginAndRegisterResponse loginAndRegisterResponse = new LoginAndRegisterResponse();
        BeanUtil.copyProperties(user, loginAndRegisterResponse);

        return createJwt(loginAndRegisterResponse);
    }
    @Override
    public LoginAndRegisterResponse loginCode(UserLoginCodeRequest userLoginCodeRequest) {
        // 校验验证码
        String email = userLoginCodeRequest.getEmail();
        String code = userLoginCodeRequest.getCode();

        String redisCode = stringRedisTemplate.opsForValue().get(email);
        ThrowUtils.throwIf(StringUtils.isBlank(redisCode) || !redisCode.equals(code), ErrorCode.LOGIN_ERROR_CODE);

        // 删除 redis 保存的验证码
        stringRedisTemplate.delete(email);
        // 验证用户是否存在
        User user = getUser(email);
        ThrowUtils.throwIf(user == null, ErrorCode.USER_NOT_EXISTS);



        LoginAndRegisterResponse loginAndRegisterResponse = new LoginAndRegisterResponse();
        BeanUtil.copyProperties(user, loginAndRegisterResponse);

        return createJwt(loginAndRegisterResponse);
    }

    public LoginAndRegisterResponse createJwt(LoginAndRegisterResponse loginAndRegisterResponse) {
        String userId = loginAndRegisterResponse.getUserId().toString();
        String accessToken = JwtUtil.generate(userId, CommonConstant.ACCESS_TOKEN_EXPIRE_TIME, CommonConstant.ACCESS_TOKEN_UNIT);
        String refreshToken = JwtUtil.generate(userId, CommonConstant.REFRESH_TOKEN_EXPIRE_TIME, CommonConstant.REFRESH_TOKEN_UNIT);
        loginAndRegisterResponse.setAccessToken(accessToken);
        loginAndRegisterResponse.setRefreshToken(refreshToken);
        stringRedisTemplate.opsForValue().set(CommonConstant.ACCESS_TOKEN_PREFIX + userId, accessToken, CommonConstant.ACCESS_TOKEN_EXPIRE_TIME, CommonConstant.ACCESS_TOKEN_UNIT);
        stringRedisTemplate.opsForValue().set(CommonConstant.REFRESH_TOKEN_PREFIX + userId, refreshToken, CommonConstant.REFRESH_TOKEN_EXPIRE_TIME, CommonConstant.REFRESH_TOKEN_UNIT);
        String nettyUri = serviceInstanceUtil.getServiceInstance(loginAndRegisterResponse.getUserId().toString());
        loginAndRegisterResponse.setNettyUri(nettyUri);

        Long offlineTime=getAndClearOfflineTime(userId);
        loginAndRegisterResponse.setOfflineTime(offlineTime);
        return loginAndRegisterResponse;
    }

    private Long getAndClearOfflineTime(String userId){
        String key=CommonConstant.OFFLINE_KEY_REDIS+userId;
        String value = stringRedisTemplate.opsForValue().getAndDelete(key);
        if (StringUtils.isNotBlank(value)) {
            log.info("用户 {} 上线，离线时间: {}", userId, value);
            return Long.parseLong(value);
        }
        // 新用户或首次登录，没有离线记录
        log.debug("用户 {} 无离线时间记录", userId);
        return null;
    }

    @Override
    public boolean logout(String userId) {
        stringRedisTemplate.delete(CommonConstant.ACCESS_TOKEN_PREFIX + userId);
        stringRedisTemplate.delete(CommonConstant.REFRESH_TOKEN_PREFIX + userId);
        return true;
    }
    @Override
    public TokenResponse refreshToken(String refreshToken) {
        // 1. 解析传入的 Refresh Token
        Claims claims = JwtUtil.parse(refreshToken);
        ThrowUtils.throwIf(claims == null, ErrorCode.TOKEN_INVALID, "凭证已失效，请重新登录");


        // 2. 从载荷中安全获取 userId
        String userId = claims.getSubject();

        // 3. 校验 Redis，防止 Token 撤销攻击（实现单设备登录的关键）
        String redisRefreshToken = stringRedisTemplate.opsForValue().get(CommonConstant.REFRESH_TOKEN_PREFIX + userId);
        ThrowUtils.throwIf(!refreshToken.equals(redisRefreshToken), ErrorCode.TOKEN_INVALID, "凭证已过期或在其他地方登录");


        // 4. 生成新的一对 Token
        String newAccessToken = JwtUtil.generate(userId, CommonConstant.ACCESS_TOKEN_EXPIRE_TIME, CommonConstant.ACCESS_TOKEN_UNIT);
        String newRefreshToken = JwtUtil.generate(userId, CommonConstant.REFRESH_TOKEN_EXPIRE_TIME, CommonConstant.REFRESH_TOKEN_UNIT);

        // 5. 更新 Redis
        stringRedisTemplate.opsForValue().set(CommonConstant.ACCESS_TOKEN_PREFIX + userId, newAccessToken, CommonConstant.ACCESS_TOKEN_EXPIRE_TIME, CommonConstant.ACCESS_TOKEN_UNIT);
        stringRedisTemplate.opsForValue().set(CommonConstant.REFRESH_TOKEN_PREFIX + userId, newRefreshToken, CommonConstant.REFRESH_TOKEN_EXPIRE_TIME, CommonConstant.REFRESH_TOKEN_UNIT);
        return TokenResponse.builder().accessToken(newAccessToken).refreshToken(newRefreshToken).build();
    }

    @Override
    public String refreshUri(Long userId) {
        return serviceInstanceUtil.getServiceInstance(String.valueOf(userId));
    }

    @Resource
    private OssUtils ossUtils;

    @Override
    public UploadUrlResponse uploadUrl(String fileName) {
        UploadUrlResponse uploadUrlResponse = new UploadUrlResponse();
        uploadUrlResponse.setUploadUrl(ossUtils.uploadUrl(CommonConstant.BUCKET_NAME, fileName, CommonConstant.PICTURE_EXPIRE_TIME));
        uploadUrlResponse.setDownloadUrl(ossUtils.downUrl(CommonConstant.BUCKET_NAME, fileName));
        return uploadUrlResponse;
    }

    @Override
    public Boolean updateAvatar(UpdateAvatarRequest updateAvatarRequest) {
        User user = this.getById(updateAvatarRequest.getUserId());
        if (user == null) {
            return false;
        }
        user.setAvatar(updateAvatarRequest.getUri());
        return this.updateById(user);
    }
}
