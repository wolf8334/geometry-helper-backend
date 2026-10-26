# geometry-helper-backend

基于 Spring Boot 4.1.2 + Spring AI 2.0.1 的几何助手后端。

项目**同时运行两个独立的 AI 引擎**：

| 引擎 | 配置前缀 | 用途 | 模型要求 |
| --- | --- | --- | --- |
| 视觉理解引擎（VL / Vision） | `app.ai.vision` | `POST /api/solve` 图片理解 | 必须支持图片输入 |
| 普通对话引擎（Chat） | `app.ai.chat` | `POST /api/chat` 文本对话 | 纯文本模型即可 |

两个引擎各自选择供应商和模型，可以同时使用不同供应商、不同 API Key、
不同 endpoint。例如：视觉引擎用 Ofox 的 Vision 模型，对话引擎用硅基流动的
文本模型。

---

## 一、接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/ping` | 返回服务状态和当前服务器时间 |
| POST | `/api/solve` | 接收 `multipart/form-data` 图片（字段名 `file`），调用视觉引擎 |
| POST | `/api/chat` | 接收 JSON 文本消息，调用普通对话引擎，支持多轮历史 |

### 1. 健康检查

`GET /api/ping` 返回：

```json
{
  "msg": "服务正常",
  "timestamp": "2026-10-26 15:30:00"
}
```

时间格式说明：需求中的 `yyyy-mm-dd hh24:mi:ss` 是 Oracle 写法，Java 中对应
`yyyy-MM-dd HH:mm:ss`，代码里使用 `DateTimeFormatter` 实现。

### 2. 图片理解（视觉引擎）

`POST /api/solve`：

- 请求类型：`multipart/form-data`
- 文件字段：`file`
- 使用的 Bean：`visionChatClient`
- 成功后返回：

```json
{
  "msg": "图片上传成功",
  "answer": "图片中是一个由三条边组成的三角形……"
}
```

处理流程：

1. Controller 接收 `MultipartFile`；
2. Service 校验文件非空、MIME 类型为图片；
3. 将图片字节封装为 Spring AI 的 `Media`；
4. 使用 `visionChatClient` 把「用户提示词 + 图片」发送给多模态模型；
5. 返回模型输出。

### 3. 普通对话（对话引擎）

`POST /api/chat`：

- 请求类型：`application/json`
- 使用的 Bean：`chatChatClient`
- 单轮请求：

```json
{
  "message": "你好，请介绍一下你自己"
}
```

- 多轮请求：

```json
{
  "message": "那它的面积是多少？",
  "history": [
    { "role": "user", "content": "一个三角形的底是 6，高是 4。" },
    { "role": "assistant", "content": "它的面积是 12。" }
  ]
}
```

- 成功响应：

```json
{
  "msg": "对话成功",
  "answer": "你好，我是普通对话引擎……"
}
```

说明：

- 服务端不保存会话状态，多轮对话由客户端把 `history` 带上来；
- `history.role` 只支持 `user` 和 `assistant`；
- 默认只保留最近 `app.ai.chat.max-history-messages` 条历史，防止 token 无限增长；
- 视觉引擎和对话引擎互不影响，可以同时调用。

### 4. 异常处理

统一返回 JSON，避免把堆栈暴露给调用方：

| 场景 | HTTP 状态码 | `msg` 示例 |
| --- | --- | --- |
| 文件超过大小限制 | 413 | 上传文件过大，请压缩图片后重试 |
| 缺少 `file` 字段 | 400 | 缺少文件字段 file，请使用 multipart/form-data 上传图片 |
| 空文件 / 非图片 | 400 | 上传文件不能为空 / 无法识别图片格式…… |
| JSON 格式错误 | 400 | 请求体格式错误，请检查 JSON 格式和字段类型 |
| 不支持的 Content-Type | 415 | 不支持的 Content-Type，请使用 application/json 或 multipart/form-data |
| 模型调用失败 | 502 | 视觉/对话服务调用失败，请检查 API Key、模型名称或网络后重试 |
| 其他未知异常 | 500 | 服务器内部错误，请稍后重试 |

### 5. 数据库

- 已加入 `spring-boot-starter-jdbc` 和 PostgreSQL JDBC 驱动；
- 数据源默认连接 `geometry` 数据库；
- 通过 Hikari 的 `schema` 和 `connection-init-sql` 保证每个连接执行
  `SET search_path TO geometry`；
- 默认不自动建表，避免本地没有数据库时启动失败。

---

## 二、目录结构

```text
src/main/java/com/xhr/geometryhelperbackend
├── GeometryHelperBackendApplication.java   # 启动类
├── config
│   ├── AiConfig.java                       # 双引擎 ChatModel / ChatClient
│   └── AiProperties.java                   # app.ai.* 配置绑定
├── controller
│   ├── PingController.java                 # GET /api/ping
│   ├── SolveController.java                # POST /api/solve（视觉引擎）
│   └── ChatController.java                 # POST /api/chat（对话引擎）
├── dto
│   ├── ChatMessage.java
│   ├── ChatRequest.java
│   ├── ChatResponse.java
│   ├── ErrorResponse.java
│   ├── PingResponse.java
│   └── SolveResponse.java
├── exception
│   ├── AiServiceException.java
│   └── GlobalExceptionHandler.java
└── service
    ├── ImageSolveService.java              # 图片校验 + 视觉引擎调用
    └── ChatService.java                    # 文本对话 + 历史消息处理

src/test/java/com/xhr/geometryhelperbackend
└── GeometryHelperBackendApplicationTests.java  # 双 ChatClient 上下文测试
```

配置文件：

```text
src/main/resources
├── application.yaml                # 双引擎、双供应商、数据库、上传限制、日志级别
├── logback-spring.xml              # 简洁日志格式、控制台 + 文件滚动
└── db/init.sql                     # 可选的 schema 初始化脚本
```

---

## 三、配置与启动

### 1. 环境要求

- JDK 25+
- Maven 3.9+（或直接使用项目自带的 `mvnw`）
- PostgreSQL 18（当前接口不依赖数据库也可启动，但数据源已配置好）

> 注意：`pom.xml` 中 `java.version=25`，运行时必须使用 JDK 25 及以上。
> 如果命令行里的 `java -version` 和 `mvn -v` 显示的版本不一致，请先设置
> `JAVA_HOME`，确保 Maven、IDE 和 `java -jar` 使用同一个 JDK。

### 2. 双引擎配置

核心配置在 `application.yaml`：

```yaml
app:
  ai:
    providers:
      siliconflow:
        base-url: https://api.siliconflow.cn/v1
        api-key: ${SILICONFLOW_API_KEY:}
      ofox:
        base-url: https://api.ofox.ai/v1
        api-key: ${OFOX_API_KEY:}

    vision:
      provider: siliconflow
      model: Qwen/Qwen2.5-VL-72B-Instruct
      timeout: 120s
      # system-prompt / user-prompt ...

    chat:
      provider: siliconflow
      model: Qwen/Qwen2.5-72B-Instruct
      timeout: 60s
      max-history-messages: 20
      # system-prompt ...
```

- `providers` 只保存供应商的连接信息（endpoint、API Key）；
- `vision.provider` 和 `chat.provider` 分别选择供应商；
- 两个引擎的模型、温度、token 上限、超时、重试次数都独立配置；
- 代码启动时会创建两个独立的 `OpenAiChatModel` 和两个独立的 `ChatClient`。

### 3. 混合供应商示例

两个引擎都使用硅基流动：

```powershell
$env:SILICONFLOW_API_KEY="sk-xxx"
$env:VISION_PROVIDER="siliconflow"
$env:VISION_MODEL="Qwen/Qwen2.5-VL-72B-Instruct"
$env:CHAT_PROVIDER="siliconflow"
$env:CHAT_MODEL="Qwen/Qwen2.5-72B-Instruct"
.\mvnw.cmd spring-boot:run
```

视觉用 Ofox，对话用硅基流动（同时生效）：

```powershell
$env:OFOX_API_KEY="sk-ofox-xxx"
$env:VISION_PROVIDER="ofox"
$env:VISION_MODEL="openai/gpt-6.1-sol"

$env:SILICONFLOW_API_KEY="sk-sf-xxx"
$env:CHAT_PROVIDER="siliconflow"
$env:CHAT_MODEL="Qwen/Qwen2.5-72B-Instruct"

.\mvnw.cmd spring-boot:run
```

视觉用硅基流动，对话用 Ofox：

```powershell
$env:SILICONFLOW_API_KEY="sk-sf-xxx"
$env:VISION_PROVIDER="siliconflow"
$env:VISION_MODEL="Qwen/Qwen2.5-VL-72B-Instruct"

$env:OFOX_API_KEY="sk-ofox-xxx"
$env:CHAT_PROVIDER="ofox"
$env:CHAT_MODEL="openai/gpt-6.1-sol"

.\mvnw.cmd spring-boot:run
```

### 4. 环境变量

供应商：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SILICONFLOW_API_KEY` | 空 | 硅基流动 API Key |
| `SILICONFLOW_BASE_URL` | `https://api.siliconflow.cn/v1` | 硅基流动 endpoint |
| `OFOX_API_KEY` | 空 | Ofox API Key |
| `OFOX_BASE_URL` | `https://api.ofox.ai/v1` | Ofox endpoint |

视觉引擎：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `VISION_PROVIDER` | `siliconflow` | 供应商标识 |
| `VISION_MODEL` | `Qwen/Qwen2.5-VL-72B-Instruct` | 必须支持图片输入 |
| `VISION_TEMPERATURE` | `0.2` | 采样温度 |
| `VISION_MAX_TOKENS` | `1024` | 最大输出 token |
| `VISION_TIMEOUT` | `120s` | 请求超时 |
| `VISION_MAX_RETRIES` | `2` | SDK 重试次数 |

对话引擎：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CHAT_PROVIDER` | `siliconflow` | 供应商标识 |
| `CHAT_MODEL` | `Qwen/Qwen2.5-72B-Instruct` | 纯文本模型 |
| `CHAT_TEMPERATURE` | `0.7` | 采样温度 |
| `CHAT_MAX_TOKENS` | `2048` | 最大输出 token |
| `CHAT_TIMEOUT` | `60s` | 请求超时 |
| `CHAT_MAX_RETRIES` | `2` | SDK 重试次数 |
| `CHAT_MAX_HISTORY_MESSAGES` | `20` | 最多携带的历史消息条数 |

数据库：

| 变量 | 默认值 |
| --- | --- |
| `DB_HOST` | `127.0.0.1` |
| `DB_PORT` | `5432` |
| `DB_NAME` | `geometry` |
| `DB_USERNAME` | `postgres` |
| `DB_PASSWORD` / `PG_PWD` | 空 |
| `DB_SCHEMA` | `geometry` |
| `DB_POOL_MAX_SIZE` | `10` |
| `DB_POOL_MIN_IDLE` | `2` |

### 5. 数据库初始化

先创建数据库和 schema（PostgreSQL 18）：

```sql
CREATE DATABASE geometry;
\c geometry
CREATE SCHEMA IF NOT EXISTS geometry;
```

应用默认连接参数：

```yaml
url: jdbc:postgresql://127.0.0.1:5432/geometry
username: postgres
password: ${DB_PASSWORD:${PG_PWD:}}
```

如果需要应用启动时自动执行 `src/main/resources/db/init.sql`，可以设置：

```yaml
spring:
  sql:
    init:
      mode: always
```

生产环境建议使用 Flyway / Liquibase，不建议依赖 `spring.sql.init`。

### 6. 上传限制

```yaml
spring.servlet.multipart.max-file-size: 10MB
spring.servlet.multipart.max-request-size: 12MB
```

可通过 `UPLOAD_MAX_FILE_SIZE`、`UPLOAD_MAX_REQUEST_SIZE` 覆盖。

### 7. 日志配置

日志格式、控制台输出和文件滚动统一在 `src/main/resources/logback-spring.xml` 中配置。

输出示例：

```text
2026-10-26 15:30:00.123 INFO [nio-9876-exec-1] c.x.g.service.ChatService - chat ok provider=siliconflow model=Qwen/Qwen2.5-72B-Instruct history=0 cost=32ms
```

- 时间、级别、线程、类名和消息之间只保留一个空格；
- 使用 `%level` 而不是 `%-5level`，避免级别后面的补位空格；
- 控制台和文件使用同一套格式；
- 业务日志统一使用简洁的 `key=value`，不再拼接多余标点和空格；
- 文件默认写入 `logs/geometry-helper-backend.log`，单文件 20MB 滚动，保留 7 天，总量上限 200MB；
- 文件目录可通过 `LOG_PATH` 环境变量或 `--logging.file.path=...` 覆盖；
- 日志级别在 `application.yaml` 的 `logging.level` 中配置，默认将 `org.springframework.ai` 调为 `WARN`，减少 SDK 日志噪音。

---

## 四、编译、运行与验证

```bash
# 编译
.\mvnw.cmd -B -DskipTests compile

# 运行测试
.\mvnw.cmd -B test

# 打包
.\mvnw.cmd -B -DskipTests package
```

启动后验证：

```bash
# 健康检查
curl.exe http://localhost:9876/api/ping

# 普通对话（单轮）
curl.exe -X POST http://localhost:9876/api/chat ^
  -H "Content-Type: application/json" ^
  -d "{\"message\":\"你好\"}"

# 普通对话（多轮）
curl.exe -X POST http://localhost:9876/api/chat ^
  -H "Content-Type: application/json" ^
  -d "{\"message\":\"那面积是多少？\",\"history\":[{\"role\":\"user\",\"content\":\"底是 6，高是 4\"},{\"role\":\"assistant\",\"content\":\"面积是 12\"}]}"

# 图片理解
curl.exe -X POST http://localhost:9876/api/solve ^
  -F "file=@D:/test.png"
```

Windows PowerShell 中建议使用 `curl.exe`，避免 `curl` 被解析成
`Invoke-WebRequest` 别名。

本次已实际验证：

- `mvnw -B -DskipTests compile`：BUILD SUCCESS
- `mvnw test`：2 个测试通过，Spring 上下文正常启动，两个 ChatClient 为独立实例
- `GET /api/ping`：200，时间格式正确
- `POST /api/chat` + 本地模拟 OpenAI 兼容服务：200，使用对话模型
- `POST /api/solve` + 本地模拟 OpenAI 兼容服务：200，使用视觉模型并携带图片
- 两个引擎同时调用：mock 日志中分别出现 `chat-test` 和 `vision-test` 两个模型
- 缺少 `message`：400；缺少 `file`：400；上游不可用：502

---

## 五、关键实现说明

### 1. 为什么手动创建两个 ChatModel

Spring AI 的 OpenAI starter 默认只根据 `spring.ai.openai.*` 自动创建一个
`OpenAiChatModel`，无法同时表达“视觉模型 A + 对话模型 B”这种组合。

因此项目中：

1. 在 `application.yaml` 中设置 `spring.ai.model.chat=none`，关闭单模型自动配置；
2. 设置 `spring.ai.chat.client.enabled=false`，关闭自动 `ChatClient.Builder`；
3. 在 `AiConfig` 中根据 `app.ai.vision` 和 `app.ai.chat` 分别构建两个
   `OpenAiChatModel`；
4. 再分别构建 `visionChatClient` 和 `chatChatClient` 两个 `ChatClient`。

这样两个引擎的 endpoint、API Key、模型、超时完全解耦。

### 2. 为什么不再用 Spring Profile 切换供应商

Profile 是全局开关：激活 `ofox` 后，所有读取该 Profile 的配置都会切到 Ofox，
很难表达“视觉用 Ofox、对话用硅基流动”这种混合场景。

现在改为：

- 供应商连接信息放在 `app.ai.providers`；
- 每个引擎用 `provider` 字段选择供应商；
- 用 `VISION_PROVIDER` / `CHAT_PROVIDER` 环境变量可以分别覆盖。

### 3. 为什么图片用 `Media`

Spring AI 的 `Media` 是模型无关的多媒体抽象，具体实现会负责把 `byte[]`
转成对应协议需要的格式。后续换视觉模型时业务代码不用改。

### 4. 多轮对话为什么由客户端传 history

当前服务端不保存会话状态，`/api/chat` 是无状态的。这样做的好处是：

- 服务可以水平扩展，不依赖本地内存或粘性会话；
- 客户端可以自由决定携带哪些历史；
- 后续如果需要服务端记忆，可以再接入 `ChatMemory`、Redis 或数据库。

### 5. `search_path` 是怎么生效的

`application.yaml` 中同时配置了：

```yaml
spring.datasource.hikari.schema: geometry
spring.datasource.hikari.connection-init-sql: SET search_path TO geometry
```

- `schema` 会让 JDBC 驱动在获取连接时设置 schema；
- `connection-init-sql` 会在每个新连接创建后执行，作为双保险。

这样后续用 `JdbcTemplate` 写 SQL 时，不写 schema 前缀也会默认落在
`geometry` 下。

---

## 六、后续如何细化（建议按优先级）

### P0：先让接口真正可用

1. **配置真实 Key 和模型**
   - 视觉引擎必须选择支持图片输入的模型；
   - 对话引擎可以选择成本更低的纯文本模型；
   - 先用 `curl` 分别验证两个供应商的 `/chat/completions`。

2. **调整提示词**
   - 视觉提示词在 `app.ai.vision.user-prompt`；
   - 对话人设在 `app.ai.chat.system-prompt`；
   - 改完重启即可，不需要改 Java 代码。

3. **校验真实图片格式**
   - 当前主要依赖 `Content-Type` 和扩展名；
   - 生产环境建议读取文件头（magic bytes）校验 PNG/JPEG/WebP/GIF。

### P1：工程化能力

4. **持久化调用记录**
   - 在 `geometry` schema 下建表，保存请求类型、供应商、模型、耗时、token 用量等；
   - 不要把图片二进制直接存数据库，建议存对象存储路径。

5. **数据库迁移**
   - 引入 Flyway 或 Liquibase，替代手写 `init.sql`。

6. **接口文档**
   - 引入 `springdoc-openapi`，自动生成 Swagger UI；
   - 为 `/api/solve`、`/api/chat`、错误码补充示例。

7. **参数校验**
   - 增加 `spring-boot-starter-validation`；
   - 对 `message`、`history` 长度、文件大小做统一校验；
   - 把错误码设计成枚举，而不是直接返回中文文案。

### P2：稳定性与安全

8. **多供应商容错**
   - 主供应商失败时自动切换备用供应商；
   - 可以为每个引擎配置主备两个 provider；
   - 配合 Resilience4j 做超时、重试、熔断、限流。

9. **安全**
   - 增加 Spring Security / JWT 鉴权；
   - 增加上传和对话频率限制，防止刷 token；
   - 配置 CORS，只允许可信前端域名；
   - 日志中不要打印 API Key、完整图片内容或模型返回的敏感信息。

10. **可观测性**
    - 接入 Micrometer + Prometheus；
    - 记录每次调用的耗时、token 用量、供应商、模型、成功率；
    - 增加 traceId，方便排查一次请求的完整链路。

11. **异步化**
    - 图片理解耗时较长，如果前端不能等待，可改成任务 ID + 轮询；
    - 可使用 Spring 的 `@Async`、RabbitMQ/Kafka 或数据库任务表。

12. **测试**
    - Controller 层：MockMvc 测试 200、400、413、415、502；
    - Service 层：Mock `ChatClient`，验证两个引擎不会串用；
    - 集成测试：Testcontainers 启动 PostgreSQL 18，验证 `search_path`；
    - AI 调用测试建议使用 WireMock 模拟 OpenAI 兼容接口。

---

## 七、已知注意事项

1. **API Key 为空时应用仍能启动**  
   Spring AI 会以 no-auth 模式创建客户端，但实际调用会失败。
   这是刻意设计，方便本地只验证 `/api/ping` 和编译。

2. **两个引擎可以同时使用不同供应商**  
   `vision.provider` 和 `chat.provider` 分别生效，互不覆盖。
   例如视觉用 Ofox、对话用硅基流动是合法配置。

3. **供应商模型会更新**  
   `application.yaml` 中的默认模型只是示例，请以供应商控制台当前可用的
   模型为准。模型下架或改名时只需要改配置。

4. **图片大小与 token 成本**  
   图片越大，视觉 token 越多。生产环境建议在上传后做尺寸限制和压缩。

5. **数据库默认不连接**  
   Hikari 是懒加载的，当前接口不访问数据库时不会建立连接。
   一旦开始使用 `JdbcTemplate`，请确保 PostgreSQL 18 已启动、库和 schema 已创建。
