package com.goat.userservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.goat.common.common.ErrorCode;
import com.goat.common.constant.CommonConstant;
import com.goat.common.constant.SessionTypeConstant;
import com.goat.common.enums.UserSessionStatusEnum;
import com.goat.common.exception.ThrowUtils;
import com.goat.common.model.dto.SessionMessageSummaryRequest;
import com.goat.common.model.dto.SessionReadPosition;
import com.goat.common.model.vo.MessageResponse;
import com.goat.common.model.vo.SessionMessageSummary;
import com.goat.common.utils.SnowflakeUtil;
import com.goat.userservice.client.OfflineMessageClient;
import com.goat.userservice.constants.FriendStatusEnum;
import com.goat.userservice.mapper.FriendMapper;
import com.goat.userservice.mapper.SessionMapper;
import com.goat.userservice.mapper.UserSessionMapper;
import com.goat.userservice.model.dto.NewGroupSessionNotificationDTO;
import com.goat.userservice.model.dto.request.CreateGroupRequest;
import com.goat.userservice.model.dto.response.CreateGroupResponse;
import com.goat.userservice.model.dto.response.SessionListResponse;
import com.goat.userservice.model.dto.response.SessionReadResponse;
import com.goat.userservice.model.dto.response.SessionSummaryResponse;
import com.goat.userservice.model.entity.Friend;
import com.goat.userservice.model.entity.Session;
import com.goat.userservice.model.entity.User;
import com.goat.userservice.model.entity.UserSession;
import com.goat.userservice.service.NotificationService;
import com.goat.userservice.service.SessionService;
import com.goat.userservice.utils.OssUtils;
import com.goat.userservice.utils.SessionCursorCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SessionServiceImpl extends ServiceImpl<SessionMapper, Session>
    implements SessionService {

    private final SessionMapper sessionMapper;
    private final UserServiceImpl userService;
    private final FriendMapper friendMapper;
    private final UserSessionMapper userSessionMapper;
    private final NotificationService notificationService;
    private final OssUtils ossUtils;
    private final OfflineMessageClient offlineMessageClient;
    private final SessionCursorCodec sessionCursorCodec;

    public SessionServiceImpl(SessionMapper sessionMapper,
                              UserServiceImpl userService,
                              FriendMapper friendMapper,
                              UserSessionMapper userSessionMapper,
                              NotificationService notificationService,
                              OssUtils ossUtils,
                              OfflineMessageClient offlineMessageClient,
                              SessionCursorCodec sessionCursorCodec) {
        this.sessionMapper = sessionMapper;
        this.userService = userService;
        this.friendMapper = friendMapper;
        this.userSessionMapper=userSessionMapper;
        this.notificationService=notificationService;
        this.ossUtils=ossUtils;
        this.offlineMessageClient = offlineMessageClient;
        this.sessionCursorCodec = sessionCursorCodec;
    }

    private static final int USER_ROLE_GROUP_OWNER = 0;
    private static final int USER_ROLE_GROUP_MEMBER = 2;
    private static final int USER_STATUS_NORMAL = 0;
    private static final int SESSION_STATUS_NORMAL = 0;
    /** MinIO 中的默认群头像对象名，访问地址由 minio.url 配置生成。 */
    private static final String DEFAULT_GROUP_AVATAR_OBJECT_NAME = "group/default-avatar.jpg";

    private String defaultGroupAvatarUrl() {
        return ossUtils.downUrl(CommonConstant.BUCKET_NAME, DEFAULT_GROUP_AVATAR_OBJECT_NAME);
    }

    @Override
    public SessionListResponse listSessions(Long userId, String cursorValue, Integer requestedLimit) {
        int limit = Math.max(1, Math.min(
                requestedLimit == null ? CommonConstant.DEFAULT_LIMIT : requestedLimit,
                100
        ));
        SessionCursorCodec.SessionCursor cursor = sessionCursorCodec.decode(cursorValue, userId);

        LambdaQueryWrapper<UserSession> membershipQuery = new LambdaQueryWrapper<>();
        membershipQuery.eq(UserSession::getUserId, userId)
                .eq(UserSession::getStatus, SESSION_STATUS_NORMAL)
                .and(wrapper -> wrapper.eq(UserSession::getHidden, false)
                        .or()
                        .isNull(UserSession::getHidden));
        List<UserSession> memberships = userSessionMapper.selectList(membershipQuery);
        if (memberships.isEmpty()) {
            return SessionListResponse.builder()
                    .items(Collections.emptyList())
                    .nextCursor(cursorValue)
                    .hasMore(false)
                    .serverTime(System.currentTimeMillis())
                    .build();
        }

        List<Long> sessionIds = memberships.stream().map(UserSession::getSessionId).toList();
        Map<Long, UserSession> membershipBySession = memberships.stream()
                .collect(Collectors.toMap(UserSession::getSessionId, membership -> membership));

        SessionMessageSummaryRequest messageRequest = new SessionMessageSummaryRequest();
        messageRequest.setUserId(userId);
        messageRequest.setSessions(memberships.stream()
                .map(membership -> new SessionReadPosition(
                        membership.getSessionId(),
                        membership.getLastReadMessageId()
                ))
                .toList());
        Map<Long, SessionMessageSummary> messageBySession = offlineMessageClient
                .getSessionMessageSummaries(messageRequest)
                .stream()
                .collect(Collectors.toMap(SessionMessageSummary::getSessionId, summary -> summary));

        Map<Long, Session> sessionById = sessionMapper.selectByIds(sessionIds).stream()
                .filter(session -> session.getStatus() != null && session.getStatus() == SESSION_STATUS_NORMAL)
                .collect(Collectors.toMap(Session::getSessionId, session -> session));
        LambdaQueryWrapper<UserSession> allMembersQuery = new LambdaQueryWrapper<>();
        allMembersQuery.in(UserSession::getSessionId, sessionIds)
                .eq(UserSession::getStatus, SESSION_STATUS_NORMAL);
        List<UserSession> allMembers = userSessionMapper.selectList(allMembersQuery);
        Map<Long, List<UserSession>> membersBySession = allMembers.stream()
                .collect(Collectors.groupingBy(UserSession::getSessionId));
        Set<Long> peerIds = allMembers.stream()
                .filter(membership -> !userId.equals(membership.getUserId()))
                .map(UserSession::getUserId)
                .collect(Collectors.toSet());
        Map<Long, User> usersById = peerIds.isEmpty()
                ? Collections.emptyMap()
                : userService.listByIds(peerIds).stream()
                        .collect(Collectors.toMap(User::getUserId, user -> user));

        List<SessionSummaryResponse> summaries = new ArrayList<>();
        for (Long sessionId : sessionIds) {
            Session session = sessionById.get(sessionId);
            if (session == null) {
                continue;
            }
            UserSession membership = membershipBySession.get(sessionId);
            List<UserSession> members = membersBySession.getOrDefault(sessionId, Collections.emptyList());
            User peer = session.getType() != null && session.getType() == SessionTypeConstant.SIGNAL_TYPE
                    ? members.stream()
                            .map(UserSession::getUserId)
                            .filter(memberId -> !userId.equals(memberId))
                            .map(usersById::get)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null)
                    : null;
            SessionMessageSummary messageSummary = messageBySession.get(sessionId);
            MessageResponse lastMessage = messageSummary == null ? null : messageSummary.getLastMessage();
            long lastMessageTime = lastMessage == null || lastMessage.getCreatedTime() == null
                    ? dateValue(session.getUpdatedTime(), session.getCreatedTime())
                    : lastMessage.getCreatedTime();
            summaries.add(SessionSummaryResponse.builder()
                    .sessionId(sessionId)
                    .sessionType(session.getType())
                    .name(peer == null ? session.getName() : peer.getNickname())
                    .avatar(peer == null ? session.getAvatar() : peer.getAvatar())
                    .peerId(peer == null ? null : peer.getUserId())
                    .lastMessage(lastMessage)
                    .lastMessageId(lastMessage == null ? null : lastMessage.getMessageId())
                    .lastMessageTime(lastMessageTime)
                    .unreadCount(messageSummary == null ? 0L : messageSummary.getUnreadCount())
                    .pinned(Boolean.TRUE.equals(membership.getPinned()))
                    .muted(Boolean.TRUE.equals(membership.getMuted()))
                    .memberCount(session.getType() != null && session.getType() == SessionTypeConstant.GROUP_TYPE
                            ? members.size() : null)
                    .currentUserRole(session.getType() != null && session.getType() == SessionTypeConstant.GROUP_TYPE
                            ? membership.getRole() : null)
                    .lastReadMessageId(membership.getLastReadMessageId())
                    .updatedTime(Math.max(
                            lastMessageTime,
                            dateValue(membership.getUpdatedTime(), membership.getCreatedTime())
                    ))
                    .build());
        }

        Comparator<SessionSummaryResponse> order = Comparator
                .comparing(SessionSummaryResponse::isPinned).reversed()
                .thenComparing(
                        SessionSummaryResponse::getLastMessageTime,
                        Comparator.nullsLast(Comparator.reverseOrder())
                )
                .thenComparing(SessionSummaryResponse::getSessionId, Comparator.reverseOrder());
        List<SessionSummaryResponse> ordered = summaries.stream()
                .sorted(order)
                .filter(summary -> isAfterCursor(summary, cursor))
                .toList();
        boolean hasMore = ordered.size() > limit;
        List<SessionSummaryResponse> page = new ArrayList<>(
                ordered.subList(0, Math.min(limit, ordered.size()))
        );
        String nextCursor = cursorValue;
        if (!page.isEmpty()) {
            SessionSummaryResponse last = page.get(page.size() - 1);
            nextCursor = sessionCursorCodec.encode(
                    userId,
                    last.isPinned(),
                    last.getLastMessageTime() == null ? 0L : last.getLastMessageTime(),
                    last.getSessionId()
            );
        }
        return SessionListResponse.builder()
                .items(page)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .serverTime(System.currentTimeMillis())
                .build();
    }

    @Override
    public SessionReadResponse markRead(Long userId, Long sessionId, Long lastReadMessageId) {
        LambdaQueryWrapper<UserSession> membershipQuery = new LambdaQueryWrapper<>();
        membershipQuery.eq(UserSession::getUserId, userId)
                .eq(UserSession::getSessionId, sessionId)
                .eq(UserSession::getStatus, SESSION_STATUS_NORMAL);
        UserSession membership = userSessionMapper.selectOne(membershipQuery);
        ThrowUtils.throwIf(membership == null, ErrorCode.MESSAGE_NOT_IN_SESSION);
        ThrowUtils.throwIf(!offlineMessageClient.isMessageInSession(sessionId, lastReadMessageId),
                ErrorCode.READ_POSITION_INVALID);

        userSessionMapper.advanceReadPosition(userId, sessionId, lastReadMessageId);
        UserSession updatedMembership = userSessionMapper.selectOne(membershipQuery);
        ThrowUtils.throwIf(updatedMembership == null || updatedMembership.getLastReadMessageId() == null,
                ErrorCode.OPERATION_ERROR);
        long effectiveReadMessageId = updatedMembership.getLastReadMessageId();

        SessionMessageSummaryRequest summaryRequest = new SessionMessageSummaryRequest();
        summaryRequest.setUserId(userId);
        summaryRequest.setSessions(List.of(new SessionReadPosition(sessionId, effectiveReadMessageId)));
        long unreadCount = offlineMessageClient.getSessionMessageSummaries(summaryRequest).stream()
                .findFirst()
                .map(SessionMessageSummary::getUnreadCount)
                .orElse(0L);
        return SessionReadResponse.builder()
                .sessionId(sessionId)
                .lastReadMessageId(effectiveReadMessageId)
                .unreadCount(unreadCount)
                .build();
    }

    private boolean isAfterCursor(
            SessionSummaryResponse summary,
            SessionCursorCodec.SessionCursor cursor) {
        if (cursor == null) {
            return true;
        }
        if (summary.isPinned() != cursor.pinned()) {
            return !summary.isPinned() && cursor.pinned();
        }
        long summaryTime = summary.getLastMessageTime() == null ? 0L : summary.getLastMessageTime();
        if (summaryTime != cursor.lastMessageTime()) {
            return summaryTime < cursor.lastMessageTime();
        }
        return summary.getSessionId() < cursor.sessionId();
    }

    private long dateValue(Date primary, Date fallback) {
        Date value = primary == null ? fallback : primary;
        return value == null ? 0L : value.getTime();
    }


    // SessionServiceImpl.java
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreateGroupResponse createGroup(CreateGroupRequest request) {
        Long creatorId = request.getCreatorId();
        List<Long> memberIds = request.getMemberIds();
        List<String> failedMemberIds = new ArrayList<>();

        // 1. 参数校验
        validateCreateGroupParameters(creatorId, memberIds);

        // 2. 确认创建者用户存在且状态正常
        getActiveUserById(creatorId);

        // 3. 验证好友关系并获取有效成员ID（使用Lambda Wrapper）
        List<Long> validMemberIds = validateAndFilterMembers(creatorId, memberIds, failedMemberIds);

        ThrowUtils.throwIf(validMemberIds.isEmpty(), ErrorCode.OPERATION_ERROR, "没有有效的好友可加入群聊");

        // 4. 生成 sessionId
        Long sessionId = SnowflakeUtil.nextId();

        // 5. 生成群名称
        String groupName = generateGroupName(creatorId, validMemberIds);

        // 6. 插入 session 表
        Session session = createSession(sessionId, groupName);
        sessionMapper.insert(session);

        // 7. 插入 user_session 表 - 创建者
        insertUserSession(sessionId, creatorId, USER_ROLE_GROUP_OWNER);

        // 8. 计算成员数量 = 有效成员数 + 群主
        int membersCount = validMemberIds.size() + 1;

        // 9. 构建推送新群会话消息
        NewGroupSessionNotificationDTO notification =
                buildNewGroupSessionNotification(creatorId, groupName, membersCount);

        // 10. 插入 user_session 表 - 其他成员并推送 Kafka 通知
        insertMembersAndPushNotifications(validMemberIds, sessionId, notification, failedMemberIds);

        // 11. 响应结果
        CreateGroupResponse response = new CreateGroupResponse();
        BeanUtils.copyProperties(notification, response);
        response.setCreatorId(String.valueOf(creatorId));
        response.setSessionId(String.valueOf(sessionId));
        response.setSessionType(SessionTypeConstant.GROUP_TYPE);
        response.setFailedMemberIds(failedMemberIds);
        return response;
    }

    // SessionServiceImpl.java
    private void validateCreateGroupParameters(Long creatorId, List<Long> memberIds) {
        ThrowUtils.throwIf(creatorId == null, ErrorCode.PARAMS_ERROR, "创建者ID不能为空");
        ThrowUtils.throwIf(memberIds == null || memberIds.isEmpty(),
                ErrorCode.PARAMS_ERROR, "成员ID列表不能为空");
    }

    // SessionServiceImpl.java
    private User getActiveUserById(Long userId) {
        User user = userService.getById(userId);
        ThrowUtils.throwIf(user == null || user.getState() != USER_STATUS_NORMAL,
                ErrorCode.NOT_FOUND_ERROR, "用户不存在或状态异常");
        return user;
    }

    // SessionServiceImpl.java
    private List<Long> validateAndFilterMembers(Long creatorId, List<Long> memberIds,
                                                List<String> failedMemberIds) {
        // 获取创建者所有好友 ID（使用 Lambda Wrapper）
        LambdaQueryWrapper<Friend> friendWrapper = new LambdaQueryWrapper<>();
        friendWrapper.eq(Friend::getUserId, creatorId)
                .eq(Friend::getStatus, FriendStatusEnum.NORMAL.getCode());
        List<Friend> friends = friendMapper.selectList(friendWrapper);

        Set<Long> friendIdSet = friends.stream()
                .map(Friend::getFriendId)
                .collect(Collectors.toSet());

        List<Long> validMemberIds = new ArrayList<>();

        for (Long memberId : memberIds) {
            if (friendIdSet.contains(memberId)) {
                validMemberIds.add(memberId);
            } else {
                failedMemberIds.add(String.valueOf(memberId));
                log.info("成员ID {} 不是创建者的好友，无法加入群聊", memberId);
            }
        }
        return validMemberIds;
    }

    // SessionServiceImpl.java
    private String generateGroupName(Long creatorId, List<Long> memberIds) {
        StringBuilder groupNameBuilder = new StringBuilder();
        List<Long> allMemberIds = new ArrayList<>(memberIds);
        allMemberIds.add(0, creatorId); // 确保群主 ID 在首位

        // 查询所有用户信息
        List<User> users = userService.listByIds(allMemberIds);

        // 构建 ID -> User 映射，确保顺序可控
        Map<Long, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getUserId, user -> user));

        // 按照 allMemberIds 的顺序拼接用户名
        for (Long memberId : allMemberIds) {
            User user = userMap.get(memberId);
            if (user != null) {
                if (groupNameBuilder.length() > 0) {
                    groupNameBuilder.append("、");
                }
                groupNameBuilder.append(user.getNickname());
                if (groupNameBuilder.length() >= 16) {
                    groupNameBuilder.setLength(16); // 截取前 16 个字符
                    break;
                }
            }
        }
        return groupNameBuilder.toString();
    }

    // SessionServiceImpl.java
    private Session createSession(Long sessionId, String groupName) {
        Session session = new Session();
        session.setSessionId(sessionId);
        session.setName(groupName);
        session.setType(SessionTypeConstant.GROUP_TYPE);   // 1
        session.setStatus(SESSION_STATUS_NORMAL);          // 0
        session.setAvatar(defaultGroupAvatarUrl());
        session.setCreatedTime(new Date());
        session.setUpdatedTime(new Date());
        return session;
    }

    // SessionServiceImpl.java
    private void insertUserSession(Long sessionId, Long userId, int role) {
        UserSession userSession = new UserSession();
        userSession.setUserId(userId);
        userSession.setSessionId(sessionId);
        userSession.setRole(role);
        userSession.setStatus(SESSION_STATUS_NORMAL);
        userSession.setCreatedTime(new Date());
        userSession.setUpdatedTime(new Date());
        userSessionMapper.insert(userSession);
    }

    private NewGroupSessionNotificationDTO buildNewGroupSessionNotification(Long creatorId, String groupName, int membersCount) {
        NewGroupSessionNotificationDTO notification = new NewGroupSessionNotificationDTO();
        notification.setCreatorId(creatorId);
        notification.setSessionName(groupName);
        notification.setMembersCount(membersCount);
        notification.setAvatar(defaultGroupAvatarUrl());
        return notification;
    }
    // SessionServiceImpl.java
    private void insertMembersAndPushNotifications(List<Long> memberIds, Long sessionId,
                                                   NewGroupSessionNotificationDTO notification,
                                                   List<String> failedMemberIds) {
        for (Long memberId : memberIds) {
            // 插入用户会话关系
            insertUserSession(sessionId, memberId, USER_ROLE_GROUP_MEMBER);

            // 推送 Kafka 通知
            try {
                notificationService.pushGroupNewSession(memberId, sessionId, notification);
            } catch (Exception e) {
                failedMemberIds.add(String.valueOf(memberId));
                log.error("推送群聊会话失败，成员ID {}，错误信息：{}", memberId, e.getMessage());
            }
        }
    }
}
