package com.goat.userservice.controller;

import com.goat.userservice.model.dto.request.UpdateAvatarRequest;
import com.goat.userservice.model.vo.TokenResponse;
import com.goat.userservice.model.vo.UploadUrlResponse;
import com.goat.userservice.model.vo.WebSocketTicketResponse;
import io.jsonwebtoken.Claims;
import com.goat.common.common.BaseResponse;
import com.goat.common.common.ErrorCode;
import com.goat.common.common.ResultUtils;
import com.goat.userservice.constants.UserConstant;
import com.goat.common.exception.ThrowUtils;
import com.goat.userservice.model.dto.request.UserLoginCodeRequest;
import com.goat.userservice.model.dto.request.UserLoginPasswordRequest;
import com.goat.userservice.model.dto.request.UserRegisterRequest;
import com.goat.userservice.model.vo.LoginAndRegisterResponse;
import com.goat.userservice.service.UserService;
import com.goat.common.utils.JwtUtil;
import com.goat.common.utils.AuthTokenUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@Validated
public class UserController {
    @Autowired
    private UserService userService;


    @GetMapping("/sendCaptcha")
    public BaseResponse<String> sendCaptcha(@NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确")@RequestParam String targetEmail) {
        userService.sendCaptcha(targetEmail);
        return ResultUtils.success(UserConstant.SEND_EMAIL_SUCCESS);
    }

    @PostMapping("/register")
    public BaseResponse<LoginAndRegisterResponse> register(@Valid @RequestBody UserRegisterRequest userRegisterRequest) {
        return ResultUtils.success(userService.register(userRegisterRequest));
    }

    @PostMapping("/login/password")
    public BaseResponse<LoginAndRegisterResponse> loginPassword(@Valid @RequestBody UserLoginPasswordRequest userLoginPasswordRequest) {
        return ResultUtils.success(userService.loginPassword(userLoginPasswordRequest));
    }

    @PostMapping("/login/code")
    public BaseResponse<LoginAndRegisterResponse> loginCode(@Valid @RequestBody UserLoginCodeRequest userLoginCodeRequest) {
        return ResultUtils.success(userService.loginCode(userLoginCodeRequest));
    }

    @GetMapping("/logout")
    public BaseResponse<Boolean> logout(HttpServletRequest request) {
        String accessToken = resolveAccessToken(request);
        Claims claims = JwtUtil.parse(accessToken);
        ThrowUtils.throwIf(claims == null, ErrorCode.NOT_LOGIN_ERROR);
        return ResultUtils.success(userService.logout(claims.getSubject()));
    }

    @PostMapping("/refresh")
    public BaseResponse<TokenResponse> refreshToken(HttpServletRequest request) {
        String refreshToken = request.getHeader("Refresh-Token");
        ThrowUtils.throwIf(StringUtils.isBlank(refreshToken), ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(userService.refreshToken(refreshToken));
    }

    @PostMapping("/ws-ticket")
    public BaseResponse<WebSocketTicketResponse> createWebSocketTicket(HttpServletRequest request) {
        String accessToken = resolveAccessToken(request);
        ThrowUtils.throwIf(StringUtils.isBlank(accessToken), ErrorCode.NOT_LOGIN_ERROR);
        return ResultUtils.success(userService.createWebSocketTicket(accessToken));
    }

    @GetMapping("/refresh/uri")
    public BaseResponse<String> refreshUri(@RequestParam Long userId) {
        return ResultUtils.success(userService.refreshUri(userId));
    }

    @GetMapping("/uploadUrl")
    public BaseResponse<UploadUrlResponse> getUploadUrl(@RequestParam String fileName) {
        return ResultUtils.success(userService.uploadUrl(fileName));
    }

    @PostMapping("/update/avatar")
    public BaseResponse<Boolean> updateAvatar(@RequestBody UpdateAvatarRequest updateAvatarRequest)  {
        return ResultUtils.success(userService.updateAvatar(updateAvatarRequest));
    }

    private String resolveAccessToken(HttpServletRequest request) {
        String token = AuthTokenUtil.extract(request.getHeader("Authorization"));
        return token == null ? AuthTokenUtil.extract(request.getHeader("Access-Token")) : token;
    }

}
