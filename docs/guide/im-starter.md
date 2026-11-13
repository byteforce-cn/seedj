# IM 长连接装配

`seedj-im-starter` 把 IM 长连接接进 seedj 基座：帧协议、Netty TCP/WebSocket 传输、IAM JWT
握手与在线连接注册表。宿主只留一个 `@EnableImServer`。

> 当前是阶段 1：登录、心跳、未实现命令回 `501`。业务对象、单聊/群聊、历史补拉在后续阶段。

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
| `seedj.im.enabled` | `true` | 总开关 |
| `seedj.im.push.provider` | `local` | 推送提供方；`redis` 留给后续阶段 |
| `seedj.im.tcp.enabled` | `true` | 是否监听 TCP |
| `seedj.im.tcp.port` | `9090` | TCP 端口；测试通常填 `0` |
| `seedj.im.ws.enabled` | `true` | 是否监听 WebSocket |
| `seedj.im.ws.port` | `9091` | WebSocket 端口 |
| `seedj.im.ws.path` | `/ws` | WebSocket 握手路径 |
| `seedj.im.heartbeat-timeout` | `90s` | 读空闲超时，超时关闭连接 |
| `seedj.im.max-frame-length` | `1048576` | 单帧 body 上限；超限直接关闭连接 |
| `seedj.im.max-sessions-per-user` | `1000` | 单用户连接上限，与 scope `In` 上限对齐 |

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
| `CHAT_SINGLE_REQ` | `0x05` | 已登录时回 `501` |
| `CHAT_GROUP_REQ` | `0x07` | 已登录时回 `501` |
| `ACK_REQ` | `0x0A` | 已登录时回 `501` |
| `OFFLINE_PULL_REQ` | `0x0B` | 已登录时回 `501` |
| `READ_RECEIPT` | `0x0D` | 已登录时回 `501` |
| `SESSION_SYNC_REQ` | `0x0E` | 已登录时回 `501` |
| `ERROR` | `0x7F` | 错误响应，body 为 `{"code":…,"message":…}` |

## 握手、心跳与错误

登录请求的 body：

```json
{"access_token":"<IAM 访问令牌>"}
```

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
