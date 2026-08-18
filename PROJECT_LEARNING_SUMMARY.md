# InfiniteChat 项目学习与架构总结

> 这份文档把前面讨论过的概念，和当前项目中的代码、端口、topic、Redis key 以及业务链路对应起来。
>
> 当前代码基线：2026-08-18。文档中的配置和类名以仓库现状为准；本地密码、JWT 密钥、邮箱密码等敏感配置不要写进 Git，应使用环境变量。

## 1. 先记住项目全貌

InfiniteChat 是一个 Spring Boot 多模块项目，当前根 POM 中有 6 个模块：

| 模块 | 主要职责 | 主要中间件或技术 |
|---|---|---|
| `Common` | 公共实体、DTO、VO、常量、JWT、统一返回、异常、AOP | MyBatis-Plus、Redis、JWT、Spring AOP |
| `Gateway` | HTTP API 统一入口、路由、CORS、服务发现负载均衡 | Spring Cloud Gateway、Nacos |
| `UserService` | 用户、登录、好友、好友申请、会话、群聊、内部校验、上传地址 | MySQL、MyBatis-Plus、Redis、Kafka、Nacos、MinIO、ShedLock |
| `RealTimeService` | Netty WebSocket 连接、鉴权、收发消息、在线系统通知 | Netty、Redis、Kafka、OpenFeign、Nacos |
| `OfflineDataService` | 消息落 MySQL、离线/历史消息查询、Canal 同步热消息到 Redis | MySQL、Redis、Kafka、Canal、OpenFeign、Nacos |
| `RedPacketService` | 红包创建、余额扣减、抢红包、过期退款、红包消息 | MySQL、Redis、Kafka、OpenFeign、Resilience4j/Spring Cloud CircuitBreaker、ShedLock |

当前本地配置中的主要端口是：

| 服务 | Spring Boot HTTP 端口 | 说明 |
|---|---:|---|
| Gateway | `10010` | 浏览器/HTTP 客户端访问的统一 API 入口 |
| UserService | `8104` | 用户业务 HTTP 接口，同时被 OpenFeign 调用 |
| OfflineDataService | `8101` | 离线和历史消息 HTTP 接口 |
| RealTimeService | `8102` | Spring Boot HTTP 端口，供服务注册和其他 HTTP 调用 |
| RealTimeService Netty | `9101` | 真正监听 WebSocket 的端口 |
| RedPacketService | `8103` | 红包 HTTP 接口 |
| Nacos | `18375` 映射到容器 `8848` | 服务注册与发现 |
| Redis | `6379` | Redis 默认端口 |
| MySQL | `3306` | MySQL 默认端口；如果使用 Docker 映射，可是宿主机上的其他端口 |
| Kafka | `9092` | Kafka 常见默认端口 |
| Canal | `11111` | Canal Server 常见端口 |
| MinIO API / Console | `9000` / `9090` | 对象存储 API / 管理控制台 |

最关键的端口结论：`server.port: 8102` 只是 RealTimeService 的 Spring HTTP 端口，Netty 在 `NettyService.java` 中另有 `private final int port = 9101`，因此 WebSocket 地址是类似 `ws://localhost:9101/ws/netty`。Nacos 通常注册的是 `8102`，不会自动把独立的 `9101` 当成服务端口。

## 2. 一次消息的完整链路

### 2.1 建立 WebSocket

WebSocket 是一种通信协议，不是一个独立的服务，也不是 Netty 本身。Netty 是一个网络编程框架，本项目用它实现 WebSocket 服务。

连接流程大致是：

```text
客户端登录 UserService
    -> 得到 accessToken
    -> 连接 ws://localhost:9101/ws/netty
    -> Header: Authorization: accessToken
    -> Netty HttpServerCodec 解析 HTTP 握手
    -> WebSocketAuthHeader 校验 JWT，并和 Redis 中的 access token 比较
    -> ChannelManager 建立 userId <-> Channel 映射
    -> WebSocketServerProtocolHandler 把 HTTP 升级成 WebSocket
    -> WebSocketHandler 开始处理聊天帧
```

`Channel` 表示一条网络连接；可以把它理解为“用户和某个 Netty 实例之间的通信通道”。它不是两个用户之间直接建立的通道。两个用户的聊天消息，实际上是：

```text
用户 A <-> A 的 WebSocket Channel <-> RealTimeService/Netty
                                           |
                                           v
                                      Kafka 消息链路
                                           |
                                           v
用户 B <-> B 的 WebSocket Channel <-> RealTimeService/Netty
```

`ChannelPipeline` 是这条 Channel 上的处理链。当前大致是：

```text
IdleStateHandler
 -> HttpServerCodec
 -> HttpObjectAggregator
 -> WebSocketAuthHeader
 -> WebSocketServerProtocolHandler
 -> WebSocketHandler
```

每个 handler 负责一类事情。连接断开时，`channelInactive` 应负责清理映射，`handlerRemoved` 可以作为兜底或记录日志。心跳是为了发现连接是否还活着；Netty 的 `IdleStateHandler` 负责检测空闲，客户端或服务端仍需要有一端定期发送 ping/业务心跳，Apifox 手动发只是测试方式。

### 2.2 收到一条普通聊天消息

`WebSocketHandler` 收到文本帧后，会解析成 `MessageRequest`，补充消息 ID、创建时间等字段，然后发送到两个 Kafka topic：

```text
WebSocketHandler
    |-- store-topic   -> OfflineDataService ConsumerOfflineService -> MySQL message
    |
    `-- message-topic -> RealTimeService ConsumerMessageService -> 找到接收者 Channel -> writeAndFlush
```

这两个 topic 的职责不同：

- `store-topic` 是存储链路，最终进入 MySQL。
- `message-topic` 是实时推送链路，最终写入接收者的 WebSocket Channel。
- Kafka 不是发送到用户 B 的网络工具；Kafka 只负责在服务内部传递任务。真正把数据交给用户 B 的最后一步，是 B 所在 Netty 实例上的 `channel.writeAndFlush(...)`。

如果没有 Kafka，也可以由 WebSocketHandler 直接调用存储服务、直接调用推送逻辑，但接收、存储、推送会同步耦合在一起。Kafka 的价值是缓冲流量、异步解耦、允许消费者独立扩展，并保留消息一段时间供失败重试；代价是链路变长、最终一致性和重复消费需要自己处理。

### 2.3 在线和离线消息

在线判断本质上是看 RealTimeService 的 Channel 是否存在且 active。离线不是 Kafka 停止消费，而是：

1. Kafka 消费者仍然消费消息。
2. 如果接收者没有 active Channel，消息不会直接推送。
3. 普通聊天消息已经通过 `store-topic` 持久化到 MySQL。
4. MySQL 的 message 表变更被 Canal 监听，CanalClient 把新消息同步到 Redis 的 `session:{sessionId}` ZSet。
5. 用户下次登录时，UserService 从 `user:offline:{userId}` 取出并删除离线时间，客户端携带这个时间请求离线消息。
6. OfflineDataService 根据用户的会话列表，查询离线时间之后的消息：最近 7 天优先查 Redis，更早的数据查 MySQL。

因此，“离线后上线收到消息”不是 Kafka 直接把消息推给用户，而是“数据库/Redis 保存消息 + 用户上线后主动补拉”。

系统通知还有一条类似链路：

```text
UserService -> system-notification-topic -> RealTimeService SystemNotificationConsumer
                                              |-- 在线：直接 writeAndFlush
                                              `-- 离线：转发到 store-notification-topic
```

当前需要注意：`store-notification-topic` 在现有代码中能看到生产逻辑，但没有像普通聊天 `store-topic` 那样明显的持久化消费者和补拉实现。也就是说，系统通知的“转发到持久化 topic”不等于已经完成了完整持久化链路，后续需要确认或补充对应消费者、数据库表和上线补拉逻辑。

## 3. Netty、NIO、EventLoop 和线程模型

### 3.1 IO 和 NIO

- IO 是输入/输出，例如从网卡读取字节、向网卡写字节。
- 阻塞 IO：线程调用读取后一直等待，数据没来之前不能去做其他事情。
- 非阻塞 IO：调用后可以先返回，线程通过事件通知或轮询处理已经就绪的连接。
- NIO 是 Java 的 New IO，核心思想是一个线程借助 Selector 监听多个 Channel 的就绪事件，也就是 IO 多路复用。

“管理很多连接”本身不自动等于“非阻塞”。关键是线程不会为每个连接永久阻塞在 read 上，而是等待多个连接的事件集合。

### 3.2 Netty 组件

- `ServerBootstrap`：服务器启动器，配置线程组、Channel 类型、子 Channel 初始化器。
- `bossGroup`：接收客户端新连接。
- `workerGroup`：处理已经建立连接上的读写事件。
- `EventLoopGroup`：一组 EventLoop 的集合；bossGroup 和 workerGroup 都是 EventLoopGroup，只是职责不同。
- `EventLoop`：事件循环线程，通常负责一组 Channel 的 IO 事件和 handler 执行。
- `Channel`：连接本身。
- `ChannelPipeline`：Channel 上的 handler 链。
- `ChannelHandler`：处理入站或出站事件。
- `writeAndFlush`：把消息交给出站 pipeline，编码后写入网络并尽快刷新。

NIO 和“串行无锁”不矛盾：NIO 解决线程如何高效等待许多连接；Netty 常让同一个 Channel 的事件由同一个 EventLoop 串行执行，减少多个线程同时修改同一连接状态的需要，因此很多地方不需要额外加锁。它们解决的是不同层面的问题。

## 4. JWT、Access Token、Refresh Token 和 WebSocket 鉴权

项目当前是“自定义 JWT 登录体系”，不是完整的 OAuth2/OIDC 授权服务器实现。

- Access Token：短期访问凭证，代码默认 30 分钟。
- Refresh Token：较长期的换新凭证，代码默认 7 天。
- JWT 的 `sub` 保存 userId，`iat` 是签发时间，`exp` 是过期时间。
- 登录时 UserService 生成两个 token，并写入 Redis：
  - `access:token:{userId}`
  - `refresh:token:{userId}`
- HTTP 请求可以通过 Gateway 的鉴权过滤器校验。
- WebSocket 不一定经过 Gateway，因此 `WebSocketAuthHeader` 自己读取 Authorization Header，解析 JWT，再和 Redis 中保存的 access token 比较。

JWT 负责“内容可验证和过期时间”，Redis 中的 token 记录负责“服务端可撤销和只允许当前 token”。只解析 JWT 不查 Redis，用户退出登录后旧 JWT 可能仍然在有效期内；两者一起用可以主动失效。

OAuth2 是授权框架，OIDC 是在 OAuth2 之上增加身份认证和 ID Token 的协议。本项目可以借鉴 access/refresh token 的思想，但目前没有完整的授权码、客户端注册、scope、OIDC Provider 等标准流程。

## 5. Gateway、Nacos、OpenFeign 和负载均衡

### 5.1 Gateway 做什么

Gateway 是 HTTP 请求的统一入口。当前路由类似：

```text
/api/user/**    -> lb://UserService
/api/message/** -> lb://OfflineDataService
```

`lb://UserService` 的意思不是访问固定的 `localhost:8104`，而是根据服务名从注册中心找 UserService 实例，再由 Spring Cloud LoadBalancer 选一个实例。

### 5.2 Nacos 做什么

Nacos 负责服务注册与发现：

```text
UserService 启动 -> 注册名字 UserService、IP、HTTP 端口 8104
Gateway 请求     -> 询问 Nacos：UserService 有哪些实例
Gateway          -> 选一个实例并转发
```

Nacos 不是 Gateway。Gateway 负责接收和转发，Nacos 负责保存“有哪些服务实例、它们在哪”。

### 5.3 OpenFeign 做什么

OpenFeign 把跨微服务 HTTP 调用包装成 Java 接口。例如 RedPacketService 的 `UserServiceClient` 声明了用户状态、群成员、好友关系等内部接口。调用 Java 方法时，Feign 根据 `@FeignClient(name = "UserService")`、`@GetMapping`、`@RequestParam` 等信息组装 HTTP 请求，再通过服务发现找到 UserService。

直接 import `ErrorCode` 是编译期共享同一个 Common JAR；OpenFeign 调用的是另一个进程，内存不共享，所以必须经过 HTTP、序列化和反序列化。

### 5.4 负载均衡和一致性哈希

普通负载均衡可以轮询或随机选择实例，让请求分摊到多台机器。项目中的 `UserService/loadbalancer` 目录有自定义一致性哈希相关代码，思路是：

```text
实例地址 -> 哈希环节点
userId   -> 哈希环上的点
沿顺时针找到第一个实例 -> 负责该 userId
```

它适合希望同一用户尽量固定到同一实例的场景。虚拟节点可以缓解所有用户集中到一个实例的问题。当前这套自定义选择逻辑主要用于根据 Nacos 实例寻找 Netty 地址，不能自动改变 Gateway 的标准负载均衡行为，也不能解决 ChannelManager 只存在于单机内存的问题。

### 5.5 当前项目是否已经是完整分布式

项目已经有分布式设计元素：Nacos 服务发现、Gateway 负载均衡、Kafka 集群式消费模型、共享 Redis、ShedLock、OpenFeign。但本地每个服务通常只启动一个实例，因此还没有真正验证横向扩展。

要多实例运行，至少需要：

1. 为同一个服务启动多个进程，使用不同 HTTP 端口和不同 `SNOWFLAKE_WORKER_ID`。
2. 每个实例注册同一个 Nacos service name。
3. Gateway 通过 `lb://ServiceName` 进行 HTTP 分流。
4. Kafka 消费者使用相同 groupId，让一个 partition 同时只由一个消费者实例处理。
5. 共享 MySQL、Redis、Kafka、Nacos，而不是每个实例各自使用本地中间件。
6. WebSocket 需要额外设计入口：使用支持 WebSocket 的网关、反向代理和粘性路由，或维护用户到 Netty 实例的可共享路由表，并让跨实例推送能够找到目标连接。

当前 `ChannelManager` 是静态 `ConcurrentHashMap`，只在当前 RealTimeService JVM 内有效，所以“HTTP 服务可以直接横向扩展”和“WebSocket 连接已经完成多实例扩展”不是一回事。

## 6. Kafka：topic、partition、groupId 和当前 topic

### 6.1 Kafka 是什么

Kafka 是分布式消息日志系统。可以先这样理解：

- Topic：消息分类的名字，例如 `store-topic`。
- Partition：topic 内的分片，消息实际按分区顺序保存。
- Producer：生产消息的一方。
- Consumer：读取消息的一方。
- Consumer Group：一组协作消费者；同一个 group 内，一个 partition 在同一时刻通常只交给一个消费者处理。
- Offset：消费者在某个 partition 读到的位置。
- Key：决定消息分区；相同 key 通常进入同一 partition，从而保持该 key 的局部顺序。

`concurrency = "3"` 表示这个监听器在应用内尝试启动 3 个并发消费线程，但最终并行度还受 topic partition 数限制。一个 groupId 的作用是让多个实例分摊消息，而不是让每个实例都收到一份。

### 6.2 当前业务 topic

| Topic | 生产者 | 消费者 | 用途 |
|---|---|---|---|
| `store-topic` | RealTimeService WebSocketHandler、红包消息统一发送入口 | OfflineDataService `ConsumerOfflineService` | 普通聊天/红包消息落 MySQL |
| `message-topic` | RealTimeService WebSocketHandler、红包消息统一发送入口 | RealTimeService `ConsumerMessageService` | 实时推送到接收者 Channel |
| `system-notification-topic` | UserService `NotificationServiceImpl` | RealTimeService `SystemNotificationConsumer` | 好友申请、新会话、新群聊等系统通知 |
| `store-notification-topic` | RealTimeService `SystemNotificationConsumer` 在接收者离线时转发 | 当前代码中需继续确认完整消费者 | 系统通知持久化链路 |
| `friend-request-creation-topic` | UserService `ApplyFriendServiceImpl` | UserService `FriendRequestExpirationEnqueuer` | 注册好友申请过期任务 |
| `friend-request-expiration-topic` | UserService `FriendRequestExpirationDispatcher` | UserService `FriendRequestExpirationExecutor` | 执行好友申请过期更新 |
| `redpacket-creation-topic` | RedPacketService 创建红包 | RedPacketService `DelayTaskEnqueuer` | 注册红包 24 小时过期任务 |
| `topic-redpacket-receive` | 当前代码预留 | 当前代码中需确认完整生产/消费实现 | 红包领取事件预留主题 |
| `topic-redpacket-completed` | 当前代码预留 | 当前代码中需确认完整生产/消费实现 | 红包领完事件预留主题 |
| `redpacket-expiration-topic` | RedPacketService `ExpirationDispatcher` | RedPacketService `ExpirationTaskExecutor` | 红包过期退款 |

### 6.3 Kafka 的可靠性和幂等性

Kafka 常见消费语义是 at-least-once：消费者处理成功但 offset 尚未提交时崩溃，重启后会再次收到同一消息。因此：

- `acks=all` 和生产者重试主要解决生产可靠性，不等于消费幂等。
- 当前部分配置使用 `enable-auto-commit: true`，offset 自动提交时机和业务处理不是一个原子事务。
- `ConsumerOfflineService` 直接 `service.save(message)`，需要数据库唯一键或其他机制防止重复落库。
- 实时推送重复消费可能导致用户看到重复气泡，推送消息也应带 messageId，并在消费侧做幂等或去重。
- 好友申请过期执行器使用 `status IN (UNREAD, READ)` 的条件更新，重复事件再次执行时更新行数为 0，属于较好的幂等范式。

## 7. Redis：当前用了哪些数据结构

| 数据结构/用途 | Key 示例 | 典型操作 | 作用 |
|---|---|---|---|
| String 验证码 | 邮箱本身作为 key | `SET`、`GET`、`DEL`、TTL | 注册/登录验证码，短时有效 |
| String Access Token | `access:token:207...` | `SET`、`GET`、`DEL` | 保存当前有效 access token，支持主动失效 |
| String Refresh Token | `refresh:token:207...` | `SET`、`GET`、`DEL` | 换发新 token |
| String 离线时间 | `user:offline:207...` | `SET`、`GETDEL`、`EXISTS` | 记录断线时间，登录时一次性取出 |
| String 好友状态缓存 | `msg:validate:friend:status:{uid}:{fid}` | `GET`、`SET`、`DEL` | 缓存好友关系，包含“非好友”负缓存 `-1`，TTL 5 分钟 |
| ZSet 最近聊天消息 | `session:{sessionId}` | `ZADD`、`ZRANGEBYSCORE`、`ZREVRANGEBYSCORE`、`ZREMRANGEBYSCORE` | score 是毫秒时间戳，保留最近 7 天热消息 |
| ZSet 好友申请延迟任务 | `friend-request-expire-zset` | `ZADD`、Lua 查询并 `ZREM` | member 是 applyFriendId，score 是过期时间戳 |
| List 红包金额池 | `redpacket:{id}:pool` | `RPUSH`/初始化、Lua `LPOP`、`LLEN` | 每个元素是一份红包金额 |
| Hash 红包领取记录 | `redpacket:{id}:records` | Lua `HEXISTS`、`HSET` | userId -> amount，防止同一用户重复领取 |
| ZSet 红包过期任务 | `redpacket-expire-zset` | `ZADD`、Lua `ZRANGEBYSCORE`、`ZREM` | member 是 redPacketId，score 是过期时间戳 |
| ShedLock 锁 | 由 ShedLock 根据任务名生成 | 底层使用 Redis 竞争锁 | 多实例时保证同一个定时任务只有一个实例执行 |

### 7.1 ZSet 轮询和 Lua

ZSet 是“成员 + 分数”的有序集合。本项目把过期时间放在 score 中，每秒执行一次：

```text
@Scheduled(fixedRate = 1000)
    -> ShedLock 竞争调度锁
    -> Lua: ZRANGEBYSCORE key -inf now LIMIT 0 batchSize
    -> Lua: 删除本次取出的成员
    -> Java: 把过期 ID 发到 Kafka
    -> Consumer: 执行业务更新
```

Lua 的意义是让“取出并删除”在 Redis 内部一次执行，避免两个调度实例同时取到同一批任务。`KEYS[1]` 是 Redis 脚本操作的 key 列表中的第一个 key，`ARGV[1]`、`ARGV[2]` 是调用方传入的普通参数；这是一种 Redis Lua 的标准参数约定，不是固定只能这样命名。

ShedLock 和 ZSet 不是同一个东西：

- ZSet 保存“哪些业务任务什么时候到期”。
- ShedLock 防止多个服务实例同时执行“扫描定时任务”。
- Lua 保证一次扫描中“领取任务并删除”是原子的。

`SET NX` 也能自己实现简单分布式锁，但要自己处理唯一 token、过期、释放锁校验、续期、异常恢复等问题。ShedLock 是针对 Spring `@Scheduled` 的现成协调方案，底层 Redis provider 也会使用类似“只有不存在时才能写入”的竞争机制，但它比裸写 `SET NX` 多了一套调度锁生命周期处理。

### 7.2 大 key 和热 key

- 大 key：一个 key 里面的数据量太大，例如一个长期活跃会话的 `session:{id}` ZSet 或一个集中存放任务的 ZSet。会导致单次读写耗时、网络响应大、删除阻塞、集群迁移困难。
- 热 key：访问频率特别高，例如热门群聊的消息 key、同一个红包金额池 key。问题是请求集中在某个 Redis 节点或单线程执行点上。

`friend-request-expire-zset` 和 `redpacket-expire-zset` 都是固定单 key。数据量达到很大规模时，可以按时间桶、业务分片或 hash tag 拆分，但拆分后扫描、去重和运维都会更复杂。当前学习项目规模下先用单 key 是可以理解的工程取舍。

## 8. 好友、会话、群聊和好友申请

### 8.1 `session` 和 `user_session`

两张表的关系类似“会话主表 + 会话成员关系表”：

- `session`：会话本身是什么，保存 `session_id`、名称、类型、状态、头像等。
- `user_session`：谁加入了哪个会话，保存 `user_id`、`session_id`、角色、成员状态。

单聊一般有一个 `session`，再通过两条 `user_session` 记录把两个用户挂进去。群聊有一个 `session`，再通过多条 `user_session` 记录保存成员和角色。所以单独查 `session` 不能知道成员关系，单独查 `user_session` 又不知道会话的名称和类型。

### 8.2 好友关系为什么通常是双向记录

`friend` 常按 `(user_id, friend_id)` 保存一条方向关系。A 和 B 是好友时，通常有：

```text
(A, B, NORMAL)
(B, A, NORMAL)
```

拉黑、删除、恢复时需要同步双向状态，并清除两个方向的 Redis 状态缓存：

```text
msg:validate:friend:status:A:B
msg:validate:friend:status:B:A
```

缓存不是数据库，它只是查询加速层。数据库关系发生变化后不清缓存，旧状态会在 TTL 到期前继续影响消息发送权限，因此要做缓存失效。

### 8.3 好友申请状态

`apply_friend` 至少涉及 sender、receiver、message、status、created_time、updated_time。未读、已读、通过、拒绝、过期是不同状态：

- 0 未读不代表所有接口都只能传 0；修改接口的 `status` 是“要改成什么状态”，可以是通过、拒绝或已读。
- 通过后会创建好友关系，必要时创建单聊会话和两条成员关系，并发送系统通知。
- 已拒绝、已读、已过期的旧申请可以被重新激活：复用记录，更新状态为未读、更新附言，再重新投递过期任务。
- 事务负责数据库内的原子性；Kafka 通知发送通常不自动参加同一个 MySQL 事务。通知发送失败时，好友和会话可能已经成功，后续需要重试、Outbox 或补偿机制。

好友申请过期完整链路：

```text
发送/重新激活好友申请
 -> friend-request-creation-topic
 -> FriendRequestExpirationEnqueuer
 -> ZADD friend-request-expire-zset applyFriendId / expireTime
 -> 每秒 @Scheduled + @SchedulerLock
 -> Lua 取出并删除已到期 applyFriendId
 -> friend-request-expiration-topic
 -> FriendRequestExpirationExecutor
 -> 条件 UPDATE apply_friend.status
```

这里“过期”的是好友申请，不是好友关系。申请在规定时间内没有处理，就标记为 EXPIRED，之后不能再按旧的待处理申请直接通过。

### 8.4 好友列表为什么要预先建 Map

如果先查好友列表，然后对每个用户再查一次关系和会话，就是 N+1 次查询。当前优化思路是：

1. 一次查出用户列表。
2. 一次批量查出关系，构造成 `friendRelationMap`。
3. 一次批量查出会话，构造成 `friendSessionMap`。
4. 遍历用户列表时通过 userId 在内存 Map 中 O(1) 获取关系和 sessionId。

这里不是“有没有创建 List”的区别，而是把数据库往返从很多次减少到固定几次。内存分页只适合数据量小；数据量增长后应使用 SQL `LIMIT/OFFSET` 或游标分页，并配合索引。

## 9. MySQL、MyBatis-Plus 和 SQL 基础

### 9.1 Entity、Mapper、Service

- Entity：Java 对应数据库表的一行，例如 `User`、`Session`、`Message`。
- Mapper：数据库访问接口，例如 `userMapper.insert(user)`，直接执行一条持久化操作。
- Service：业务层，例如 `userService.save(user)`，通常由 MyBatis-Plus 的 `IService` 提供通用 CRUD，也可以在 ServiceImpl 中组合多次 Mapper 调用、校验和事务。

`mapper.insert()` 更接近“直接执行 Mapper 的 insert”；`service.save()` 是 Service 层的通用保存入口，内部通常仍会调用 Mapper，但可以承载业务封装。两者都不是“自动魔法保存一切”，最终仍然要满足表名、列名、类型、数据库连接和事务条件。

### 9.2 `@TableField`

MyBatis-Plus 默认会根据 Java 命名规则把 `userId` 映射成 `user_id`。当字段名和默认规则不一致、需要明确指定、或项目希望提高可读性时会写 `@TableField("user_id")`。有的实体没写，是因为全局下划线映射已经能正确处理；不是数据库字段不存在。

### 9.3 LambdaUpdateWrapper 和原子扣款

典型扣款代码表达的是：

```sql
UPDATE user_balance
SET balance = balance - ?
WHERE user_id = ?
  AND balance >= ?;
```

`LambdaUpdateWrapper` 是 MyBatis-Plus 用 Java 方法引用拼条件和更新的类型安全写法：

- `eq(...)`：等于条件。
- `ge(...)`：大于等于条件。
- `set(...)`：设置字段值。
- `setSql(...)`：写 SQL 表达式，例如 `balance = balance - 100`。

单条条件更新的并发安全来自数据库的行锁和 SQL 的原子执行，不是“SQL 天生无锁”。两个请求同时扣款时，InnoDB 会串行处理同一行，第二个请求重新检查 `balance >= amount`，余额不足就更新 0 行。

金额应尽量用“分”的整数 `Long`，避免浮点数误差。红包领取 Lua 脚本的关键步骤是：

```text
HEXISTS records userId
 -> 已领：返回 -1
 -> 未领：LPOP pool 取一份金额
 -> HSET records userId amount
 -> LLEN pool 判断是否领完
```

这些步骤在 Redis Lua 中连续执行，Redis 不会在脚本中途穿插其他客户端命令，因此可以保证“检查未领取、扣出金额、记录领取”作为一个原子整体。当前过期退款逻辑主要使用 `calculateRemainAmountScript`，它把剩余金额计算和清空金额池放在 Redis 脚本内，避免 Java 先读再删之间出现并发窗口；数据库红包状态校验还需要和领取接口共同保证“过期后不能再领”。

### 9.4 关键数据库表

| 表 | 作用 |
|---|---|
| `user` | 用户账号、密码、昵称、头像、状态、角色、逻辑删除字段 |
| `friend` | 用户与好友的方向关系及状态 |
| `apply_friend` | 好友申请及申请状态 |
| `session` | 单聊/群聊会话主表；当前实体包含头像字段，数据库必须同步有 `avatar` 列 |
| `user_session` | 用户与会话的成员关系、角色和状态 |
| `message` | 聊天消息持久化表；当前包含 `session_type` |
| `red_packet` | 红包主体、总金额、数量、状态、会话类型 |
| `red_packet_receive` | 谁领取了哪个红包、领取金额；`(red_packet_id, receiver_id)` 应使用唯一索引防止重复领取 |
| `user_balance` | 用户余额，金额单位为分 |
| `balance_log` | 扣款、领取、退款等余额流水 |

`updated_time` 是审计字段，表示最后一次更新；状态变化、重试、修改附言、退款都能依靠它追踪时间。它不是自动完成业务逻辑的开关，是否更新仍取决于代码和 MyBatis-Plus 配置。

## 10. DTO、Request、VO、Entity 和泛型

可以按“数据流向”记：

| 类型 | 方向 | 例子 | 作用 |
|---|---|---|---|
| Entity | Java <-> 数据库 | `User`、`Friend`、`RedPacket` | 表结构映射，不应该直接把所有数据库字段暴露给前端 |
| Request | 客户端 -> Controller | `AddFriendRequest`、`CreateGroupRequest` | 接收 URL 参数、JSON body，并做校验 |
| DTO | 服务内部/服务之间 | `MessageRequest`、`FriendDTO`、Kafka event | 传输和组合业务数据，不一定是数据库对象，也不一定只能表示输入 |
| VO | Controller -> 客户端 | `TokenResponse`、`FriendDetailVO` | 面向前端展示的输出模型 |
| Response | Controller -> 客户端 | `BaseResponse<T>`、`PageResponse<T>` | 统一包装状态、数据、分页信息 |

DTO 不是只能表示“传入数据”。它的本质是 Data Transfer Object，只要是在模块或层之间传输，就可以是入参、出参、事件或内部聚合结果。Request/VO 是项目里进一步强调输入/输出方向的命名约定。

`BaseResponse<T>` 的泛型表示 `data` 可以装不同类型：

```java
BaseResponse<UserStatusResponse>
BaseResponse<List<FriendDTO>>
BaseResponse<Boolean>
```

`PageResponse<T>` 表示一页数据，通常包含 records、total、current、size 等信息；它和业务对象无关，只负责分页结果的统一包装。

`Serializable` 表示对象可以被 Java 序列化，方便缓存、会话、消息或某些框架传输。`serialVersionUID` 是序列化版本号，用于反序列化时判断类版本是否兼容；它不是数据库主键，也不参与业务逻辑。

## 11. Spring Boot、Spring MVC、Maven 和常见注解

### 11.1 三者是什么

- Maven：项目构建和依赖管理工具。读取 POM，下载 Spring Boot、MyBatis、Kafka 等依赖，执行编译、测试、打包。
- Spring Boot：基于 Spring 的快速配置和启动方式，通过 starter 和自动配置组装应用。
- Spring MVC：Spring 处理 HTTP 请求的 Web 框架，负责 URL 映射、参数绑定、JSON 转换、Controller 调用和响应返回。Gateway 使用 reactive WebFlux，而普通业务服务主要是 Spring MVC 风格。

### 11.2 常见注解

- `@SpringBootApplication`：应用入口，组合组件扫描和自动配置。
- `@RestController`：Controller 方法返回值直接写入 HTTP response body，通常转为 JSON。
- `@RequestMapping`：声明路径前缀。
- `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping`：分别映射不同 HTTP 方法。
- `@PathVariable`：从 URL 路径取值，例如 `/user/{userId}`。
- `@RequestParam`：从 query 参数取值，例如 `?userId=11`。
- `@RequestBody`：把请求 JSON 反序列化成 Java 对象。
- `@Valid`：触发 Request 对象上的校验注解。
- `@Service`：业务层 Bean。
- `@Component`：通用组件 Bean。
- `@Repository`：持久化层 Bean。
- `@Configuration`：配置类。
- `@Bean`：把方法返回对象注册到 Spring 容器。
- `@Autowired` / `@Resource`：依赖注入；构造器注入通常更容易保证依赖完整，也更利于测试。
- `@RequiredArgsConstructor`：Lombok 根据 `final` 字段生成构造器，Spring 会使用它进行构造器注入。
- `@Transactional`：把数据库操作放到事务中，异常时按规则回滚数据库操作。
- `@Scheduled`：让 Spring 定时调用方法，例如每秒扫描过期任务。
- `@KafkaListener`：把方法注册成 Kafka 消费者。
- `@FeignClient`：声明一个跨服务 HTTP 客户端接口。
- `@Aspect` / `@Around`：定义 AOP 切面。

字段注入是在对象创建后直接给字段赋值；构造器注入是在构造对象时把依赖传入。构造器注入能更早暴露缺失依赖，字段可以保持 `final`，测试时也可以直接传入 mock。

## 12. AOP、日志、重复提交和异常业务码

项目公共 AOP 的 `LogInterceptor` 用切点匹配 Controller 方法，在 Controller 执行前后统一记录日志。它不会替代 Controller，也不是“Controller 不干活”；它只是额外包住 Controller 的横切逻辑。

红包模块的 `PreventDuplicateSubmitAspect` 是更具体的业务切面：

```text
Controller 方法加 @PreventDuplicateSubmit
 -> Around 拿到方法和参数
 -> 参数 JSON 生成请求指纹
 -> Redis SET NX + TTL 抢占 key
 -> 抢不到：认为重复提交，拒绝
 -> 抢到：执行原方法
 -> 业务异常：删除 key，允许用户重试
```

拿方法、注解和参数的代码是很多 AOP 都会用到的通用框架操作；如何生成 key 则是防重复提交这个业务特有的策略。它只防短时间内相同请求重复进入，不替代红包领取 Lua、数据库唯一索引和业务幂等。

统一返回的核心结构是：

```json
{
  "code": 200,
  "data": {},
  "message": "ok"
}
```

当前 `ErrorCode` 定义了参数错误、未登录、无权限、资源不存在、系统错误、业务操作错误、用户状态错误、WebSocket 错误等业务码。需要区分：

- HTTP 状态码：网络层/协议层的状态，例如 200、400、401、404、500。
- `BaseResponse.code`：项目业务层状态，例如 `200` 成功、`40000` 参数错误、`40100` 未登录、`40300` 无权限、`50000` 系统错误。

当前很多 Controller 会把业务结果包装成 `BaseResponse`，即使业务失败也可能通过 HTTP 200 返回，这时前端必须判断 `code`，不能只看 HTTP status。对于 Feign 也一样：远程 HTTP 200 只表示网络请求成功，`BaseResponse.code != 200` 时 FallbackFactory 不会自动触发，调用方业务层必须检查 code 和 data。

更成熟的做法是明确约定：参数/认证/权限错误使用对应 HTTP 状态，业务失败使用稳定业务码，并让全链路日志携带 requestId、userId、业务对象 ID 和异常原因。无论是否采用 HTTP 非 2xx，都要保证错误码稳定、含义唯一、前端可处理。

## 13. MinIO 图片/文件上传

MinIO 是对象存储，类似私有部署版的 S3，不把图片二进制塞进 MySQL。

项目的典型上传流程：

```text
1. 客户端请求 UserService 获取上传地址
2. UserService 从 JWT 确认当前 userId
3. 后端生成唯一 objectName
4. OssUtils 调用 MinIO SDK 生成预签名 PUT URL
5. 客户端直接 PUT 文件到 MinIO
6. 客户端把最终对象地址或 objectName 提交给业务接口
7. UserService 更新 user.avatar，群聊更新 session.avatar
```

头像和聊天图片的“文件上传”流程可以相同，区别在于业务对象和最后保存的位置不同：头像通常保存到 `user.avatar` 或 `session.avatar`，聊天图片通常作为消息内容/消息体中的对象地址保存到 `message`。

本地地址如 `http://localhost:9000/infinitechat/dp.jpg` 只适合本机开发。上线时应换成 MinIO 的域名、反向代理域名或 CDN 地址；代码中 `minio.url` 是生成下载地址时使用的基础地址。`dp.jpg -> group/default.jpg` 是为群聊默认头像准备的对象，不代表所有用户图片都自动混在同一个业务记录里；MinIO bucket 内是对象，数据库记录 URL 或 objectName。

## 14. Canal 和冷热数据

Canal 模拟 MySQL 从库读取 binlog，监听数据库行变更。当前 `CanalClient` 实现 `CommandLineRunner`，Spring 启动完成后另起线程持续拉取 Canal 消息：

```text
MySQL INSERT message
 -> MySQL binlog
 -> Canal Server
 -> CanalClient getWithoutAck
 -> 解析 afterColumnsList
 -> 组装 MessageResponse
 -> Redis ZADD session:{sessionId} messageJson score=createdTimeMillis
 -> ack(batchId)
```

`List<CanalEntry.Column>` 就是 Canal 从 binlog 事件中解析出来的列集合。业务代码负责把列名和值组装成项目对象；Canal 本身只负责把数据库变更事件传出来。

Redis 的消息 score 和 MySQL 转换后的 `createdTime` 当前统一为毫秒时间戳，便于冷热数据混合时使用数值比较和排序。`DATE_FORMATTER` 仍用于解析 Canal 传来的 MySQL 日期字符串，例如 `2026-08-01 10:00:00`，解析成毫秒后就不再负责排序。

当前冷热分层是：

- 热数据：Redis `session:{sessionId}`，约保留最近 7 天。
- 冷数据：MySQL `message` 表。
- 写入 Redis 时通过 `ZREMRANGEBYSCORE` 删除 7 天以前的成员。
- 当前不是给每个会话 key 设置 TTL，所以长期没有新消息的会话可能保留旧数据；规模增大后可以增加定期清理策略。

## 15. 红包模块全链路

### 15.1 创建红包

```text
客户端 -> RedPacketController
 -> 校验用户/群成员/余额/金额/数量
 -> 扣减 sender balance（条件 UPDATE）
 -> 记录 balance_log
 -> 生成红包金额列表
 -> Redis List 写入金额池
 -> MySQL 写入 red_packet
 -> 发送 redpacket-creation-topic
 -> DelayTaskEnqueuer 把 redPacketId + 24h 过期时间写入 redpacket-expire-zset
```

红包消息本质上也是聊天消息，所以发送红包消息时会创建 `MessageRequest` 和内部 `MessageBody`：

- `MessageRequest` 是统一消息外层，放 sessionId、senderId、type、sessionType、receiverId、clientMessageId 等。
- `MessageBody` 是消息内容，红包消息里放 redPacketId、红包文案等。

这不是两个响应体，而是一层统一消息信封里嵌套不同类型的消息体。普通文本、图片、回复、红包都可以共用外层字段。

### 15.2 抢红包

红包金额池是 Redis List，领取记录是 Redis Hash。领取 Lua 将“查重、弹出金额、写记录、判断是否领完”放在一个原子脚本中。数据库 `red_packet_receive` 还应有唯一索引：

```sql
UNIQUE KEY uk_red_packet_receiver (red_packet_id, receiver_id)
```

Redis 防住正常路径的重复领取，数据库唯一索引是最终兜底。两者都要有，因为 Redis 可能失效、脚本可能绕过、消息可能重复消费。

### 15.3 红包过期退款

```text
redpacket-expire-zset
 -> ExpirationDispatcher 每秒 @Scheduled
 -> ShedLock 确保多实例只执行一个 dispatcher
 -> Lua 原子取出并删除到期红包 ID
 -> redpacket-expiration-topic
 -> ExpirationTaskExecutor
 -> 查询 red_packet 状态
 -> calculateRemainAmountScript 原子计算并清空剩余金额
 -> UPDATE user_balance SET balance = balance + remainAmount
 -> 写 balance_log(type=退款)
 -> 更新 red_packet.status=EXPIRED
 -> 删除红包 Redis pool、records 和过期任务
```

“红包过期后不能再抢”不能只靠定时任务。定时扫描每秒一次，过期时刻到达后到消费者真正处理之间存在窗口，因此领取接口本身也要校验数据库红包状态和过期时间，并在 Redis/数据库层设计一致的并发控制。过期处理使用事务可以保证 MySQL 内的余额、流水和红包状态一起提交/回滚；Redis 操作和 MySQL 事务不是同一个原子事务，严格一致性还需要更完整的状态机、幂等和补偿方案。

## 16. 当前项目的分布式能力和边界

已经体现分布式思想的地方：

- Nacos：服务注册发现。
- Gateway + `lb://`：HTTP 服务负载均衡。
- OpenFeign：服务间 HTTP RPC。
- Kafka：异步解耦、消费者组、跨实例分摊消息。
- Redis：共享缓存、共享延迟任务池、共享锁和 token 状态。
- ShedLock：多实例定时任务互斥。
- MySQL：共享持久化数据。
- Snowflake：分布式 ID 的时间排序和节点区分。

还没有完全解决的地方：

- 当前主要是本地单实例运行，没有真正压测多实例。
- Netty 的 `ChannelManager` 是单机内存映射，跨实例推送还需要路由表或统一 WebSocket 网关。
- Kafka 消费幂等还应补数据库唯一键、Redis 去重或手动提交 offset 等配套。
- 业务数据库事务和 Kafka 通知发送不是天然同一事务，通知失败需要重试/Outbox/补偿。
- `store-notification-topic` 的完整消费、持久化和上线补拉链路需要继续确认。
- 延迟任务 ZSet 是固定单 key，规模大时存在大 key/热 key 风险。
- 应用配置中不应提交真实密码和密钥；应统一使用环境变量或配置中心。

## 17. 推荐的后续学习/完善路线

按当前项目继续学习，建议顺序是：

1. 先熟悉 Spring MVC：Controller、Request、校验、统一响应、异常处理。
2. 熟悉 MyBatis-Plus：Entity、Mapper、Service、Wrapper、事务、索引和 SQL。
3. 重新走通登录和 JWT：access/refresh、Redis token、Gateway 鉴权、WebSocket 鉴权。
4. 走通好友和会话：`user`、`friend`、`apply_friend`、`session`、`user_session` 的关系。
5. 走通 Netty：Channel、Pipeline、EventLoop、心跳、断线清理、`writeAndFlush`。
6. 走通 Kafka：topic、partition、group、key、offset、至少一次消费和幂等。
7. 走通离线消息：Kafka -> MySQL -> Canal -> Redis -> 上线补拉。
8. 学 Redis 数据结构和 Lua：String、Hash、List、ZSet、SET NX、pipeline、脚本原子性。
9. 学 Nacos/Gateway/OpenFeign：服务注册、发现、HTTP 负载均衡、Fallback 和业务 code 检查。
10. 最后做多实例实验：启动两个 UserService、两个 RealTimeService，观察 Nacos、Kafka 分区、ShedLock 和 WebSocket 路由。

最值得牢记的一句话是：

> WebSocket 负责保持用户和服务器的实时连接；Netty 负责网络事件；Kafka 负责服务内部的异步消息流转；MySQL 负责可靠持久化；Canal 把 MySQL 变更传播到 Redis；Redis 负责缓存、状态、延迟任务和锁；Gateway/Nacos/OpenFeign 负责微服务之间的入口、发现和调用。

