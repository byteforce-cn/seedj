# IM 长连接装配

`seedj-im-starter` 把 IM 长连接接进 seedj 基座：帧协议、Netty TCP/WebSocket 传输、IAM JWT
握手与在线连接注册表。宿主只留一个 `@EnableImServer`。

> 当前阶段：登录、心跳、单聊（发送/投递/确认/已读/补拉/同步）、命令分发与两套存储
> （内存 / PostgreSQL）。群聊的 socket 命令与 HTTP 管理端点在下个阶段落地；未注册的命令字仍回 `501`。

## 依赖与启用

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>seedj-im-starter</artifactId>
</dependency>
```

```java
@SpringBootApplication
@EnableImServer
public class ImServer {

    public static void main(String[] args) {
        SpringApplication.run(ImServer.class, args);
    }
}
```

`@EnableImServer` 同时引入 biz 标记，所以命令、审计、安全等业务能力也会就绪。宿主必须显式关闭
本地开发认证，只信任 IAM 令牌：

```yaml
seedj:
  biz:
    dev-auth:
      enabled: false
  im:
    enabled: true
```

本模块不依赖 `seedj-iam-starter`，只消费 IAM 签发的访问令牌（SPI 宪法 C1）。

## 配置

| 键 | 默认值 | 说明 |
|---|---|---|
| `seedj.im.enabled` | `true` | 总开关；关掉后传输、存储、HTTP 全部不生效 |
| `seedj.im.store` | `memory` | 存储：`memory` 进程内，`jdbc` PostgreSQL |
| `seedj.im.push.provider` | `local` | 推送提供方；跨节点广播留给后续阶段 |
| `seedj.im.tcp.enabled` | `true` | 是否监听 TCP |
| `seedj.im.tcp.port` | `9090` | TCP 端口；测试通常填 `0` |
| `seedj.im.ws.enabled` | `true` | 是否监听 WebSocket |
| `seedj.im.ws.port` | `9091` | WebSocket 端口 |
| `seedj.im.ws.path` | `/ws` | WebSocket 握手路径 |
| `seedj.im.heartbeat-timeout` | `90s` | 读空闲超时，超时关闭连接 |
| `seedj.im.max-frame-length` | `1048576` | 单帧 body 上限；超限直接关闭连接 |
| `seedj.im.max-sessions-per-user` | `1000` | 单用户会话数上限（登录时生效） |
| `seedj.im.session-overflow` | `reject` | 超限行为：`reject` 回 `429` 并关闭；`evict-oldest` 先关最早连接 |
| `seedj.im.message.max-body-bytes` | `65536` | 单条消息体上限，超限拒绝 |
| `seedj.im.message.retention` | 空 | 消息保留期（例 `P180D`）；空表示永久，清理任务尚未落地 |
| `seedj.im.sync.page-size` | `200` | 单次增量拉取最大条数 |
| `seedj.im.sync.replay-history` | `false` | 入群前历史是否回放 |
| `seedj.im.executor.threads` | `4` | 命令执行泳道数；命令不在 Netty IO 线程执行 |
| `seedj.im.executor.queue-capacity` | `4096` | 每条泳道队列容量；满时回 `429` |

## 本地跑起来

### 1. 起 IAM

按 [IAM 装配](iam-starter.md) 启动 `seedj-server/iam`，至少打开 native-auth 与 demo
client（demo client 只用于联调）：

```yaml
seedj:
  iam:
    enabled: true
    issuer: http://localhost:9000
    demo:
      enabled: true
    native-auth:
      enabled: true
```

### 2. 换访问令牌

native-auth 只对第一方 App 开放，使用 `native-app` 公共客户端：

```bash
curl -s http://localhost:9000/oauth2/token \
  -d grant_type=urn:seedj:params:oauth:grant-type:native-auth \
  -d client_id=native-app \
  -d username=alice \
  -d password=alice-password \
  -d scope="openid profile" | jq -r .access_token
```

用户与权限由 IAM 管理面维护；`permissions` claim 默认会写进访问令牌。

### 3. 起 IM 宿主

```bash
SEEDJ_IAM_ISSUER=http://localhost:9000 \
  ./mvnw -pl seedj-server/im -am spring-boot:run -Dspring-boot.run.profiles=local
```

`local` profile 排除数据源自动配置，命令/对象/锁用内存实现，审计/outbox/eventbus 关闭。
生产环境用 `postgres` profile 配置数据源。

## 存储

`seedj.im.store` 是唯一开关，两套实现的语义由同一份契约测试约束：

| 模式 | 表/结构 | 适用 |
|---|---|---|
| `memory` | 进程内映射与计数器 | 本地开发、单实例测试；重启即丢 |
| `jdbc` | `im_conversation`、`im_conversation_member`、`im_conversation_event`、`im_message`、`im_read_cursor`、`im_sync_cursor` 与序列 `im_seq` | 生产 |

`jdbc` 模式在启动期通过 `ImSchemaCapability`（迁移 scope `im`，能力序号 220）建表，缺
`DataSource` 时直接启动失败并提示改 `spring.datasource` 或回 `seedj.im.store=memory`——不会
静默退回内存。

成员是「任期」而不是一行一人：入群插一行、退群写 `left_seq`，因此「某个序号时谁在群里」是一次
区间比较。消息序号来自全局序列，**单调但不连续**，客户端必须容忍缺号。

## 单聊链路

```text
A.send(peer_id)            → 服务端按 direct_key 找或建单聊会话 → 取号 → 落消息 + outbox
                           → 回 CHAT_SINGLE_RESP{message_id, conversation_id, seq}
                           → 向 B 推 CHAT_DELIVER
B.ACK  {conversation_id, seq}         → 推进该设备的同步游标（成功不回包）
B.READ_RECEIPT {conversation_id, seq} → 推进已读序号，向 A 转发 READ_RECEIPT 通知
B.OFFLINE_PULL {cursor, limit}        → 自己所属会话里 seq > cursor 的消息，按序号升序
B.SESSION_SYNC {since_seq}            → 会话快照（last_seq / unread）+ 已读游标 + 建议补拉游标
```

语义约定：

- **至少一次**。在线投递是尽力而为（连接不可写就跳过），离线靠 `OFFLINE_PULL` 补齐；客户端按
  `message_id` 去重。
- **`seq` 全局单调但会话内可能不连续**：跨节点提交顺序与序号顺序不保证一致，游标只能在持久化后
  前进，重连时从游标重拉一页并去重。
- **幂等**：同一 `client_msg_id` 重发返回同一条消息，不重复推送。
- **租户与成员**：跨租户会话、非成员发送一律 `403`；判定在服务层统一收口，socket 与 HTTP 共用。
- **正文不进事件总线**：outbox 事件 `im.message.sent` 只带 `message_id / conversation_id / seq /
  sender_id / kind`。

宿主可用 `ImConversationService`（建单聊/建群/加人/退群/改名）与 `ImMessagingService`（发送/补拉/
同步）从自己的 HTTP 端点复用同一条路径；默认策略 bean 是 `ImConversationPolicy` 与
`ImAuthorizationGate`，覆盖它们即可接入好友、黑名单与组织模型。

## 命令扩展

宿主注册一个 `ImCommandHandler` bean 就能新增命令字，不用改 starter，也不用 fork 分发逻辑：

```java
@Bean
ImCommandHandler<MyBody> myCommand() {
    return new ImCommandHandler<>() {
        public byte cmd() { return (byte) 0x30; }
        public Class<MyBody> bodyType() { return MyBody.class; }
        public ImReply handle(ImCallContext context, MyBody body) {
            context.notify(Cmd.READ_RECEIPT, Map.of("seq", 1));   // 主动通知，序号 0
            return ImReply.of((byte) 0x31, Map.of("ok", true));   // 回包，序号原样带回
        }
    };
}
```

- 同一命令字注册两次是启动失败，不静默覆盖。
- 处理器抛 `ContractException` 时按契约语义映射成协议码（403/404/409…），其它异常统一 `500`，
  细节只进日志。
- 未注册的命令字仍回 `501`，连接保持。

## 帧格式

帧头固定 16 字节、大端：

| 偏移 | 长度 | 字段 | 说明 |
|---|---|---|---|
| 0 | 4 | `magic` | 固定 `0xCAFEBABE` |
| 4 | 1 | `version` | 当前为 `1` |
| 5 | 1 | `serializeType` | JSON 为 `1` |
| 6 | 1 | `cmd` | 命令字，见下表 |
| 7 | 1 | `msgType` | `0` 请求、`1` 响应、`2` 通知 |
| 8 | 4 | `sequenceId` | 客户端序号，响应原样带回 |
| 12 | 4 | `bodyLength` | body 字节数 |
| 16 | 变长 | `body` | UTF-8 JSON |

body 的 JSON 字段一律蛇形命名；**所有 id 都是字符串**（IAM `sub` 是字符串，seedj 对象 id 是
UUID）。

| 命令 | 值 | 阶段 1 行为 |
|---|---|---|
| `LOGIN_REQ` | `0x01` | 用 `access_token` 握手 |
| `LOGIN_RESP` | `0x02` | 返回 `code`、`user_id`、`display_name` |
| `HEARTBEAT_PING` | `0x03` | 回 `HEARTBEAT_PONG` |
| `HEARTBEAT_PONG` | `0x04` | 服务端下发 |
| `CHAT_SINGLE_REQ` | `0x05` | 单聊发送：`{client_msg_id, conversation_id?, peer_id?, kind, body}` |
| `CHAT_GROUP_REQ` | `0x07` | 群聊发送：下个阶段 |
| `ACK_REQ` | `0x0A` | 客户端确认：`{conversation_id, seq}`，成功不回包 |
| `OFFLINE_PULL_REQ` | `0x0B` | 增量补拉：`{cursor, limit}` |
| `READ_RECEIPT` | `0x0D` | 已读上报：`{conversation_id, seq}`，向其它成员转发同形状通知 |
| `SESSION_SYNC_REQ` | `0x0E` | 会话同步：`{since_seq}` |
| `ERROR` | `0x7F` | 错误响应，body 为 `{"code":…,"message":…}` |

## 握手、心跳与错误

登录请求的 body：

```json
{"access_token":"<IAM 访问令牌>","device_id":"<设备标识，可省略>"}
```

`device_id` 缺省时服务端按连接生成，用于多端已读与同步游标；省略不影响登录。

成功响应：

```json
{"code":0,"user_id":"u-1","display_name":"Alice"}
```

`display_name` 依次取 JWT 的 `name`、`preferred_username`、`display_name`，都没有时回落
`sub`。登录成功后连接进入注册表；同一用户多端登录会保留多条连接，只有最后一条断开会移除。

失败语义：

- `access_token` 缺失、空白、过期、签名不符：回 `ERROR`（`code=401`）并关闭连接，注册表不留条目。
- 未登录就发业务帧：回 `401` 并关闭。
- 已登录但命令未实现：回 `ERROR`（`code=501`，消息 `未实现: <cmd>`），连接保持。
- 单帧超过 `seedj.im.max-frame-length`：服务端立即关闭连接，不继续缓存。

## WebSocket

WebSocket 走独立端口，默认 `ws://localhost:9091/ws`。握手完成后，每个二进制帧的负载就是
一个完整 IM 帧（与 TCP 字节布局相同）；文本帧不会被当作 IM 报文处理。

## 最小客户端伪代码

```text
function send(cmd, seq, body):
    bytes = jsonBytes(body)
    writeInt(0xCAFEBABE); writeByte(1); writeByte(1)
    writeByte(cmd); writeByte(0); writeInt(seq)
    writeInt(bytes.length); writeBytes(bytes)

send(LOGIN_REQ, 1, {"access_token": token})
frame = readFrame()
assert frame.cmd == LOGIN_RESP and frame.body.code == 0

send(HEARTBEAT_PING, 2, {})
assert readFrame().cmd == HEARTBEAT_PONG
```

TCP 客户端不要把一次 `read` 当成一帧：先读满 16 字节帧头，再按 `bodyLength` 读 body；服务端
也是同样处理半包。

## 排错

| 现象 | 可能原因 |
|---|---|
| 登录回 `401` | 令牌缺失/过期，或 IM 的 `issuer-uri` 与 IAM 不一致 |
| 一直连不上 | IAM 未启动、`seedj.biz.dev-auth.enabled` 仍为 `true`，或端口被占用 |
| 发业务命令回 `501` | 阶段 1 正常行为；业务命令在后续阶段落地 |
| 连接突然关闭 | 帧超过 `max-frame-length`，或读空闲超过 `heartbeat-timeout` |
| 日志提示没有 `JwtDecoder` | 宿主未配置资源服务器 issuer，或没有引入 biz 安全装配 |

更多模块边界见 [模块与装配](modules.md)，令牌规则见 [安全](security.md)。
