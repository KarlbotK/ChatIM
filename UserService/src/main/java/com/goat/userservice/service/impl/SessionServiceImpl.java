package com.goat.userservice.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.goat.userservice.mapper.SessionMapper;
import com.goat.userservice.model.entity.Session;
import com.goat.userservice.service.SessionService;
import org.springframework.stereotype.Service;


@Service
public class SessionServiceImpl extends ServiceImpl<SessionMapper, Session>
    implements SessionService {

}