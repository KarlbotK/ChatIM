package com.goat.userservice.service;


import com.goat.userservice.model.dto.request.InviteGroupRequest;
import com.goat.userservice.model.dto.response.InviteGroupResponse;
import com.goat.userservice.model.dto.response.GroupMemberListResponse;

//群组服务接口
public interface GroupService {

    GroupMemberListResponse listMembers(Long requesterId, Long sessionId, String cursor, Integer limit);

    /*
    * 邀请用户加入群聊
    *
    * 处理流程：
    * 1、验证会话存在且为群聊类型
    * 2、验证邀请人权限（群主、管理员）
    * 3、验证被邀请者是邀请者好友
    * 4、检查被邀请者是否已在群里
    * 5、创建UserSession记录
    * 6、发送kafka通知
    *
    * @param request 邀请请求参数
    * @return 邀请结果（成功、失败列表）
    * */
    InviteGroupResponse inviteGroup(InviteGroupRequest request);
}
