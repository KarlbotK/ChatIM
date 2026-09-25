# ChatIM 前端产品需求文档

## 1. 文档说明

| 项目 | 内容 |
|---|---|
| 产品名称 | ChatIM |
| 产品形态 | Android / iOS App，后续可复用为 Web 或桌面端 |
| 文档状态 | 需求与接口规划阶段；按 2026-09-19 当前工作区核对（含已有未提交改动） |
| 后端现状 | Gateway、UserService、RealTimeService、OfflineDataService、RedPacketService |
| 目标体验 | 以微信为体验参考的即时通信产品，不直接复制微信视觉资产 |
| 当前限制 | 单设备 token/路由模型；群管理、可靠发送确认和系统通知补拉尚未完整实现，部分接口仍通过 userId 参数识别用户 |

这份文档只描述产品、页面、交互和接口需求，不代表所有接口已经存在。接口状态会明确标记为“已有”“内部接口”“待补充”。

保留现有五个服务和 Common 的职责划分，不为前端新增独立 PushRouter 或会话微服务。“已有”表示源码已具备，仍需联调；第 9 节及完整体验属于待补充目标，不能直接视为当前后端承诺。

## 2. 产品目标

### 2.1 核心目标

1. 用户可以注册、登录、维护个人资料和头像。
2. 用户可以搜索其他用户、发送好友申请、接受或拒绝申请。
3. 用户可以进行单聊，发送文本、图片、表情和红包。
4. 用户可以创建群聊、邀请好友、查看群成员和修改群资料。
5. 用户离线后重新上线，可以看到离线期间收到的消息和系统通知。
6. 用户可以在移动端获得接近成熟 IM 产品的消息列表、未读数、会话状态和错误恢复体验。

### 2.2 非目标

第一版暂不要求：

- 音视频通话；
- 朋友圈内容生产和推荐算法；
- 复杂的群机器人平台；
- 多端同时编辑同一条消息；
- 企业级组织架构和审批流。

这些功能可以放入第二阶段或第三阶段，避免影响文字、图片、好友、群聊和红包主链路。

## 3. 平台与技术建议

### 3.1 App 技术选型

推荐使用 Flutter：

- 一套代码覆盖 Android 和 iOS；
- 图片选择、权限、推送、WebSocket、文件上传都有成熟插件；
- 页面状态和路由可以统一管理；
- 后续可以复用部分业务逻辑构建桌面端或 Web 端。

推荐技术组合：

| 层次 | 建议 |
|---|---|
| UI | Flutter Material 3，建立自己的 ChatIM 主题 |
| 网络 | Dio |
| WebSocket | web_socket_channel 或等价库 |
| 状态管理 | Riverpod 或 Bloc，二选一，不混用 |
| 本地安全存储 | flutter_secure_storage |
| 本地消息缓存 | Drift / SQLite |
| 图片选择 | image_picker |
| 图片压缩 | image_compress |
| 路由 | go_router |
| 日志 | 分级日志，生产环境禁止输出 token 和密码 |

### 3.2 客户端目录建议

```text
lib/
  app/
    app.dart
    router.dart
    theme.dart
  core/
    network/
      api_client.dart
      auth_interceptor.dart
      response.dart
    websocket/
      websocket_client.dart
      websocket_protocol.dart
    storage/
    errors/
  features/
    auth/
    chat/
    contacts/
    group/
    red_packet/
    profile/
    settings/
  shared/
    models/
    widgets/
    formatters/
```

## 4. 后端接入约定

### 4.1 HTTP 入口

前端业务 HTTP 接口统一访问 Gateway；WebSocket 使用服务端返回的 Netty 地址，MinIO 预签名上传直接访问对象存储。

本地推荐入口：

```text
http://localhost:10010
```

当前服务直连端口仅用于调试：

| 服务 | 本地端口 | 前端是否直接访问 |
|---|---:|---|
| Gateway | 10010 | 是，正式入口 |
| UserService | 8104 | 否，仅调试 |
| OfflineDataService | 8101 | 否，仅调试 |
| RedPacketService | 8103 | 否，仅调试 |
| RealTimeService HTTP | 8102 | 否 |
| Netty WebSocket | 9101 | 当前暂时直连 |

当前 Gateway 已补充 `/api/contact/**` 和 `/api/group/**` 到 UserService 的路由，红包统一使用 `/api/chat/redPacket/**`。真机调试应把 localhost 换成设备可访问的开发机地址；生产环境的 HTTP、WebSocket 和对象存储地址均需对客户端可达。

### 4.2 HTTP 请求头

普通接口使用：

```text
Access-Token: <accessToken>
```

刷新接口使用：

```text
Refresh-Token: <refreshToken>
```

移动端原生 WebSocket 握手使用：

```text
Authorization: Bearer <accessToken>
```

该握手方式用于能设置自定义 Header 的 Android/iOS 客户端，服务端暂时兼容旧版纯 token 写法。标准浏览器先用已登录 HTTP 请求调用 `POST /api/user/ws-ticket`，再把返回的一次性短期 ticket 放入 WebSocket 握手 URL；accessToken 和 refreshToken 不进入 URL。

### 4.3 通用响应格式

面向页面的 HTTP 接口通常返回以下包装；第 6.4 节的内部 ID 查询接口返回裸数组，不能套用该解析方式：

```json
{
  "code": 200,
  "data": {},
  "message": "ok"
}
```

前端网络层必须统一处理：

- 同时检查 HTTP 状态与业务码：鉴权失效（如 40100、40103）时合并并发刷新请求，只尝试一次；刷新凭证失效时回登录页。Gateway 当前也可能将系统错误包装为 HTTP 401，不能仅凭 401 无限刷新；
- `code == 200`：业务成功；
- 其他业务码：显示服务端 message，并保留页面上下文；
- 网络超时：查询可重试；发送消息保留原 clientMessageId，并按第 7.3 节处理结果未知。后端持久化幂等补齐前，不能自动重发消息或红包；
- 重复请求：前端按钮进入 loading，业务层仍要依赖后端幂等。

### 4.4 ID 与时间

- 雪花 ID 使用 `Long`，前端模型统一按 `String` 保存；当前服务端仍可能输出 JSON 数字，Web 端需服务端字符串化或无损解析，不能先转 JavaScript Number 再转字符串；
- 前端内部时间统一转成毫秒；聊天 `createdTime` 和通知 `timestamp` 已使用毫秒，部分 HTTP DTO 仍是 Java Date，联调时需确认实际序列化格式；
- 红包金额接口当前使用元，前端展示两位小数；
- 消息 `clientMessageId` 由客户端生成，用于本地发送关联，不是数据库 messageId；目前未持久化，历史/离线消息可能缺失，服务端重试幂等仍待补齐。

## 5. 页面信息架构

### 5.1 主导航

App 登录后进入四个主 Tab：

1. 聊天：会话列表和聊天详情；
2. 通讯录：好友、好友申请、群聊入口；
3. 发现：第一版展示收藏、文件、扫一扫等预留入口，未完成的功能明确显示“即将开放”；
4. 我的：个人资料、头像、设置、账号安全。

底部导航显示：

- 聊天未读总数；
- 通讯录好友申请未读数；
- 当前选中状态；
- 网络断开和同步状态。

### 5.2 页面树

```text
启动页
  ├─ 登录页
  ├─ 注册页
  ├─ 验证码登录页
  └─ 主框架
      ├─ 聊天 Tab
      │   ├─ 会话列表
      │   ├─ 单聊页
      │   ├─ 群聊页
      │   ├─ 图片预览页
      │   ├─ 红包详情页
      │   └─ 消息搜索页
      ├─ 通讯录 Tab
      │   ├─ 好友申请
      │   ├─ 好友列表
      │   ├─ 搜索用户
      │   ├─ 好友详情
      │   └─ 群聊列表
      ├─ 发现 Tab
      └─ 我的 Tab
          ├─ 个人资料
          ├─ 修改头像
          ├─ 设置
          └─ 登录设备与退出登录
```

## 6. 现有接口与页面用途

以下接口是当前代码中已经存在的公开或半公开 HTTP 接口。前端网络层要统一封装，页面只调用 Repository，不直接拼接 URL。

### 6.1 账号与登录

| 方法 | 地址 | 页面用途 | 请求数据 | 结果 |
|---|---|---|---|---|
| GET | `/api/user/sendCaptcha?targetEmail=` | 注册或验证码登录前发送验证码 | 邮箱 | 成功提示 |
| POST | `/api/user/register` | 注册页提交 | email、password、confirmPassword、code、nickname | 用户资料、双 token、nettyUri、offlineTime |
| POST | `/api/user/login/password` | 密码登录 | email、password | 用户资料、双 token、nettyUri、offlineTime |
| POST | `/api/user/login/code` | 验证码登录 | email、code | 用户资料、双 token、nettyUri、offlineTime |
| GET | `/api/user/logout` | 退出登录 | Access-Token | 清理本地 token、断开 WebSocket |
| POST | `/api/user/refresh` | Access-Token 过期后换新 token | Refresh-Token | 新双 token |
| GET | `/api/user/refresh/uri?userId=` | 连接层重新获取 WebSocket 地址 | 当前用户 ID | nettyUri；无可用实例时可能为空 |
| GET | `/api/user/uploadUrl?fileName=` | 获取头像上传地址 | 文件名 | uploadUrl、downloadUrl |
| POST | `/api/user/update/avatar` | 保存头像地址 | userId、uri | 是否成功 |

登录成功响应中的关键字段：

```json
{
  "userId": 11,
  "nickname": "单聊1",
  "avatar": "https://...",
  "accessToken": "...",
  "refreshToken": "...",
  "nettyUri": "host:port/ws/netty",
  "offlineTime": 1785587671624
}
```

前端登录成功后的顺序：

1. 安全存储 accessToken 和 refreshToken；
2. 保存当前用户资料；
3. 持久化登录返回的 `offlineTime`，根据 `nettyUri` 建立 WebSocket 并接收实时消息；
4. 从保存的离线时间或本地同步进度补拉，与实时消息按 messageId 合并；
5. 进入会话列表并显示同步状态；补拉失败保留起点，不能直接标记同步完成。

当前账号接口的问题：

- `update/avatar` 允许请求体传 `userId`，不能只相信前端传入的用户 ID；
- 缺少“当前用户资料”接口，App 重启后无法只凭 token 拉取最新资料；
- `refresh/uri` 已存在，可由连接层在原地址失效或冷启动恢复时经 Gateway 调用，页面不直接定位实例；后端仍需将 userId 与登录身份绑定。刷新 token 的响应不含 nettyUri 和 offlineTime。

### 6.2 用户头像上传流程

```text
App 请求 uploadUrl
    -> UserService 生成 MinIO 预签名 PUT 地址
    -> App 直接 PUT 图片到 MinIO
    -> App 把 downloadUrl 提交给 UserService
    -> user.avatar 更新
```

页面行为：

- 选择图片后先压缩，建议最长边不超过 2048 像素；
- 头像上传显示进度；
- MinIO PUT 成功后再更新数据库；
- 数据库更新失败时保留上传结果，允许重试保存；
- 不要把 uploadUrl 保存为头像，它是临时上传地址；
- 头像展示使用 downloadUrl。

### 6.3 好友与通讯录

| 方法 | 地址 | 页面用途 | 请求数据 | 结果 |
|---|---|---|---|---|
| GET | `/api/contact/{userId}/user/search?keyword=` | 搜索用户 | 手机号或邮箱 | FriendDetailVO |
| GET | `/api/contact/{userId}/friend` | 好友列表 | pageNum、pageSize、key | PageResponse<FriendDTO> |
| POST | `/api/contact/{userId}/friend/{receiveuserId}` | 发送好友申请 | `{ "msg": "申请说明" }` | 是否成功 |
| GET | `/api/contact/{userId}/apply` | 好友申请列表 | pageNum、pageSize | PageResponse<ApplyFriendDTO> |
| GET | `/api/contact/{userId}/applyCount` | 通讯录红点 | 无 | `{ "count": 3 }` |
| POST | `/api/contact/{userId}/application/{status}` | 接受、拒绝或标记已读 | receiveuserIds | 通过时返回会话信息 |
| DELETE | `/api/contact/{userId}/friend/{receiveuserId}` | 删除好友 | 无 | 是否成功 |
| POST | `/api/contact/{userId}/block/{receiveuserId}` | 拉黑好友 | 无 | 是否成功 |
| DELETE | `/api/contact/{userId}/block/{receiveuserId}` | 取消拉黑 | 无 | 是否成功 |
| GET | `/api/contact/{userId}/friend/{friendId}` | 好友详情 | 无 | FriendDetailVO |

好友申请状态：

| 状态 | 含义 | 前端操作 |
|---:|---|---|
| 0 | 未读 | 显示红点和“接受/拒绝”按钮 |
| 1 | 已通过 | 显示已成为好友 |
| 2 | 已拒绝 | 显示已拒绝 |
| 3 | 已读、尚未处理 | 取消红点，仍保留“接受/拒绝”按钮 |
| 4 | 已过期 | 禁止直接接受，允许重新申请 |

好友列表交互：

- 默认按昵称或后端排序展示；
- `status != 0` 的关系显示拉黑或已删除状态，不展示为正常好友；
- `sessionId` 不为空时，详情页显示“发消息”按钮；
- 点击好友头像进入好友详情；
- 删除和拉黑必须二次确认；
- 新好友申请到达时，更新通讯录 Tab 红点和申请列表。

当前好友接口的安全改造要求：后续应由 JWT 确定当前用户，不应让前端通过 URL 的 `{userId}` 决定“我是谁”。第一版前端可以按现有接口传入当前 userId，但要把这部分封装在 API 层，方便后端改为 `/api/contact/me/...`。

### 6.4 会话与群聊

| 方法 | 地址 | 页面用途 | 请求数据 | 结果 |
|---|---|---|---|---|
| POST | `/api/group` | 创建群聊 | creatorId、memberIds | sessionId、群名、默认头像、失败成员 |
| POST | `/api/group/invite` | 邀请好友入群 | sessionId、inviterId、inviteeIds | 成功列表、失败列表 |
| GET | `/api/user/get/receivers?sessionId=` | 内部 Feign 辅助接口，非页面成员接口 | sessionId | 裸用户 ID 列表，无 BaseResponse |
| GET | `/api/user/get/sessions?userId=` | 内部 Feign 辅助接口，非页面会话列表 | userId | 裸会话 ID 列表，无 BaseResponse |

创建群聊页面：

1. 从好友列表多选好友；
2. 显示已选人数和头像；
3. 点击创建后展示进度；
4. 成功后打开群聊页；
5. 失败成员显示在结果页，不阻塞已成功成员；
6. 使用 MinIO 中的默认群头像；
7. 后续增加群主上传自定义头像。

当前群聊接口还缺少完整的会话详情和权限管理，不能仅靠会话 ID 列表完成微信式聊天列表。上述两个内部接口虽然当前位于 `/api/user/**` 路由下，仍不应成为页面契约；应补充第 9 节的公开接口及会话成员权限校验。

### 6.5 离线消息与历史消息

| 方法 | 地址 | 页面用途 | 请求体 | 结果 |
|---|---|---|---|---|
| POST | `/api/message/offline` | WebSocket 重连后补拉离线消息 | userId、offlineTime | Map<sessionId, List<MessageResponse>> |
| POST | `/api/message/history` | 聊天页向上翻历史消息 | sessionId、beforeTime、limit | 消息列表 |

离线消息流程：

1. 保存登录返回的 `offlineTime`；该值在服务端登录时被读取并删除，普通 WebSocket 重连不会重新返回；
2. 先建立 WebSocket，再从保存的起点请求 `/offline`；普通重连使用本地同步进度并保留重叠区间；
3. 按 sessionId 分发到本地各会话；
4. 消息按 `createdTime` 升序合并；
5. 服务端消息按 `messageId` 去重；实时回推有 clientMessageId 时，可关联本地待发送消息；
6. 补拉结束后再将会话标记为已同步；
7. WebSocket 后续收到的消息按时间和 ID 再次去重。

当前补拉有实现边界：近 7 天主要读 Canal 异步写入的 Redis，热数据缺失时没有完整 MySQL 回源；只补拉一次或只按最后接收时间推进游标可能漏掉延迟、乱序消息。后端需补齐可靠同步游标/回源保障，前端在此之前保留补拉重试能力，不能承诺绝不丢消息。`/offline` 只返回聊天消息；系统通知目前没有存储消费与历史查询闭环，重连先刷新好友申请等业务列表。

历史消息采用游标式交互：

```json
{
  "sessionId": "1",
  "beforeTime": 1785587671624,
  "limit": 20
}
```

如果返回 20 条，就把最早一条消息的 `createdTime` 作为下一次 `beforeTime`，不要使用页码，以避免新消息插入导致翻页错位。

这是现有时间游标的兼容方式；同一时间戳下超过一页的消息仍可能被跳过，完整分页需后端补充 `(createdTime, messageId)` 联合游标。离线/历史消息暂不保证有 `clientMessageId`；红包 body 当前可能被序列化在 `body.content` 中，适配层需兼容解析，后续由 OfflineDataService 与 Canal 统一输出结构。

### 6.6 红包

| 方法 | 地址 | 页面用途 | 请求体或参数 | 结果 |
|---|---|---|---|---|
| POST | `/api/chat/redPacket/send` | 聊天页发送红包 | sessionId、receiverId、senderId、type、sessionType、body、clientMessageId | redPacketId、messageId |
| POST | `/api/chat/redPacket/receive` | 点击红包领取 | userId、redPacketId | 状态、提示、金额 |
| GET | `/api/chat/redPacket/basic?redPacketId=` | 红包气泡快速展示 | 红包 ID | 基本信息 |
| GET | `/api/chat/redPacket/?redPacketId=&pageNum=&pageSize=` | 红包详情页 | 红包 ID、分页 | 发送者、金额、领取记录 |

红包发送弹窗：

- 类型：普通红包、拼手气红包；
- 金额单位：元；
- 个数：群聊必填，单聊默认 1；
- 文案：最多限制后端允许长度；
- 发送按钮点击后立即进入 loading；
- `clientMessageId` 由 App 生成并保存；
- 请求外层 `type=3`；红包类型放在 `body.redPacketType`（0 普通、1 拼手气），同时传 totalAmount、totalCount、redPacketWrapperText；
- HTTP 成功后用返回的 redPacketId、messageId 关联本地气泡，再合并 WebSocket/历史消息；不能只等 WebSocket 回推；
- 当前服务端只有 3 秒防重复提交，未按 clientMessageId 实现持久化幂等。发送超时应显示“结果待确认”，在补齐结果查询和幂等前不得自动再次扣款发送。

红包整体状态（`/basic` 和详情）：

| status | 页面显示 |
|---:|---|
| 0 | 可领取，显示“领取” |
| 1 | 已领完，显示“已被领完” |
| 2 | 已过期，显示“红包已过期” |

领取接口 `/receive` 需单独适配：当前普通领取成功和重复领取都可能返回 `status=0`，领到最后一份时返回 `status=1` 且有金额，不存在时返回 `-1`；实际实现没有返回 `status=3`。不可把 `status=1` 一律解释为本次领取失败，也不能由整体状态推断当前用户已领取。第一版展示返回金额、提示和领取记录；明确的“本次领取结果/已领取”字段属于后端待补充项。

当前红包请求体仍包含 `senderId` 和 `userId`，正式产品必须由后端从 JWT 读取当前用户，避免用户伪造他人身份发红包或领取红包。

## 7. WebSocket 产品链路

### 7.1 建立连接

```text
登录成功
  -> 保存 accessToken
  -> 读取 login.data.nettyUri
  -> 按部署实际支持的协议补全 ws:// 或 wss://
  -> 移动端 Header: Authorization: Bearer accessToken
  -> 浏览器 POST /api/user/ws-ticket 后携带一次性 ticket
  -> 连接 /ws/netty
  -> 服务端验证 JWT 和 Redis 中的 accessToken
  -> App 发送心跳
```

当前 `nettyUri` 返回 `host:port/ws/netty`，来自登录或 `/api/user/refresh/uri`，不能固定写死 9101。后端已从 Nacos metadata 读取 Netty 端口；部署还需保证地址对客户端可达，WSS 需实际 TLS 入口支持。当前已实现第一版 RealTimeService 多实例路由，前端不负责选择 Kafka Partition 或实例；旧地址不可用时由连接层重新获取地址。

后端多实例方案需要由服务端完成：

```text
方案 A（当前）：Kafka -> 任意 RealTimeService -> Redis 查询用户所在实例 -> 本机推送或转发
方案 B（后续可选）：Kafka -> PushRouter -> Redis 查询用户所在实例 -> 目标 RealTimeService 推送
```

后端当前维护 `ws:route:{userId} -> instanceId|channelId`，默认 TTL 为 60 秒。前端使用服务端返回的 `nettyUri`，处理连接断开、重连和离线补拉；不要在客户端根据 Kafka、Partition 或实例名自行分配连接。当前按单设备模型接入，多端登录需要后端同时改造 token、路由和旧连接下线机制。

### 7.2 连接状态

客户端维护：

```text
disconnected -> connecting -> connected -> reconnecting -> connected
                                      -> authFailed
```

要求：

- 网络断开时指数退避重连，建议 1、2、4、8、16 秒，上限 30 秒；
- 心跳使用文本帧 `ping`，服务端回复文本 `pong`；协议控制帧 Ping 不能替代路由续期。默认 60 秒 TTL 下可每 20 秒发送一次，具体间隔随后端配置调整；
- App 后台暂停心跳或间隔超过 TTL 后，回到前台重新握手再补拉。路由已过期时，旧连接仅发送 ping 不会重新登记路由；
- 连接失败不能无限弹窗，页面顶部显示轻量网络状态；
- 重连成功后补拉聊天消息并刷新好友申请；未读同步接口补齐前使用本地未读数；
- 发送中的消息保留在本地，不能因为 WebSocket 断开直接丢失。

### 7.3 消息结构

普通聊天上行使用以下结构；单聊 receiverId 必填，群聊 receiverId 置空。senderId 当前仍由请求携带，后端需绑定到已鉴权 Channel 用户并校验会话权限，不能把握手通过视为发送身份已校验。红包发送走第 6.6 节 HTTP 接口：

```json
{
  "sessionId": "1",
  "receiverId": "12",
  "senderId": "11",
  "type": 0,
  "sessionType": 0,
  "clientMessageId": "client-uuid",
  "body": {
    "content": "你好",
    "replyId": null,
    "redPacketId": null,
    "redPacketWrapperText": null
  }
}
```

消息类型：

| type | 含义 | UI 组件 |
|---:|---|---|
| 0 | 文本 | 文本气泡 |
| 1 | 图片 | 图片气泡和预览 |
| 2 | 表情 | 表情气泡 |
| 3 | 红包 | 红包卡片 |
| 101 | 好友申请通知 | 通讯录通知卡片 |
| 102 | 新单聊通知 | 新会话提示 |
| 103 | 新群聊通知 | 群聊邀请提示 |
| 104 | 群成员移除通知（已有常量，业务推送待实现） | 系统提示 |

系统通知与聊天消息分别解析：通知使用 `timestamp` 和字符串 `messageId`，`sessionId`/`sessionType` 可空、body 随类型变化，不能强行套用普通 Message。聊天下行 `MessageResponse` 不包含 receiverId，clientMessageId 也不保证在补拉时存在。

当前后端没有统一的发送确认协议，也没有按 clientMessageId 的持久化重试幂等。前端第一版使用以下策略：

- 本地发送状态：sending、sent、unknown、failed；超时记为 unknown（结果待确认），不直接认定失败；
- 使用 `clientMessageId` 关联输入框消息和实时回推；当前回推后可标记 sent，但仅表示已回推，不表示已持久化或对方已读；
- 断线保留待确认消息，先补拉核对，后端幂等完成前不自动重发；
- 后端幂等与原结果查询补齐后，重试必须复用同一个 clientMessageId；
- 同时用 `messageId` 去重；
- 后续由 RealTimeService 经 WebSocket 返回 `message-ack`，接收状态与持久化状态分开；持久化结果由 OfflineDataService 通过内部事件反馈。

## 8. 页面详细需求

### 8.1 启动页

职责：

- 读取本地 token；
- 检查 token 是否存在；
- 进入登录页或主框架；
- 显示最短必要的启动状态，不做营销型大图；
- 版本升级时支持强制升级和稍后提醒。

### 8.2 登录与注册页

登录页：

- 邮箱输入；
- 密码登录；
- 验证码登录切换；
- 忘记密码入口预留；
- 登录按钮防重复点击；
- 服务器错误显示在表单下方；
- 成功后不可回到登录页重复提交。

注册页：

- 邮箱、验证码、密码、确认密码、昵称；
- 发送验证码按钮 60 秒倒计时；
- 密码强度提示；
- 隐私协议和用户协议勾选；
- 注册成功直接进入主框架。

### 8.3 会话列表页

每个会话项包含：

- 会话头像；
- 会话名称；
- 最后一条消息摘要；
- 最后消息时间；
- 未读数；
- 免打扰标记；
- 置顶状态；
- 草稿标记；
- 发送失败标记。

手势和菜单：

- 左滑置顶；
- 左滑标记未读；
- 左滑删除或隐藏会话；
- 长按进入多选；
- 点击进入聊天详情。

当前后端缺少会话列表聚合接口，因此前端不能只依赖 `/get/sessions`。需要新增会话摘要接口。

### 8.4 单聊页

顶部：

- 好友昵称；
- 在线状态；
- 进入好友详情；
- 更多菜单：搜索聊天、置顶、免打扰、清空记录、删除好友。

消息区域：

- 分页向上加载历史消息；
- 新消息自动滚动到底部；
- 用户正在阅读旧消息时不强制跳到底部；
- 显示“有新消息”浮层；
- 时间分割线；
- 消息发送状态；
- 长按复制、回复、转发、撤回、删除；
- 图片点击查看大图；
- 红包点击领取。

底部输入区：

- 文本输入；
- 表情按钮；
- 图片选择；
- 红包按钮；
- 语音按钮预留；
- 键盘弹出时布局不跳动；
- 输入框内容支持草稿。

### 8.5 群聊页

顶部：

- 群头像、群名称、成员数量；
- 群设置入口；
- 群成员头像预览；
- 网络和同步状态。

群设置页：

- 群头像；
- 群名称；
- 群公告；
- 群成员管理；
- 邀请好友；
- 群主转让；
- 管理员管理；
- 全员禁言；
- 消息免打扰；
- 退出群聊；
- 群主解散群聊。

权限展示：

| 角色 | 可执行操作 |
|---|---|
| 群主 | 修改群资料、邀请、移除成员、设置管理员、转让、解散 |
| 管理员 | 按后端规则邀请、移除普通成员、管理公告 |
| 普通成员 | 查看群资料、邀请可选好友、退出群聊 |

当前只有群创建和邀请接口，以上其他操作属于待补充接口。

### 8.6 通讯录页

分区：

- 新的朋友；
- 群聊；
- 好友列表；
- 黑名单；
- 搜索入口。

好友申请卡片：

- 头像、昵称、申请说明、时间；
- 接受、拒绝按钮；
- 已处理和已过期状态；
- 批量标记已读；
- 申请成功后显示“发消息”。

好友详情页：

- 头像、昵称、签名、性别；
- 添加好友或发消息；
- 设置备注；
- 拉黑、删除；
- 进入共同会话。

### 8.7 我的与设置

个人资料页：

- 头像；
- 昵称；
- 性别；
- 个性签名；
- 邮箱；
- 用户 ID；
- 修改资料入口。

设置页：

- 新消息通知；
- 声音和震动；
- 深色模式；
- 清理缓存；
- 网络诊断；
- 账号安全；
- 退出登录。

## 9. 必须补充的后端接口

### 9.1 P0，基础链路接口（按版本启用）

账号、会话摘要、消息确认/幂等及补拉保障是 V0.1 的联调重点；完整群管理接口在 V0.2 启用，不要求第一轮一次实现全部群管理。

| 接口 | 用途 | 建议返回 |
|---|---|---|
| GET `/api/user/me` | 根据 JWT 获取当前用户 | UserProfileVO |
| PATCH `/api/user/me` | 修改昵称、性别、签名 | UserProfileVO |
| GET `/api/session/list` | 获取会话摘要列表 | 会话 ID、类型、名称、头像、最后消息、未读数 |
| GET `/api/session/{sessionId}` | 获取会话详情 | 会话资料和当前用户权限 |
| POST `/api/session/{sessionId}/read` | 标记会话已读 | unreadCount=0 |
| GET `/api/group/{sessionId}/members` | 分页获取群成员 | 成员资料、角色、状态 |
| GET `/api/group/{sessionId}/avatar/upload-url` | 群主获取群头像上传地址 | uploadUrl、downloadUrl、objectName |
| PUT `/api/group/{sessionId}/avatar` | 群主保存群头像 | 最新头像地址 |
| POST `/api/group/{sessionId}/leave` | 普通成员退出群聊 | 是否成功 |
| DELETE `/api/group/{sessionId}/members/{userId}` | 群主或管理员移除成员 | 是否成功 |
| PATCH `/api/group/{sessionId}` | 修改群名称、公告 | 群详情 |
| WebSocket `message-ack`（待实现） | RealTimeService 主动返回发送结果 | messageId、clientMessageId、接收/持久化状态；失败错误码 |
| GET `/api/message/unread` | 获取各会话未读数 | Map<sessionId, count> |

这些接口中的当前用户都应从 JWT 获取，不能依赖请求体中的 userId。

服务归属沿用现有架构：`/api/session/**` 和群成员管理归 UserService，消息历史与查询归 OfflineDataService；待新增的已读位置建议由 UserService 持有，未读查询按该位置聚合。通知历史建议由 UserService 补齐独立存储和查询。新增 `/api/session/**`、`/api/notification/**` 时同步补 Gateway 路由。发送 ack 是服务端结果，不能由客户端 POST 一个确认来代替落库证明；接收回执、已读回执需另定协议。

### 9.2 P1，影响微信式体验

| 接口 | 用途 |
|---|---|
| PATCH `/api/contact/friend/{friendId}/remark` | 设置好友备注 |
| GET `/api/contact/blacklist` | 黑名单列表 |
| POST `/api/session/{sessionId}/pin` | 置顶或取消置顶 |
| POST `/api/session/{sessionId}/mute` | 免打扰 |
| DELETE `/api/session/{sessionId}` | 隐藏或删除会话 |
| POST `/api/message/{messageId}/recall` | 撤回消息 |
| DELETE `/api/message/{messageId}` | 删除自己的消息 |
| GET `/api/message/search` | 搜索聊天记录 |
| POST `/api/group/{sessionId}/transfer-owner` | 转让群主 |
| POST `/api/group/{sessionId}/admins` | 设置管理员 |
| DELETE `/api/group/{sessionId}/admins/{userId}` | 取消管理员 |
| PUT `/api/group/{sessionId}/announcement` | 修改群公告 |
| GET `/api/notification/list` | 查看系统通知历史 |
| POST `/api/notification/read` | 标记系统通知已读 |
| GET `/api/user/presence/{userId}` | 查看在线状态 |

### 9.3 P2，增强功能

| 接口 | 用途 |
|---|---|
| POST `/api/message/forward` | 转发消息 |
| POST `/api/message/reply` | 回复消息 |
| POST `/api/message/reactions` | 消息表情回应 |
| POST `/api/group/{sessionId}/polls` | 群投票 |
| POST `/api/call/session` | 音视频通话信令 |
| GET `/api/file/{objectName}/download-url` | 私有文件临时下载地址 |
| POST `/api/device/register` | 推送设备注册 |
| GET `/api/device/list` | 登录设备管理 |

## 10. 数据模型要求

前端至少建立以下模型：

### UserProfile

```text
userId: String
email: String?
nickname: String
avatar: String?
gender: Int
description: String?
```

### Conversation

```text
sessionId: String
type: 0 | 1
name: String
avatar: String?
lastMessage: Message?
lastMessageTime: int?
unreadCount: int
isPinned: bool
isMuted: bool
```

### Message

```text
messageId: String?
clientMessageId: String?  // 本地待发送消息必填；历史/离线可能缺失
sessionId: String
senderId: String
receiverId: String?
type: int
sessionType: 0 | 1
createdTime: int
body: MessageBody
sendStatus: sending | sent | unknown | failed
```

### GroupMember

```text
userId: String
nickname: String
avatar: String?
role: 0 | 1 | 2
status: 0 | 1
```

## 11. 状态、缓存与离线策略

### 11.1 本地持久化

安全存储：

- accessToken；
- refreshToken；
- 当前用户 ID；
- 当前环境地址。

本地数据库存储：

- 最近会话；
- 最近消息；
- 各账号的补拉起点、同步进度和待确认发送请求（含 clientMessageId）；
- 草稿；
- 未读数；
- 好友列表缓存；
- 群成员缓存。

不保存：

- 明文密码；
- 验证码；
- WebSocket Authorization Header；
- MinIO 的预签名 uploadUrl。

### 11.2 消息合并规则

离线消息、历史消息、WebSocket 实时消息可能重复或乱序。统一规则：

1. 优先按 messageId 去重；
2. 本地待发送消息按当前账号 + clientMessageId 关联实时回推；缺少两种 ID 时不按内容强行去重；
3. 显示按 createdTime 排序；
4. 相同时间按 messageId 或本地接收序号排序；
5. 发送失败消息不覆盖服务端已经确认的消息。

### 11.3 未读数

当前后端还没有完整的未读数接口，前端第一版可以本地累计，但正式版本必须以后端为准：

- 每个会话保存 lastReadMessageId 或 lastReadTime；
- 进入会话时提交已读；
- 收到消息但不在当前会话时增加未读数；
- WebSocket 重连后重新同步未读数。

## 12. 交互与视觉要求

### 12.1 整体风格

- 移动端优先，单手操作友好；
- 信息密度接近成熟聊天 App；
- 主色使用清晰的绿色或蓝绿色，但不大面积铺色；
- 页面以内容和操作为主，不使用营销型 Hero 页面；
- 卡片圆角保持克制，普通列表不套多层卡片；
- 重要操作使用图标加文字，危险操作使用明确的二次确认；
- 所有图标提供语义化 tooltip 或无障碍标签；
- 深色模式和浅色模式都要保证文本对比度。

### 12.2 反馈状态

每个异步操作都必须考虑：

- 初始状态；
- 加载状态；
- 空状态；
- 成功状态；
- 业务失败状态；
- 网络失败状态；
- 重试状态；
- 权限不足状态。

不能只实现 HTTP 200 的页面。尤其是好友申请、红包领取、图片上传和 WebSocket 重连。

## 13. 关键用户流程验收

### 13.1 登录上线

1. 用户使用密码或验证码登录；
2. App 保存双 token；
3. 持久化登录返回的 offlineTime；
4. App 先连接 WebSocket，再按保存起点补拉并合并消息；
5. 进入会话列表并展示同步状态，失败不丢弃补拉起点；
6. 断网后自动重连；
7. Access-Token 过期时刷新并重新连接；
8. Refresh-Token 也过期时回登录页。

### 13.2 单聊发送文字

1. 用户打开好友会话；
2. 客户端生成 clientMessageId；
3. 消息立即显示为 sending；
4. 通过 WebSocket 发送；
5. 收到实时回推后变为 sent；可靠持久化状态需等待后端 ack 协议补齐；
6. 超时显示结果待确认，确定失败后按错误反馈；
7. 后端持久化幂等完成后验证“重试不得产生两条相同消息”；此前不自动重发；
8. 对方离线时，对方上线后能在离线消息中看到。

### 13.3 好友申请

1. 搜索用户；
2. 查看用户详情；
3. 输入申请说明并发送；
4. 接收方收到系统通知；
5. 通讯录红点增加；
6. 接受后双方建立单聊会话；
7. 前端刷新好友列表和会话列表；
8. 拒绝、已读、过期状态展示正确。

### 13.4 群聊

第 1—4 项属于第一轮基础群聊；第 5—8 项依赖 V0.2 的详情、成员和群管理接口。

1. 从好友列表多选成员；
2. 创建群聊；
3. 所有成功成员收到新群聊通知；
4. 群主可邀请更多好友；
5. 群成员可以看到群头像、群名和成员数；
6. 群主修改头像后，成员收到更新通知；
7. 普通成员不能执行群主管理操作；
8. 退出群聊后不再收到该群消息。

### 13.5 红包

1. 用户在单聊或群聊输入红包信息；
2. 前端校验金额、个数和会话类型；
3. 点击发送后按钮锁定；
4. 红包消息通过 WebSocket 展示；
5. 接收方点击领取；
6. 领取成功、已领取、已领完、已过期分别展示；
7. 红包详情页展示领取人和金额；
8. 后端补齐持久化幂等、账务与事件补偿后验证网络重试不重复扣款或入账；此前发送超时不自动重试，领取展示当前查询结果。

## 14. 版本路线

### V0.1，跑通后端

- 登录、注册、验证码；
- 单聊文本；
- 好友搜索和好友申请；
- 离线消息；
- 会话列表基础版；
- 基础群创建和邀请；
- 图片上传；
- 红包；
- App 本地 token 和 WebSocket 重连。

### V0.2，完整群聊（基于 V0.1 创建/邀请能力）

- 群成员列表；
- 群头像和群名称；
- 群主、管理员、普通成员权限；
- 退出、移除、解散；
- 群消息未读数。

### V0.3，接近成熟 IM

- 会话置顶和免打扰；
- 消息撤回、删除、搜索、回复；
- 好友备注和黑名单页；
- 系统通知历史；
- 多端登录和设备管理；
- 推送通知。

### V1.0，增强产品

- 语音和视频通话；
- 文件传输；
- 消息表情回应；
- 群投票和群公告；
- 多端同步；
- 监控、埋点、崩溃上报和灰度发布。

## 15. 前端开始开发前必须确认的后端事项

1. 确认 `nettyUri` 返回真实 WebSocket 端口；
2. HTTP 保留 Access-Token / Refresh-Token 兼容，WebSocket 移动端使用 Bearer Header，浏览器使用一次性短期 ticket；
3. HTTP 操作者身份取自 JWT、WebSocket 发送者取自已鉴权 Channel，并校验会话权限；
4. 增加会话摘要、会话详情和同步保障；服务端未读数启用前明确本地统计限制；
5. V0.2 再补齐群成员、群头像、群设置和退出群聊接口；
6. 补齐持久化幂等、WebSocket 发送确认与已读接口，明确超时结果查询方式；
7. 确认图片对象的公开访问策略，生产环境不要把 localhost 地址写入数据库；
8. 按当前方案使用 RealTimeService 消费后查询 Redis 路由并转发；前端只依赖稳定的 `nettyUri` 和重连/离线补拉协议，暂不增加 PushRouter；
9. 明确消息重复、重试和离线补拉的幂等规则；
10. 为所有公开接口补充统一错误码和接口文档。

## 16. 第一轮前端交付标准

第一轮不追求页面数量，而是要求一条链路完整：

以下是联调交付目标；会话摘要、发送确认/幂等和可靠补拉等缺口按第 9、15 节补齐后验收，不能把客户端页面完成当作后端能力已经完成。

- 能注册并登录；
- 能进入主页面；
- 能建立 WebSocket；
- 能看到会话列表；
- 能发送和接收文本；
- 能断线重连；
- 能补拉离线消息；
- 能搜索用户并添加好友；
- 能创建群聊并邀请好友；
- 能上传头像；
- 能发送和领取红包；
- 所有失败操作都有清晰反馈；
- App 杀掉重启后，token、会话和消息状态可恢复。

这条链路稳定后，再逐步补全群管理、消息管理、设置和发现页。
