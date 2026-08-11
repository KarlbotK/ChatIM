package com.goat.userservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.goat.userservice.model.dto.request.CreateGroupRequest;
import com.goat.userservice.model.dto.response.CreateGroupResponse;
import com.goat.userservice.model.entity.Session;


public interface SessionService extends IService<Session> {
    CreateGroupResponse createGroup(CreateGroupRequest request);

}