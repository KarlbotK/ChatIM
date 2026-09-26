package com.goat.userservice.service;


import com.goat.userservice.model.dto.request.InviteGroupRequest;
import com.goat.userservice.model.dto.request.GroupMemberTargetRequest;
import com.goat.userservice.model.dto.request.UpdateGroupAvatarRequest;
import com.goat.userservice.model.dto.request.UpdateGroupProfileRequest;
import com.goat.userservice.model.dto.response.GroupAvatarUploadResponse;
import com.goat.userservice.model.dto.response.GroupManagementResponse;
import com.goat.userservice.model.dto.response.GroupProfileResponse;
import com.goat.userservice.model.dto.response.InviteGroupResponse;
import com.goat.userservice.model.dto.response.GroupMemberListResponse;

//群组服务接口
public interface GroupService {

    GroupMemberListResponse listMembers(Long requesterId, Long sessionId, String cursor, Integer limit);

    GroupProfileResponse updateProfile(Long requesterId, Long sessionId, UpdateGroupProfileRequest request);

    GroupAvatarUploadResponse createAvatarUpload(Long requesterId, Long sessionId, String fileName);

    GroupProfileResponse updateAvatar(Long requesterId, Long sessionId, UpdateGroupAvatarRequest request);

    GroupManagementResponse leaveGroup(Long requesterId, Long sessionId);

    GroupManagementResponse removeMember(Long requesterId, Long sessionId, Long memberId);

    GroupManagementResponse addAdministrator(Long requesterId, Long sessionId, GroupMemberTargetRequest request);

    GroupManagementResponse removeAdministrator(Long requesterId, Long sessionId, Long memberId);

    GroupManagementResponse transferOwner(Long requesterId, Long sessionId, GroupMemberTargetRequest request);

    GroupManagementResponse dissolveGroup(Long requesterId, Long sessionId);

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
