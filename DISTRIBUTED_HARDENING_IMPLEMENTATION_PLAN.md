# InfiniteChat 分布式能力整改实施方案

> 目的：把当前项目中已经存在的分布式组件，逐步补齐为可验证、可扩展、可恢复的实现。
>
> 本文只描述代码层、配置层、数据库层和验证层的改法。本次只调整文档，不修改 Java、YAML、SQL 或部署文件。
>
> 适用代码基线：2026-09-19 当前工作区（含已有未提交改动）。以下“已实现”仅指源码和配置已具备，不代表已完成多实例验收；数据库约束和实际部署状态仍需另行确认。

## 0. 总体结论

当前项目是微服务雏形：

```text
Gateway + UserService + RealTimeService + OfflineDataService + RedPacketService
                         + Common 公共类库
```

项目已经使用 Nacos、Gateway、OpenFeign、Kafka、Redis、Canal、ShedLock 和 Snowflake，但“使用组件”不等于“已经完成高可用分布式系统”。当前最重要的整改目标是：

1. 让多实例下生成的 ID 不冲突。
2. 让 Kafka 消费失败可重试、可进入死信，并且重复消费不会产生重复业务结果。
3. 让过期任务投递失败后不会从任务池永久消失。
4. 让 WebSocket 消息能够跨 RealTimeService 实例找到目标连接。
5. 让 Gateway 路由完整，服务之间减少不必要的编译期耦合。
6. 让数据库唯一约束成为最终一致性兜底，而不是只依赖 JVM 内存和 Redis。

建议分三阶段实施：

| 阶段 | 目标 | 主要内容 |
|---|---|---|
| 第一阶段 | 先保证数据不乱 | 唯一索引、Snowflake、Kafka 幂等、异常不吞、过期任务不丢 |
| 第二阶段 | 支持多实例业务服务 | Nacos 多实例、Gateway 路由、WebSocket 实例路由、ShedLock 参数 |
| 第三阶段 | 基础设施高可用 | Kafka 多 Broker、Redis Sentinel/Cluster、Nacos 集群、MySQL 高可用、监控告警 |

不要一开始同时改所有中间件。先把“重复、丢失、错路由”这些数据正确性问题解决，再做集群化部署。

## 1. 问题与整改总表

| 编号 | 当前问题 | 严重程度 | 推荐处理 |
|---:|---|---|---|
| 1 | Snowflake 多实例使用相同节点编号，RealTimeService 的动态注入写法无效 | P0 | 改为 Spring Bean + 每实例唯一 workerId/datacenterId |
| 2 | Kafka 自动提交 offset，消费者异常被捕获后不再抛出 | P0 | 手动 ack、重试、死信、幂等 |
| 3 | 过期任务先从 Redis 删除，后发 Kafka | P0 | 改为发送成功后确认，或使用租约/Outbox |
| 4 | ChannelManager 是本地状态；Redis 跨实例路由与 Pub/Sub 已实现，可靠性仍待验收 | P0 | 保留方案 A，验证重连、路由过期及补拉恢复 |
| 5 | Netty 端口配置化、`netty-port` 注册与读取已实现 | P1 | 验证各实例端口、唯一实例 ID 和客户端可达地址 |
| 6 | Gateway 已包含好友、群聊和红包路由 | P1 | 验证现有入口；新增会话等接口时同步补路由 |
| 7 | 注册邮箱只靠 `synchronized(email.intern())`，且查询在锁外 | P1 | 数据库 email 唯一索引 + 捕获重复键 |
| 8 | UserService 编译依赖 RedPacketService | P2 | 删除无实际使用的业务模块依赖 |
| 9 | Kafka topic 默认副本数为 1 | P1 | 集群部署时副本数改为 3，实际 topic 重新扩容 |
| 10 | 示例配置默认指向本地单点，已支持部分环境变量覆盖 | P1 | 先验证多实例，再按实际部署做中间件高可用 |

## 2. P0：修复 Snowflake 分布式 ID

### 2.1 当前代码问题

当前存在多处固定节点配置：

- `Common/src/main/java/com/goat/common/constant/SnowflakeConstant.java`
- `UserService/src/main/java/com/goat/userservice/constants/UserConstant.java`
- `Common/src/main/java/com/goat/common/utils/SnowflakeUtil.java`
- `UserService/src/main/java/com/goat/userservice/service/impl/UserServiceImpl.java`
- `RealTimeService/src/main/java/com/goat/realtimeservice/utils/SnowflakeDynamicUtil.java`

多个实例如果使用相同的 `workerId + datacenterId`，在相同时间窗口内可能生成重复 ID。

`SnowflakeDynamicUtil` 还有额外问题：

```java
@Value("${snowflake.workerId}")
private static long workerId;

private static final Snowflake SNOWFLAKE =
        IdUtil.getSnowflake(workerId, dataCenterId);
```

Spring 不应依赖 `@Value` 注入 static 字段，而且 static 初始化发生在 Spring 完成注入前。这个类很可能使用默认值，而不是配置文件中的值。

### 2.2 推荐改法

在 `Common` 中提供可注入的 ID 生成器，或者在每个需要生成 ID 的服务中提供同样的 Bean。推荐公共实现：

```java
@ConfigurationProperties(prefix = "snowflake")
public class SnowflakeProperties {
    private long workerId;
    private long datacenterId;
}

@Configuration
@EnableConfigurationProperties(SnowflakeProperties.class)
public class SnowflakeConfig {
    @Bean
    public Snowflake snowflake(SnowflakeProperties properties) {
        return IdUtil.getSnowflake(
                properties.getWorkerId(),
                properties.getDatacenterId());
    }
}

@Component
public class SnowflakeIdGenerator {
    private final Snowflake snowflake;

    public SnowflakeIdGenerator(Snowflake snowflake) {
        this.snowflake = snowflake;
    }

    public long nextId() {
        return snowflake.nextId();
    }
}
```

然后把业务类中的：

```java
SnowflakeUtil.nextId()
SnowflakeDynamicUtil.nextId()
```

改成构造器注入：

```java
private final SnowflakeIdGenerator idGenerator;

Long id = idGenerator.nextId();
```

需要重点替换的调用位置包括：

- 用户注册生成 userId。
- 好友申请生成 applyFriendId。
- WebSocketHandler 生成 messageId。
- RedPacketService 生成 redPacketId、balanceLogId、messageId 等。

`WebSocketHandler` 当前由 `NettyService` 手动创建，不是 Spring 自动管理的 Bean；应由 `NettyService` 注入 ID 生成器，再传入每个连接的 Handler，不能只增加注解期待自动注入。

### 2.3 每个实例如何分配节点编号

本地多实例测试先手动配置：

```yaml
snowflake:
  workerId: ${SNOWFLAKE_WORKER_ID:1}
  datacenterId: ${SNOWFLAKE_DATACENTER_ID:1}
```

启动第二个同类实例时必须使用不同的组合：

```text
UserService-1: workerId=1, datacenterId=1
UserService-2: workerId=2, datacenterId=1
RealTimeService-1: workerId=3, datacenterId=1
RealTimeService-2: workerId=4, datacenterId=1
```

不要只按“服务名”分配；同一个服务启动多个实例时，每个实例也必须不同。

生产环境可以使用：

- Kubernetes StatefulSet ordinal。
- 容器/Pod 环境变量。
- 启动时从 Redis 或数据库租约表申请节点编号。
- 独立的 ID 生成服务。

### 2.4 数据库兜底

由雪花 ID 标识的实体主键应有主键或唯一索引；消息表中的 `sender_id`、`session_id` 等关联字段允许重复，不能分别加唯一约束。主键约束不能代替正确的 workerId，但能在生成冲突时尽早暴露问题。

### 2.5 验收标准

1. 启动两个 UserService，分别配置不同 workerId。
2. 并发生成至少 10 万个 ID。
3. 插入数据库后没有主键/唯一键冲突。
4. 重启实例后仍能正常生成 ID。
5. 日志打印实例标识和 Snowflake 节点配置，便于排查。

## 3. P0：Kafka 消费可靠性、重试和幂等

### 3.1 当前问题

当前多个 `application.yml` 使用：

```yaml
enable-auto-commit: true
```

涉及 UserService、RealTimeService、OfflineDataService 和 RedPacketService。

这会让 offset 提交和业务处理脱离控制。除此之外，当前若干消费者捕获异常后只记录日志，不继续抛出，Kafka 可能把这条消息当成已经处理成功。

重点检查这些类：

- `OfflineDataService/.../ConsumerOfflineService.java`
- `RealTimeService/.../ConsumerMessageService.java`
- `RealTimeService/.../SystemNotificationConsumer.java`
- `UserService/.../FriendRequestExpirationEnqueuer.java`
- `UserService/.../FriendRequestExpirationExecutor.java`
- `RedPacketService/.../DelayTaskEnqueuer.java`
- `RedPacketService/.../ExpirationTaskExecutor.java`
- `RedPacketService/.../RedPacketConsumer.java`（领取入账、领完事件）

### 3.2 第一阶段配置改法

把消费者配置调整为：

```yaml
spring:
  kafka:
    consumer:
      enable-auto-commit: false
      properties:
        isolation.level: read_committed
    listener:
      ack-mode: manual_immediate
```

`manual_immediate` 只是示例，最终要和监听器方法的 ack 方式保持一致。

### 3.3 消费者方法改法

监听器增加 `Acknowledgment`：

```java
@KafkaListener(topics = "store-topic", groupId = "infinite-chat-store-group")
public void consume(String message, Acknowledgment acknowledgment) {
    try {
        MessageRequest request = parse(message);
        messageService.saveMessageToMySQL(request);
        acknowledgment.acknowledge();
    } catch (Exception e) {
        // 不确认，并交给 DefaultErrorHandler 重试
        throw e;
    }
}
```

注意：手动提交只能控制“什么时候确认消费”，不能消除重复消费。重复消费一定要靠业务幂等。

只有核实重复键对应同一业务事件，且原业务已成功提交时，才可按重复消费确认；不能把所有 `DuplicateKeyException` 都当作成功，否则会掩盖 Snowflake 冲突或不完整处理。

### 3.4 重试和死信

在 Kafka 配置类中增加：

```java
@Bean
public DefaultErrorHandler kafkaErrorHandler(
        DeadLetterPublishingRecoverer recoverer) {
    FixedBackOff backOff = new FixedBackOff(1000L, 3L);
    return new DefaultErrorHandler(recoverer, backOff);
}
```

生产环境还应增加：

- 重试次数。
- 指数退避。
- 死信 topic，例如 `store-topic.DLT`。
- 失败原因、原始 topic、partition、offset、messageId 等 headers。
- 监控死信数量和消费者 lag。

如果某个消费者已经在方法内部 catch 住异常，就必须重新抛出，或者明确调用错误处理器；不能只写日志然后正常返回。

### 3.5 消费幂等

普通消息落库：

```sql
ALTER TABLE message
ADD UNIQUE KEY uk_message_id (message_id);
```

如果 `message_id` 已经是主键，不需要重复增加唯一索引，但必须先确认数据库实际结构。

红包领取：

```sql
ALTER TABLE red_packet_receive
ADD UNIQUE KEY uk_red_packet_receiver
(red_packet_id, receiver_id);
```

好友申请过期已经使用条件更新，方向是正确的：

```sql
UPDATE apply_friend
SET status = EXPIRED
WHERE apply_friend_id = ?
  AND status IN (UNREAD, READ);
```

推送消息的幂等可以采用以下一种或多种组合：

- 客户端按 messageId 去重。
- Redis 保存短期 `push:{userId}:{messageId}` 去重标记。
- 每个消费实例在本地维护短期 Caffeine 去重缓存。

Channel 是否 active 只是连接检查，不是幂等依据；本地缓存也不能保证跨实例去重。

上述 `messageId` 唯一约束只解决同一 Kafka 事件重复消费。当前 WebSocket 每次收到请求都会生成新 `messageId`，消息表没有保存 `clientMessageId`；客户端复用该字段重发仍会产生新消息。若要满足前端“重试不重复”，需另补基于已认证发送者与 `clientMessageId` 的持久化幂等及原结果返回。红包发送目前也只有 3 秒防重复提交，不能作为长期幂等保证。

推送失败是否重试，要看消息是否已经持久化。只有存储与补拉闭环完善后，普通聊天才能依赖补拉恢复（见第 5.4 节）；消息落库失败不能直接确认存储消费的 offset。

### 3.6 生产者发送确认

当前 `kafkaTemplate.send(...).whenComplete(...)` 主要用于打印日志。对于必须可靠投递的事件，不能只打印失败日志后继续完成业务。

可以采用：

- 等待发送 Future 确认并设置合理超时；Netty 收包线程应使用异步衔接，不能阻塞整个事件循环。
- 在业务数据所在的同一个本地事务内写 Outbox，事务提交后再由后台发布。
- 使用 Kafka 事务时，配合数据库 Outbox 解决跨系统一致性。
- 发送失败记录补偿表。

`acks=all` 只说明 Kafka Producer 需要等待副本确认，不代表消费者幂等，也不代表 MySQL 事务和 Kafka 事务自动一致。

当前普通消息与红包消息分别发送 `store-topic` 和 `message-topic`，两次发送并不原子；收到 WebSocket 回推不等于 MySQL 已落库。前端需要的发送确认应由 RealTimeService 通过 WebSocket 返回，并区分已接收与已持久化；持久化结果由 OfflineDataService 通过事件反馈。不能仅新增一个客户端调用的 HTTP ack 接口就宣称消息已可靠保存。

## 4. P0：修复红包过期任务丢失

### 4.1 当前链路的问题

当前 `ExpirationDispatcher` 的顺序是：

```text
Lua：ZRANGEBYSCORE + ZREM
    -> KafkaTemplate.send()
```

代码位置：

`RedPacketService/src/main/java/com/goat/redpacketservice/scheduler/ExpirationDispatcher.java`

如果 Redis 删除成功，Kafka 发送失败，这个任务就从 ZSet 和 Kafka 中同时消失。

另外，`kafkaTemplate.send()` 是异步 Future，外层 `try-catch` 不能捕获所有异步发送失败。

### 4.2 当前项目推荐的最小改法：先读，发送成功后删除

学习项目可以先采用“发送成功后确认删除”：

```text
Lua 只查询到期成员，不删除
    -> 逐条发送 Kafka
    -> Kafka Future 成功回调
    -> ZREM 精确删除对应 redPacketId
```

伪代码：

```java
List<String> expiredIds = scanExpiredWithoutDelete(...);

for (String redPacketId : expiredIds) {
    kafkaTemplate.send(topic, redPacketId, redPacketId)
            .whenComplete((result, exception) -> {
                if (exception == null) {
                    redisTemplate.opsForZSet()
                            .remove(EXPIRE_ZSET, redPacketId);
                } else {
                    log.error("发送失败，保留任务等待下次重试", exception);
                }
            });
}
```

这种方案会允许同一个任务在 Kafka 发送确认前被重复扫描，所以 `ExpirationTaskExecutor` 必须幂等。重复事件最终只能让一次状态更新和一次退款成功。

这是实施前提：当前 `handleRedPacketExpiration` 先查状态再退款、`updateById`，不是原子条件流转；计算剩余金额的 Lua 还会先删除金额池，之后 MySQL 回滚也不能恢复 Redis。应先补齐退款金额的可恢复记录、状态条件更新与账务事务，再启用重复投递；仅修调度器或增加领取记录唯一索引不足以保证退款不丢。

### 4.3 更稳妥的 Redis 租约方案

如果担心多个调度器重复扫描，可以增加处理中 ZSet：

```text
redpacket-expire-pending-zset
redpacket-expire-processing-zset
```

Lua 脚本把到期任务从 pending 移到 processing，并给 processing 设置租约时间。发送成功后删除 processing；发送失败或实例宕机，租约到期后重新回到 pending。

需要注意：Redis 脚本、Kafka ack 和数据库状态仍然不是一个全局事务，最终仍靠 Executor 幂等。

### 4.4 生产方案：Outbox

创建红包的数据库事务内写入：

```text
red_packet
red_packet_event_outbox
```

Outbox 字段可包含：

```text
event_id
event_type
aggregate_id = redPacketId
payload
status = PENDING / SENT / FAILED
retry_count
next_retry_time
created_time
updated_time
```

后台发布器读取 PENDING 事件，发送 Kafka 成功后更新 SENT；失败则增加重试次数和下次重试时间。消费者以 event_id 或 redPacketId 幂等。

### 4.5 ShedLock 参数

当前：

```java
@Scheduled(fixedRate = 1000)
@SchedulerLock(lockAtMostFor = "800ms", lockAtLeastFor = "200ms")
```

当前单轮时间预算约 400ms，800ms 看似有余量，但 Kafka 发送、Redis 延迟、GC、线程调度都可能让任务超过 800ms。

改法：

- `lockAtMostFor` 大于单轮最坏执行时间，并保留余量。
- 时间预算、批次大小、锁时长改为配置项。
- 不把 ShedLock 当成业务幂等。
- Executor 必须支持重复事件。

例如可以先将锁配置为 5 秒，再通过压测决定最终值，而不是直接把 800ms 当成绝对安全值。

## 5. P0：WebSocket 多实例路由

### 5.1 当前实现与剩余问题

当前 `ChannelManager` 只保存当前 JVM 内的：

```text
userId -> Channel
Channel -> userId
```

Kafka 的 `message-topic` 使用同一个消费组。多实例下，消费消息的实例不一定就是持有目标用户 Channel 的实例。

一致性哈希只能帮助“选择用户连接到哪个实例”，不能解决“Kafka 消费者最终在哪个实例执行”的问题。因此仅添加一致性哈希不够。

当前已通过 `WebSocketRouteService`、`WebSocketPushService` 和 Redis 订阅者补上跨实例转发。下面保留现有方案及验收要求，不再将这些组件列为待新增。

### 5.2 已实现：Netty 端口注册到 Nacos metadata

Spring HTTP 端口仍然保持给 Feign 使用，例如：

```text
RealTimeService HTTP: 8102
RealTimeService Netty: 9101
```

不要把 Nacos 的主端口直接改成 9101，否则 Feign 可能会把 HTTP 请求发到 Netty 端口。

RealTimeService 当前 metadata 配置为：

```yaml
spring:
  cloud:
    nacos:
      discovery:
        metadata:
          netty-port: ${NETTY_SERVER_PORT:9101}
          realtime-instance-id: ${REALTIME_INSTANCE_ID:realtime-${NETTY_SERVER_PORT:9101}}
```

Netty 监听端口已从配置读取：

```java
@Value("${netty.server.port:9101}")
private int port;
```

更推荐使用 `@ConfigurationProperties`，不要在字段上到处散落 `@Value`。

`NettyServiceLocator` 已按以下逻辑读取 metadata：

```java
ServiceInstance instance = select(...);
String nettyPort = instance.getMetadata().get("netty-port");
if (nettyPort == null) {
    throw new IllegalStateException("RealTimeService 缺少 netty-port metadata");
}
return instance.getHost() + ":" + nettyPort + "/ws/netty";
```

不要继续无条件使用 `instance.getPort()` 拼接 WebSocket 地址。

运行时路由与订阅使用 `realtime.instance-id`，应与 metadata 的 `realtime-instance-id` 共用 `REALTIME_INSTANCE_ID`。默认值只含 Netty 端口，不同机器使用同一端口时必须显式配置不同实例 ID。Nacos 返回的主机地址还需对客户端可达，生产环境的 WSS 地址需要实际的 TLS 入口支持。

### 5.3 已实现：共享路由与连接生命周期

连接鉴权成功后，在 `WebSocketAuthHeader` 中写入 Redis：

```text
ws:route:{userId} -> instanceId|channelId
```

当前 TTL 由 `realtime.route-ttl-seconds` 配置，默认 60 秒：

```text
SET ws:route:207... realtime-2|channel-id EX 60
```

路由值包含实例 ID 和具体 Channel ID；下面 Lua 的比较值必须是完整的 `instanceId|channelId`，只比较实例 ID 无法区分同实例的新旧连接。

连接生命周期：

```text
握手鉴权通过 -> 写入路由并设置 TTL
收到文本 ping -> 仅在完整路由值匹配时续期 TTL，并回复文本 pong
channelInactive -> 只有当前值仍属于本 Channel 时才删除
```

断开时不要直接无条件 `DEL`，否则旧连接断开可能把新连接的路由删掉。应使用 Lua compare-and-delete：

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
```

如果要支持一个用户多端同时在线，不能只用一个 String，需要改成：

```text
ws:route:{userId} Hash(channelId -> instanceId)
```

当前 token 与路由均按用户单值保存，按单设备模型接入。跨实例旧连接强制下线和离线时间归属仍需补齐，不能宣称已有完整的多端管理。客户端心跳间隔必须小于路由 TTL（默认配置下可取 20 秒）；后台暂停心跳后应重新握手登记路由，过期后的旧连接仅发心跳不会重建路由。

### 5.4 第三步：选择实例间推送方案

这里有两种主要思路，不能把“Kafka 消费”和“Redis 转发”混为一件事：

当前代码已落地方案 A：`WebSocketAuthHeader` 登记 Redis 路由，`ConsumerMessageService` 和
`SystemNotificationConsumer` 统一通过 `WebSocketPushService` 推送；目标在本机时直接写入
`ChannelManager`，目标在其他实例时发布到 `ws:push:{instanceId}`，由目标实例的 Redis
订阅者接收后再写入本地 Channel。

#### 方案 A：RealTimeService 消费后再转发（当前推荐的最小改法）

```text
message-topic
    -> 任意 RealTimeService 消费
    -> 查 Redis 得到目标 instanceId
    -> 如果目标是本机，直接 Channel.writeAndFlush
    -> 如果目标是其他实例，发布到 ws:push:{instanceId}
    -> 目标实例订阅自己的 Redis channel
    -> 目标实例从本地 ChannelManager 找 Channel
    -> writeAndFlush
```

优点：不需要新增服务，本机目标可以少一次转发，适合当前学习项目渐进改造。缺点是 Kafka 可能先把消息交给“没有目标连接”的实例，该实例需要多做一次 Redis 查询，并在跨实例时多一次转发。

Redis Pub/Sub 只适合实时推送。普通聊天有 `store-topic -> MySQL -> Canal -> Redis` 存储链路，但两路 Kafka 发送独立，且当前热区间补拉依赖 Redis，没有覆盖该区间的 MySQL 回源兜底。因此“推送失败可补拉恢复”是需要补齐和验收的目标，不能当作当前无条件保证。

系统通知另走 `system-notification-topic`；当前仅在路由失败且检测到离线标记时发送 `store-notification-topic`，仓库尚无对应存储消费者和通知历史接口。不能将系统通知按普通聊天消息写入或通过 `/api/message/offline` 补拉；第一版应重新查询好友申请等业务列表，通知历史作为后续独立能力补齐。

#### 方案 B：增加 PushRouter，先统一路由再转发

```text
message-topic
    -> PushRouter 消费
    -> 查 Redis 得到 userId -> instanceId
    -> 发布到 ws:push:{instanceId}
    -> 目标 RealTimeService 订阅自己的 channel
    -> ChannelManager 找 Channel
    -> writeAndFlush
```

PushRouter 不是 Redis 自己完成的，而是一个独立的消费者组件。它可以部署多个实例并使用同一个 Kafka groupId，让 Kafka 在 Router 实例之间分摊 Partition。

优点：RealTimeService 只负责 WebSocket 连接和本地推送，路由职责集中；不会由每个 RealTimeService 都承担“消息可能发错实例”的判断。缺点是所有实时推送都要经过 Router 和 Redis，多一个固定跳转；PushRouter 需要扩容、监控和故障处理，否则会形成新的瓶颈或单点。

因此，PushRouter 不是绝对更好，而是把复杂度从每个 RealTimeService 集中到一个专门组件。当前项目已先实现方案 A；当推送量、实例数量或团队职责需要独立扩展时，再抽出方案 B。

#### 不要把所有 Kafka Topic 都送进 Redis

只有实时推送类 Topic 适合经过 PushRouter，例如：

```text
message-topic
system-notification-topic
```

`store-topic`、好友申请过期、红包领取和红包过期等 Topic 仍然应该由各自业务消费者处理。Redis 是在线路由/转发层，不替代 Kafka 的持久化消息流。

如果要求推送任务本身可恢复，可以评估 Redis Streams 或 Kafka 实例专属 Topic。现有 Pub/Sub 方案需先补齐上述存储和补拉闭环；切换转发介质仍需额外管理确认、重试、实例上下线、Topic 生命周期和路由变化。

### 5.5 WebSocket 验收测试

1. 启动两个 RealTimeService，配置不同 HTTP/Netty 端口和实例 ID。
2. 用户 A 连接实例 1，用户 B 连接实例 2。
3. A 给 B 发单聊消息。
4. 观察任意实例消费 `message-topic` 后能否把消息转给实例 2。
5. 关闭 B 的连接，确认 Redis 路由能过期或被删除。
6. B 重新连接后，旧连接不能删除新连接的路由。
7. B 多次发送心跳，确认路由 TTL 持续刷新。
8. 暂停心跳超过 TTL 后重新握手，验证路由恢复；实例宕机后重新获取 `nettyUri` 并连接其他实例。
9. 模拟推送或 Canal 延迟，验证补拉最终可恢复已持久化消息；系统通知单独验证。

## 6. P1：核对 Gateway 路由与新增接口归属

当前 Gateway 的本地配置和示例配置均已包含用户、好友、群聊、离线消息和红包路由。

在：

`Gateway/src/main/resources/application.yml`

和对应的 `application.example.yml` 中已配置红包路由：

```yaml
- id: RedPacketService
  uri: lb://RedPacketService
  predicates:
    - Path=/api/chat/redPacket/**
```

验证：

```text
直接访问 http://localhost:8103/api/chat/redPacket/...
通过 Gateway 访问 http://localhost:10010/api/chat/redPacket/...
```

两者都应到达同一业务接口。

前端规划的 `/api/session/**` 与 `/api/notification/**` 当前没有公开接口及网关路由。会话/成员归 UserService，新增的用户已读位置也建议归该服务；消息存储与查询仍归 OfflineDataService，会话摘要可通过内部调用或事件聚合。通知历史可在现有服务内补齐存储与查询，明确归属后再加路由，不因新 URL 就新建微服务。`/api/user/get/receivers` 和 `/api/user/get/sessions` 是现有 Feign 辅助接口，返回裸 ID 列表，不能直接替代前端会话详情与列表接口。

WebSocket 不应直接把 Gateway 的普通 HTTP 路由当成 Netty 路由。短期可以由登录接口返回带 metadata 的 Netty 地址；长期可以部署专门支持 WebSocket 的网关/反向代理。

## 7. P1：修复注册并发和数据库约束

### 7.1 当前代码问题

用户注册流程先查询邮箱：

```java
User user = getUser(email);
```

然后在 `synchronized (email.intern())` 内插入。

问题有两个：

1. `synchronized` 只在当前 JVM 生效。
2. 查询在锁外，没有在锁内重新查询。

即使单机也存在：两个线程都先查不到，然后依次进入锁并插入。

### 7.2 推荐改法

数据库先建立唯一索引：

```sql
ALTER TABLE user
ADD UNIQUE KEY uk_user_email (email);
```

执行前先检查重复数据：

```sql
SELECT email, COUNT(*)
FROM user
GROUP BY email
HAVING COUNT(*) > 1;
```

业务代码保留“先查再提示”作为用户体验优化，但最终以数据库唯一索引为准：

```java
try {
    userMapper.insert(user);
} catch (DuplicateKeyException e) {
    throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
}
```

如果继续保留 JVM 锁，锁内至少要重新查询；但它仍然不能替代数据库唯一索引。

### 7.3 其他需要确认的唯一约束

```text
user.email                         UNIQUE
message.message_id                 PRIMARY KEY/UNIQUE
red_packet_receive(red_packet_id,
                   receiver_id)   UNIQUE
```

好友关系、会话成员、好友申请是否需要唯一约束，要结合当前业务允许的状态和重激活逻辑设计，不能直接对所有字段加唯一键。

## 8. P2：删除 UserService 对 RedPacketService 的编译依赖

当前：

`UserService/pom.xml` 声明了 `RedPacketService` 依赖，但 UserService 源码没有实际使用红包模块的业务类。

整改步骤：

1. 用 `rg` 检查 UserService 是否 import `com.goat.redpacketservice`。
2. 用 `mvn dependency:tree` 确认没有隐藏的编译依赖。
3. 删除 UserService POM 中的 RedPacketService dependency。
4. 执行根项目 `mvn clean test`。
5. 如果确实需要共享类型，把类型移动到 Common，不要让业务模块互相依赖。

目标依赖方向：

```text
UserService       -> Common
RedPacketService  -> Common
RealTimeService   -> Common
OfflineDataService-> Common
Gateway           -> 必要的 Gateway 依赖
```

业务服务之间需要通信时使用 OpenFeign 或 Kafka，而不是直接依赖对方的业务实现类。

## 9. P1：Kafka 和中间件高可用部署路线

### 9.1 Kafka

当前 `KafkaConstant.DEFAULT_REPLICA_COUNT = 1`，代码声明的 topic 副本数不是高可用配置。

生产环境建议：

```text
Kafka Broker >= 3
业务 topic replication.factor >= 3
min.insync.replicas >= 2
acks=all
```

注意：已有 topic 的副本数不能只改 Java 常量。需要使用 Kafka 管理命令或运维工具执行副本迁移，并确认实际结果。

### 9.2 Redis

Redis 选择一种高可用模式即可：

- Sentinel：主从 + 自动故障转移，适合现有单主模型改造。
- Cluster：作为后续分片方案，切换前需检查当前 `database: 2`、红包多键 Lua 和键命名的兼容性，不能只替换连接地址。

WebSocket 路由、token、好友状态、ShedLock、延迟 ZSet 都依赖 Redis，共享 Redis 不能继续只依赖某台机器的 localhost。

### 9.3 Nacos

开发环境单节点足够。生产环境通常使用 Nacos 集群，并使用外部 MySQL 集群存储 Nacos 数据。

### 9.4 MySQL 和 Canal

MySQL 需要主从、云数据库高可用或其他故障转移方案。Canal 连接的应该是稳定的 MySQL 主库/复制拓扑，而不是开发机 localhost。

CanalClient 当前只监听 `message` 表 INSERT 并写 Redis。如果以后需要处理消息修改、删除或其他表，必须扩展事件类型和幂等处理。Canal 本身也需要重连、位点恢复和高可用方案。

当前 CanalClient 随每个 OfflineDataService 启动，不能将 HTTP/Kafka 消费实例扩容等同于 Canal 消费高可用；扩容前应明确同一 destination 的消费归属和位点管理。现有代码已有重连与 ack/rollback，但 Redis 写失败被内部捕获后仍可能确认 Canal 批次，需先修正错误传播并提供热缓存回源或重建能力。

## 10. 代码实施顺序

### 第 1 批：不改变业务接口

1. 查询数据库现有重复数据。
2. 增加 email、messageId、红包领取联合唯一索引。
3. 改 Snowflake 为 Spring Bean。
4. 每个实例使用唯一 workerId/datacenterId。
5. 消费者关闭 auto commit，配置手动 ack。
6. 消费者失败抛异常，加入重试和死信。
7. 保留条件更新和唯一索引作为幂等。

### 第 2 批：修复延迟任务

1. 修改红包过期脚本，不要在 Kafka 发送前永久删除任务。
2. Kafka 成功后再删除，失败保留等待重试。
3. 确保过期 Executor 重复执行只退款一次。
4. 增大并配置化 ShedLock 的 `lockAtMostFor`。
5. 对好友申请过期链路做相同检查。

### 第 3 批：验收并完善现有多实例 WebSocket

1. 验证已有端口配置、Nacos metadata 和 `NettyServiceLocator`。
2. 配置全局唯一的 `REALTIME_INSTANCE_ID`，核对 `instanceId|channelId` 路由和 60 秒默认 TTL。
3. 验证已有连接/文本心跳/断线路由维护与 compare-and-delete。
4. 验证现有 Pub/Sub 转发，补齐持久化确认和离线补拉保障。
5. 补齐跨实例旧连接处置、离线时间归属与故障后地址刷新。
6. 做双实例跨机器测试。

### 第 4 批：入口和依赖整理

1. 验证已有 Gateway 好友、群聊、红包路由；随新增接口补充会话等路由。
2. 确认业务 HTTP 请求经过 Gateway；WebSocket 使用服务端返回的地址，预签名上传直达对象存储。
3. 删除 UserService 对 RedPacketService 的无用编译依赖。
4. 为服务配置环境变量，不把真实密码提交到仓库。

### 第 5 批：部署高可用

1. 编写 Docker Compose 作为本地多实例实验环境。
2. 再根据需要迁移 Kubernetes。
3. 部署 Nacos、Redis、Kafka、MySQL 的高可用版本。
4. 加 Actuator、日志聚合、指标、链路追踪、告警和限流。

## 11. 每一批的验收场景

### ID

```text
两个同类服务实例同时生成 100000 个 ID
数据库无唯一键冲突
```

### Kafka

```text
消费者处理到一半强制结束进程
重启后消息重新投递
业务结果只产生一次
连续失败超过重试次数后进入 DLT
```

### 过期任务

```text
模拟 Kafka 不可用
Redis 中任务不能永久消失
Kafka 恢复后任务能够重新投递
同一红包过期事件重复到达只能退款一次
```

### WebSocket

```text
A 连接 RealTimeService-1
B 连接 RealTimeService-2
A 给 B 发消息
B 能收到
B 断开后 Redis 路由最终消失
B 重新连接后旧连接断开不会删除新连接路由
```

### Gateway

```text
通过 10010 访问用户接口
通过 10010 访问离线消息接口
通过 10010 访问红包接口
请求都能被 Nacos 找到正确实例
```

## 12. 最终目标架构

```text
                         +------------------+
客户端 HTTP -----------> | Gateway x 2       |
                         +--------+---------+
                                  |
                         Nacos 服务发现/负载均衡
              +-------------------+-------------------+
              |                   |                   |
        UserService x 2   OfflineDataService x 2  RedPacketService x 2
              |                   |                   |
              +-----------+-------+-------------------+
                          |
                  MySQL / Redis / Kafka
                          |
       +------------------+------------------+
       |                                     |
RealTimeService x 2                  Canal -> Redis 热消息
       |
       +-- Netty WebSocket
       +-- 本地 ChannelManager
       +-- Redis user -> instance 路由
       +-- Redis Pub/Sub 实例间转发
```

最终需要形成的职责边界是：

- Gateway：HTTP 入口和路由。
- Nacos：服务发现，不负责业务消息。
- OpenFeign：同步 HTTP RPC。
- Kafka：异步事件和任务流转。
- Redis：共享状态、缓存、锁、延迟任务和实时实例路由。
- MySQL：最终业务数据和唯一性约束。
- Netty：WebSocket 网络连接和 Channel 写入。
- Canal：把 MySQL binlog 变更传播到热数据缓存。
- ShedLock：只协调定时任务，不替代业务幂等。

一句话原则：

> 分布式系统不是把服务启动在不同端口就完成了；必须同时保证实例发现、请求路由、ID 唯一、消息不丢、重复可安全处理、任务可恢复，以及 WebSocket 能跨实例找到连接。
