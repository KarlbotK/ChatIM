package com.goat.userservice.controller;

import com.goat.common.common.BaseResponse;
import com.goat.common.common.ErrorCode;
import com.goat.common.common.ResultUtils;
import com.goat.common.exception.BusinessException;
import com.goat.userservice.model.vo.FriendDetailVO;
import com.goat.userservice.service.FriendService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 联系人Controller
 * <p>
 * 功能说明：
 * - 提供好友相关的REST API接口
 * - 包含好友申请、好友管理、好友查询等功能
 * - 支持分页查询和关键字搜索
 */
@Slf4j
@RestController
@RequestMapping("/api/contact")
public class ContactController {
    private final FriendService friendService;

    public ContactController(FriendService friendService) {
        this.friendService = friendService;
    }


/**
 * 搜索用户（手机号或邮箱）
 *
 * @param userId  用户ID
 * @param keyword 搜索关键字（手机号或邮箱）
 * @return 用户详情
 */

    @GetMapping("/{userId}/user/search")
    public BaseResponse<?> searchUser(@PathVariable("userId")String userId,@RequestParam(value = "keyword")String keyword){
        try {
            FriendDetailVO friendDetail = friendService.searchUserByKeyword(userId, keyword);
            return ResultUtils.success(friendDetail);
        } catch (BusinessException e) {
            log.error("搜索用户失败，用户ID：{}，关键字：{}，原因：{}", userId, keyword, e.getMessage());
            return ResultUtils.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("搜索用户失败，用户ID：{}，关键字：{}，原因：{}", userId, keyword, e.getMessage(), e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR);
        }
    }
}
