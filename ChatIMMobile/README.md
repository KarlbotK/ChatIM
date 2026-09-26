# ChatIM 移动端产品原型

当前已完成账号流程和登录后的第一版核心聊天体验，并按选定的第三套视觉方案实现统一的移动端页面。

## 当前可体验功能

- 密码登录、验证码登录、注册、会话恢复和退出登录；
- 会话列表、未读数、置顶/免打扰/草稿/失败等列表状态；
- 会话搜索、聊天详情、文本发送与发送状态；
- 联系人列表、好友搜索、好友资料和好友申请发送；
- 新朋友申请列表、未读数、批量已读、接受/拒绝，以及通过后直接发起会话；
- 群聊列表、好友多选建群、部分失败结果、服务端群资料、分页成员、群名称/公告/头像修改，以及群主或管理员继续邀请好友；
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
- `GET /api/user/uploadUrl?fileName=`，随后直接 `PUT` 到 MinIO 预签名地址
- `WS /ws/netty`，文本心跳与消息收发
- `POST /api/message/offline/sync`
- `POST /api/message/history`
- `GET /api/message/status?clientMessageId=`
- `GET /api/session/list?cursor=&limit=`
- `GET /api/session/{sessionId}`
- `POST /api/session/{sessionId}/read`
- `GET /api/group/{sessionId}/members?cursor=&limit=`
- `PATCH /api/group/{sessionId}`
- `GET /api/group/{sessionId}/avatar/upload-url?fileName=`
- `PUT /api/group/{sessionId}/avatar`
- `GET /api/message/unread`

当前浏览器版本用 `localStorage` 模拟移动端安全存储。正式客户端接入时应将访问令牌和刷新令牌迁移到系统安全存储。

## 当前接口边界

后端已经公开会话摘要、会话详情、已读位置、未读数、群成员分页和群资料修改接口。前端在冷启动与重连后以服务端会话状态为准，在会话可见后提交最后消息 ID，并在打开群资料时同步群信息、成员角色和成员列表。群主可修改名称、公告和头像，管理员可修改公告；在线成员会通过系统事件收到最新群资料。消息发送会分别收到 `accepted`、`persisted` 或 `failed` ACK，前端只在 `persisted` 后显示“已发送”；ACK 超时后会查询 `/api/message/status`，无法确认时显示“结果待确认”，不会自动重复发送。

WebSocket 建连后，客户端通过 `/api/message/offline/sync` 循环拉取 MySQL 中的完整消息，每页合并成功后保存当前账号专属的服务端游标，直到 `hasMore` 为 false。

移动端原生连接通过 `window.chatIMCreateWebSocket(url, headers)` 注入 `Authorization: Bearer <accessToken>`。标准浏览器会先调用 `POST /api/user/ws-ticket` 获取最长 60 秒、仅可使用一次的短期 ticket，再连接服务端返回的 `nettyUri`；长期令牌不会写入 WebSocket URL。离线和历史消息接口继续通过 Gateway 访问。
