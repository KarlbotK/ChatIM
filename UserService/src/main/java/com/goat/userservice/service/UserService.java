package com.goat.userservice.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.goat.userservice.model.dto.request.UpdateAvatarRequest;
import com.goat.userservice.model.dto.request.UserLoginCodeRequest;
import com.goat.userservice.model.dto.request.UserLoginPasswordRequest;
import com.goat.userservice.model.dto.request.UserRegisterRequest;
import com.goat.userservice.model.entity.User;
import com.goat.userservice.model.vo.LoginAndRegisterResponse;
import com.goat.userservice.model.vo.TokenResponse;
import com.goat.userservice.model.vo.UploadUrlResponse;

/**
* @author KARLK
* @description 针对表【user(用户表)】的数据库操作Service
* @createDate 2026-07-08 16:41:10
*/
public interface UserService extends IService<User> {


    void sendCaptcha(String targetEmail);
    LoginAndRegisterResponse register(UserRegisterRequest userRegisterRequest);
    LoginAndRegisterResponse loginPassword(UserLoginPasswordRequest userLoginPasswordRequest);
    LoginAndRegisterResponse loginCode(UserLoginCodeRequest userLoginCodeRequest);
    boolean logout(String userId);
    String refreshUri(Long userId);
    TokenResponse refreshToken(String refreshToken);
    UploadUrlResponse uploadUrl(String fileName) ;
    Boolean updateAvatar(UpdateAvatarRequest updateAvatarRequest);

}
