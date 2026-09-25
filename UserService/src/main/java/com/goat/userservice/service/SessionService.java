package com.goat.userservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.goat.userservice.model.dto.request.CreateGroupRequest;
import com.goat.userservice.model.dto.response.CreateGroupResponse;
import com.goat.userservice.model.dto.response.SessionListResponse;
import com.goat.userservice.model.dto.response.SessionReadResponse;
import com.goat.userservice.model.entity.Session;


public interface SessionService extends IService<Session> {
    CreateGroupResponse createGroup(CreateGroupRequest request);

    SessionListResponse listSessions(Long userId, String cursor, Integer limit);

    SessionReadResponse markRead(Long userId, Long sessionId, Long lastReadMessageId);

}
