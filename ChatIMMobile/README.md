# ChatIM 移动端产品原型

当前已完成账号流程和登录后的第一版核心聊天体验，并按选定的第三套视觉方案实现统一的移动端页面。

## 当前可体验功能

- 密码登录、验证码登录、注册、会话恢复和退出登录；
- 会话列表、未读数、置顶/免打扰/隐藏操作、草稿/失败等列表状态；
- 会话搜索、聊天详情、文本发送、发送状态，以及待确认/失败消息的结果查询和手动重试；
- 联系人列表、好友搜索、好友资料和好友申请发送；
- 新朋友申请列表、未读数、批量已读、接受/拒绝，以及通过后直接发起会话；
- 群聊列表、好友多选建群、部分失败结果、服务端群资料、分页成员、群资料修改、成员管理，以及群主或管理员继续邀请好友；
- 聊天图片选择、格式与大小校验、最长边压缩、上传进度、图片气泡和全屏预览；
- WebSocket 实时收发、心跳保活、指数退避重连和服务端回推确认；
- 登录及重连后的会话摘要同步、服务端未读数校正、已读位置提交、离线消息补拉、按消息编号去重，以及向上加载历史消息；
- 发现页和个人中心的第一版信息架构；
- 按账号保存草稿、最近会话和消息，刷新后可以恢复；
- iPhone 和 Pixel 10 两种设备预览，以及键盘顶起和安全区适配。

## 本地运行

```powershell
npm install
npm run dev -- --host 0.0.0.0 --port 4173
```

打开 `http://localhost:4173/` 使用真实接口。前端默认连接 `http://localhost:10010`，也可以复制 `.env.example` 为 `.env.local` 后修改 `VITE_API_BASE_URL`。

Gateway 开发环境需要允许此页面的来源：

```powershell
$env:FRONTEND_ORIGIN="http://localhost:4173"
```

打开 `http://localhost:4173/?demo=1` 可直接体验完整流程。演示邮箱可使用任意格式正确的地址，密码和验证码都是 `123456`。

## 已接入接口

- `GET /api/user/sendCaptcha`
- `POST /api/user/login/password`
- `POST /api/user/login/code`
- `POST /api/user/register`
- `POST /api/user/refresh`
- `GET /api/user/logout`
- `GET /api/contact/{userId}/user/search`
- `GET /api/contact/{userId}/friend`
- `GET /api/contact/{userId}/friend/{friendId}`
- `POST /api/contact/{userId}/friend/{receiverId}`
- `GET /api/contact/{userId}/apply`
- `GET /api/contact/{userId}/applyCount`
- `POST /api/contact/{userId}/application/{status}`
- `POST /api/group`
- `POST /api/group/invite`
- `GET /api/file/{sessionId}/upload-url?fileName=`，返回会话内稳定对象标识，随后直接 `PUT` 到 MinIO 预签名地址
- `GET /api/file/{sessionId}/download-url?objectName=`，校验会话权限后获取短期下载地址
- `WS /ws/netty`，文本心跳与消息收发
- `POST /api/message/offline/sync`
- `POST /api/message/history`
- `GET /api/message/status?clientMessageId=`
- `GET /api/session/list?cursor=&limit=`
- `GET /api/session/{sessionId}`
- `POST /api/session/{sessionId}/read`
- `POST /api/session/{sessionId}/pin`
- `POST /api/session/{sessionId}/mute`
- `DELETE /api/session/{sessionId}`，仅隐藏当前用户的会话，新消息到达后恢复显示
- `GET /api/group/{sessionId}/members?cursor=&limit=`
- `PATCH /api/group/{sessionId}`
- `GET /api/group/{sessionId}/avatar/upload-url?fileName=`
- `PUT /api/group/{sessionId}/avatar`
- `POST /api/group/{sessionId}/leave`
- `DELETE /api/group/{sessionId}/members/{userId}`
- `POST /api/group/{sessionId}/admins`
- `DELETE /api/group/{sessionId}/admins/{userId}`
- `POST /api/group/{sessionId}/transfer-owner`
- `DELETE /api/group/{sessionId}`
- `GET /api/message/unread`

认证数据统一通过 `window.chatIMSecureStorage` 适配层读写。原生容器必须将该桥接实现为 iOS Keychain 或 Android Keystore；没有原生桥接时，浏览器原型才使用 `localStorage` 联调。存储内容带版本和 `VITE_ENVIRONMENT_ID`，不会跨环境恢复令牌，并会自动迁移旧版浏览器会话。退出登录会同时清除认证数据及当前 `userId` 对应的草稿、消息、同步游标等缓存。

原生桥接需要提供以下异步或同步方法：

```ts
window.chatIMSecureStorage = { getItem, setItem, removeItem };
window.chatIMAccountStorage = { getItem, setItem, removeItem, clearUser };
```

`chatIMSecureStorage` 保存令牌和当前账号；`chatIMAccountStorage` 由账号隔离的 SQLite 数据库实现，所有方法的第一个参数均为 `userId`，离线同步游标已经通过该桥接读写。浏览器回退仅用于演示和开发，不能视为生产安全存储。

## 当前接口边界

后端已经公开会话摘要、会话详情、已读位置、未读数、置顶、免打扰、隐藏、群成员分页、群资料修改和成员管理接口。前端在冷启动与重连后以服务端会话状态为准，在会话可见后提交最后消息 ID；置顶区保持独立排序，隐藏会话收到持久化新消息后自动恢复显示。打开群资料时会同步群信息、成员角色和成员列表。群主可修改资料、管理成员、转让或解散群聊，管理员可修改公告和移除普通成员，其他成员可退出；在线成员会通过系统事件收到最新群资料和成员状态。消息发送会分别收到 `accepted`、`persisted` 或 `failed` ACK，前端只在 `persisted` 后显示“已发送”；ACK 超时后会查询 `/api/message/status`，无法确认时显示“结果待确认”。用户点击状态按钮后会再次查询，已落库直接归并、处理中继续等待，只有确认未落库或可恢复失败才复用原 `clientMessageId` 手动重试；权限和参数错误只展示服务端原因。

WebSocket 建连后，客户端通过 `/api/message/offline/sync` 循环拉取 MySQL 中的完整消息，每页合并成功后保存当前账号专属的服务端游标，直到 `hasMore` 为 false。

移动端原生连接通过 `window.chatIMCreateWebSocket(url, headers)` 注入 `Authorization: Bearer <accessToken>`。标准浏览器会先调用 `POST /api/user/ws-ticket` 获取最长 60 秒、仅可使用一次的短期 ticket，再连接服务端返回的 `nettyUri`；长期令牌不会写入 WebSocket URL。离线和历史消息接口继续通过 Gateway 访问。
