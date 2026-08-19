package com.goat.userservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.goat.common.common.ErrorCode;
import com.goat.common.constant.CommonConstant;
import com.goat.common.constant.SessionTypeConstant;
import com.goat.common.exception.ThrowUtils;
import com.goat.userservice.constants.FriendStatusEnum;
import com.goat.userservice.mapper.FriendMapper;
import com.goat.userservice.mapper.SessionMapper;
import com.goat.userservice.mapper.UserMapper;
import com.goat.userservice.mapper.UserSessionMapper;
import com.goat.userservice.model.dto.NewGroupSessionNotificationDTO;
import com.goat.userservice.model.dto.request.InviteGroupRequest;
import com.goat.userservice.model.dto.response.InviteGroupResponse;
import com.goat.userservice.model.entity.Friend;
import com.goat.userservice.model.entity.Session;
import com.goat.userservice.model.entity.UserSession;
import com.goat.userservice.service.GroupService;
import com.goat.userservice.service.NotificationService;
import com.goat.userservice.service.UserSessionService;
import com.goat.userservice.utils.OssUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GroupServiceImpl implements GroupService {
    private final SessionMapper sessionMapper;
    private final UserSessionMapper userSessionMapper;
    private final FriendMapper friendMapper;
    private final NotificationService notificationService;
    private final UserSessionService userSessionService;
    private final OssUtils ossUtils;

    private static final int USER_ROLE_GROUP_OWNER=0;
    private static final int USER_ROLE_GROUP_ADMIN=1;
    private static final int USER_ROLE_GROUP_MEMBER=2;

    private static final int SESSION_STATUS_NORMAL=0;

    private static final String DEFAULT_GROUP_AVATAR_OBJECT_NAME = "group/default-avatar.jpg";
    private final UserMapper userMapper;

    private String defaultGroupAvatarUrl() {
        return ossUtils.downUrl(CommonConstant.BUCKET_NAME, DEFAULT_GROUP_AVATAR_OBJECT_NAME);
    }

    public GroupServiceImpl(SessionMapper sessionMapper,
                            UserSessionMapper userSessionMapper,
                            FriendMapper friendMapper,
                            NotificationService notificationService,
                            UserSessionService userSessionService,
                            OssUtils ossUtils, UserMapper userMapper){
        this.sessionMapper=sessionMapper;
        this.userSessionMapper=userSessionMapper;
        this.friendMapper=friendMapper;
        this.notificationService=notificationService;
        this.userSessionService=userSessionService;
        this.ossUtils=ossUtils;
        this.userMapper = userMapper;
    }

    // GroupServiceImpl.java
    @Override
    @Transactional(rollbackFor = Exception.class)
    public InviteGroupResponse inviteGroup(InviteGroupRequest request) {
        Long sessionId = request.getSessionId();
        Long inviterId = request.getInviterId();
        List<Long> inviteeIds = request.getInviteeIds();

        log.info("开始邀请成员加入群聊，sessionId: {}, inviterId: {}, inviteeIds: {}",
                sessionId, inviterId, inviteeIds);

        // 1. 参数校验
        validateInviteGroupParameters(sessionId, inviterId, inviteeIds);

        // 2. 校验会话存在且为群聊
        Session session = validateSession(sessionId);

        // 3. 校验邀请人权限（必须是群主或管理员）
        validateInviterPermission(sessionId, inviterId);

        // 4. 校验邀请人与被邀请人的好友关系
        List<Long> failedIds = new ArrayList<>();
        List<Long> validInviteeIds = validateAndFilterFriends(inviterId, inviteeIds, failedIds);

        // 5. 过滤已在群内的成员
        validInviteeIds = filterExistingMembers(sessionId, validInviteeIds, failedIds);

        ThrowUtils.throwIf(validInviteeIds.isEmpty(),
                ErrorCode.OPERATION_ERROR, "没有有效的好友可加入群聊");

        // 6. 插入 user_session 记录并推送 Kafka 通知
        List<Long> successIds = insertMembersAndPushNotifications(
                sessionId, session.getName(), validInviteeIds, failedIds);

        // 7. 构建响应
        InviteGroupResponse response = new InviteGroupResponse();
        response.setSuccessIds(successIds.stream().map(String::valueOf).collect(Collectors.toList()));
        response.setFailedIds(failedIds.stream().map(String::valueOf).collect(Collectors.toList()));

        log.info("群聊邀请完成，sessionId: {}, 成功: {}, 失败: {}",
                sessionId, successIds.size(), failedIds.size());
        return response;
    }

    private void validateInviteGroupParameters(Long sessionId,Long inviterId,List<Long> inviteeIds){
        ThrowUtils.throwIf(sessionId==null||sessionId<=0,ErrorCode.PARAMS_ERROR,"会话ID不能为空");
        ThrowUtils.throwIf(inviterId==null||inviterId<=0,ErrorCode.PARAMS_ERROR,"邀请人ID不能为空");
        ThrowUtils.throwIf(inviteeIds==null||inviteeIds.isEmpty(),ErrorCode.PARAMS_ERROR,"被邀请人ID列表不能为空");
    }

    // GroupServiceImpl.java
    private Session validateSession(Long sessionId) {
        LambdaQueryWrapper<Session> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Session::getSessionId, sessionId)
                .eq(Session::getStatus, SESSION_STATUS_NORMAL);
        Session session = sessionMapper.selectOne(wrapper);

        ThrowUtils.throwIf(session == null, ErrorCode.NOT_FOUND_ERROR, "群聊不存在或已解散");
        ThrowUtils.throwIf(!(SessionTypeConstant.GROUP_TYPE == session.getType()),
                ErrorCode.PARAMS_ERROR, "该会话不是群聊");

        return session;
    }
    // GroupServiceImpl.java
    private void validateInviterPermission(Long sessionId, Long inviterId) {
        LambdaQueryWrapper<UserSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserSession::getSessionId, sessionId)
                .eq(UserSession::getUserId, inviterId)
                .eq(UserSession::getStatus, SESSION_STATUS_NORMAL);
        UserSession userSession = userSessionMapper.selectOne(wrapper);

        ThrowUtils.throwIf(userSession == null, ErrorCode.NO_AUTH_ERROR, "您不在该群聊中");
        ThrowUtils.throwIf(userSession.getRole() != USER_ROLE_GROUP_OWNER
                        && userSession.getRole() != USER_ROLE_GROUP_ADMIN,
                ErrorCode.NO_AUTH_ERROR, "只有群主或管理员才能邀请成员");
    }
    // GroupServiceImpl.java
    private List<Long> validateAndFilterFriends(Long inviterId, List<Long> inviteeIds,
                                                List<Long> failedIds) {
        LambdaQueryWrapper<Friend> friendWrapper = new LambdaQueryWrapper<>();
        friendWrapper.eq(Friend::getUserId, inviterId)
                .eq(Friend::getStatus, FriendStatusEnum.NORMAL.getCode());
        List<Friend> friends = friendMapper.selectList(friendWrapper);

        Set<Long> friendIdSet = friends.stream()
                .map(Friend::getFriendId)
                .collect(Collectors.toSet());

        List<Long> validInviteeIds = new ArrayList<>();
        for (Long inviteeId : inviteeIds) {
            if (friendIdSet.contains(inviteeId)) {
                validInviteeIds.add(inviteeId);
            } else {
                failedIds.add(inviteeId);
                log.info("被邀请人ID {} 不是邀请人的好友，无法加入群聊", inviteeId);
            }
        }
        return validInviteeIds;
    }
    // GroupServiceImpl.java
    private List<Long> filterExistingMembers(Long sessionId, List<Long> inviteeIds,
                                             List<Long> failedIds) {
        if (inviteeIds.isEmpty()) {
            return inviteeIds;
        }

        // 查询已在群内的成员
        LambdaQueryWrapper<UserSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserSession::getSessionId, sessionId)
                .in(UserSession::getUserId, inviteeIds)
                .eq(UserSession::getStatus, SESSION_STATUS_NORMAL);
        List<UserSession> existingMembers = userSessionMapper.selectList(wrapper);

        Set<Long> existingMemberIds = existingMembers.stream()
                .map(UserSession::getUserId)
                .collect(Collectors.toSet());

        List<Long> newInviteeIds = new ArrayList<>();
        for (Long inviteeId : inviteeIds) {
            if (existingMemberIds.contains(inviteeId)) {
                failedIds.add(inviteeId);
                log.info("被邀请人ID {} 已在群聊中", inviteeId);
            } else {
                newInviteeIds.add(inviteeId);
            }
        }
        return newInviteeIds;
    }
    // GroupServiceImpl.java
    private List<Long> insertMembersAndPushNotifications(Long sessionId, String groupName,
                                                         List<Long> inviteeIds, List<Long> failedIds) {
        List<Long> successIds = new ArrayList<>();

        // 1. 先插入所有成员
        for (Long inviteeId : inviteeIds) {
            try {
                insertUserSession(sessionId, inviteeId, USER_ROLE_GROUP_MEMBER);
                successIds.add(inviteeId);
            } catch (Exception e) {
                failedIds.add(inviteeId);
                log.error("邀请成员加入群聊失败，成员ID {}，错误信息：{}", inviteeId, e.getMessage(), e);
            }
        }

        // 2. 获取群主 ID 和最新成员数量（所有成员插入后）
        Long creatorId = getGroupCreatorId(sessionId);
        int membersCount = userSessionService.getGroupMemberCount(sessionId);

        // 3. 构建通知消息
        NewGroupSessionNotificationDTO notification =
                buildNewGroupSessionNotification(groupName, creatorId, membersCount);

        // 4. 统一推送 Kafka 通知
        for (Long inviteeId : successIds) {
            try {
                notificationService.pushGroupNewSession(inviteeId, sessionId, notification);
            } catch (Exception e) {
                log.error("推送群聊会话通知失败，成员ID {}，错误信息：{}", inviteeId, e.getMessage(), e);
                // 通知失败不影响邀请成功状态，仅记录日志
            }
        }
        return successIds;
    }
    // GroupServiceImpl.java
    private Long getGroupCreatorId(Long sessionId) {
        LambdaQueryWrapper<UserSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserSession::getSessionId, sessionId)
                .eq(UserSession::getRole, USER_ROLE_GROUP_OWNER)
                .eq(UserSession::getStatus, SESSION_STATUS_NORMAL);
        UserSession ownerSession = userSessionMapper.selectOne(wrapper);
        ThrowUtils.throwIf(ownerSession == null, ErrorCode.NOT_FOUND_ERROR, "群主信息不存在");
        return ownerSession.getUserId();
    }

    private void insertUserSession(Long sessionId,Long userId,int role){
        UserSession userSession=new UserSession();
        userSession.setSessionId(sessionId);
        userSession.setUserId(userId);
        userSession.setRole(role);
        userSession.setStatus(SESSION_STATUS_NORMAL);
        userSession.setCreatedTime(new Date());
        userSession.setUpdatedTime(new Date());
        userSessionMapper.insert(userSession);
    }

    private NewGroupSessionNotificationDTO buildNewGroupSessionNotification(String groupName,Long creatorId,int membersCount){
        NewGroupSessionNotificationDTO notification=new NewGroupSessionNotificationDTO();
        notification.setAvatar(defaultGroupAvatarUrl());
        notification.setSessionName(groupName);
        notification.setCreatorId(creatorId);
        notification.setMembersCount(membersCount);
        return notification;
    }
}
