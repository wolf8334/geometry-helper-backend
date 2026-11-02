# geometry-helper-backend

基于 Spring Boot 4.1.2 + Spring AI 2.0.1 的几何助手后端。

项目**同时运行三个独立的 AI 引擎**：

| 引擎 | 配置前缀 | 用途 | 模型要求 |
| --- | --- | --- | --- |
| 视觉理解引擎（VL / Vision） | `app.ai.vision` | `POST /api/solve` 题目复述 | 必须支持图片输入 |
| 解题引擎（Solve） | `app.ai.solve` | `POST /api/solve` 分步讲解 | 建议支持图片输入 |
| 普通对话引擎（Chat） | `app.ai.chat` | `POST /api/chat` 文本对话 | 纯文本模型即可 |

三个引擎各自选择供应商和模型，可以同时使用不同供应商、不同 API Key、
不同 endpoint。例如：视觉引擎用 DeepSeek，解题引擎用 Ofox 的 Anthropic
模型，对话引擎用成本更低的文本模型。

---

## 一、接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/ping` | 返回服务状态和当前服务器时间 |
| POST | `/api/solve` | 接收 `multipart/form-data` 图片（字段名 `file`），SSE 流式返回题目复述和三段讲解 |
| POST | `/api/chat` | 接收 JSON 文本消息，SSE 流式返回普通对话，支持多轮历史 |
| GET | `/api/history` | 分页查询历史题目，支持日期、关键词、是否有追问筛选 |
| GET | `/api/history/{id}` | 查询一道题的识别、分步讲解和全部追问 |
| GET | `/api/history/{id}/image` | 读取该题保存的原始图片 |
| POST | `/api/followup` | 针对已经生成的讲解继续追问，SSE 流式返回 |

### 0. SSE 事件格式

`POST /api/solve`、`POST /api/chat` 和 `POST /api/followup` 成功时返回
`Content-Type: text/event-stream`，不再一次性返回 JSON。事件如下：

| 事件 | `data` | 说明 |
| --- | --- | --- |
| `meta` | 阶段信息 | 例如 `{"stage":"part","part":1,"totalParts":3}`，只用于页面状态，不落库 |
| `delta` | `{"text":"..."}` | 正文增量，页面边接收边显示 |
| `commit` | 与原 JSON 响应一致 | 服务端已经成功写入数据库，前端用该对象替换页面内容 |
| `error` | `{"message":"...","retryable":true}` | 流中错误；`retryable=false` 表示参数或资源问题，不应自动重试 |
| `done` | `{}` | 正常结束 |

- 只有 `delta` 会进入页面正文；模型的思考/推理内容不会进入正文或历史记录，等待阶段只在正文区域显示行走动画，不再显示“正在思考”文字；
- 流式显示时正文按纯文本增量刷新，公式在收尾符号到达后立即用 KaTeX 排版；还没闭合的半截公式暂不显示，避免页面上出现 `$\frac{...` 这样的原始片段；
- `commit` 发出前数据已经写入数据库，前端只在收到 `commit` 后确认成功；
- 连接在 `commit` 之前中断（网络、超时或服务端 5xx）时，前端会自动重试；
- 服务端每 10 秒发送一次 SSE 注释帧（`:ping`）作为心跳，页面上不可见；
- 文件缺失、参数错误等发生在流开始前的问题仍然是普通 JSON + 4xx 状态码。

### 0.1 等待动画

在还没有收到可展示正文时（包括模型 Thinking 阶段），识别题目、每一段讲解和
追问回复的正文区域会显示一个左右走动的芙宁娜等待动画：

- 只有 `delta` 正文增量会结束动画；`thinking` 事件不会；
- 动画是 `assets/furina-walk.webp` 的两行精灵图，用 CSS `steps(8)` 逐帧播放，
  15 秒一个来回、匀速行走，走到边界后自动切换左右朝向；
- 等待期间正文区域只有动画，不再叠加“老师正在思考…”之类的文字；
- 请求失败、超时、取消或收到完整结果时都会清理动画，不会残留；
- 页面开启 `prefers-reduced-motion` 时只显示静态角色；
- 素材来自 [furina-dsh-pet](https://github.com/kline-dev/furina-dsh-pet) /
  [petdex](https://petdex.dev/pets/furina-2)，版权归原作者，仅限个人/学习用途，
  详见 `static/assets/furina-walk.LICENSE.txt`。

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

### 2. 题目复述与分步讲解（视觉引擎 + 解题引擎）

`POST /api/solve`：

- 请求类型：`multipart/form-data`
- 文件字段：`file`
- 文字字段：`message`、`stage`、`analysis`、`part`、`history`、`questionId`
- 阶段：
  - `analyze`（默认）：视觉引擎识别并复述题目，不输出解题计划，也不询问是否确认；
  - `retry`：重新识别并复述题目；
  - `confirm`：用户确认题目复述后，解题引擎生成第 1 段讲解；
  - `continue`：携带 `analysis` 和 `part`，继续生成下一段；前文由服务端从数据库读取，
    `history` 字段仅为兼容旧前端保留，已弃用。
- 响应是 SSE 流：正文通过 `delta` 事件逐渐返回，落库成功后通过 `commit`
  事件返回下面的结果对象。
- 题目复述成功后的 `commit` 数据：

```json
{
  "msg": "题目复述完成，请核对",
  "stage": "confirm",
  "analysis": "## 题目复述\n……",
  "answer": null,
  "part": 0,
  "totalParts": 3,
  "done": false,
  "questionId": 1
}
```

- 分步讲解成功后的 `commit` 数据：

```json
{
  "msg": "已生成新内容，先想一想，再继续",
  "stage": "part",
  "analysis": null,
  "answer": "### 先找切入点\n……",
  "part": 1,
  "totalParts": 3,
  "done": false,
  "questionId": 1
}
```

首次识别成功后，`commit` 事件会返回 `questionId`。后续 `retry`、`confirm`、`continue`
和 `/api/followup` 都要带回该 ID；旧版前端没有携带时，服务端会根据图片和
题目复述补建一条记录。

处理流程：

1. Controller 在请求线程内把 `MultipartFile` 读成 `UploadedImage`，避免请求结束后
   multipart 临时文件被清理；
2. Service 校验文件非空、MIME 类型为图片；
3. `ImageProcessingService` 统一做图片预处理：Base64 后仍低于 5MB 的 JPG/PNG/WebP/GIF 直接使用，
   BMP/TIFF 或超过限制的图片会缩放并转成 JPEG，确保实际发送内容小于 5MB；
4. 视觉阶段只生成题目复述，不提前给解题计划，正文增量通过 SSE 实时返回；
5. 用户确认后，解题阶段每轮只生成一段，并把题目复述和已生成的前文一起发送；
6. 第三段完成后返回 `done=true`；
7. 解题提示词会注入 `app.ai.solve.knowledge-scope` 配置的知识范围，当前默认只允许使用中国国内初二及以前各学科知识（不限于数学）；
8. 识别成功时创建 `geometry_question`，并写入 `recognition_request`、`recognition_result`；
9. 每生成一段讲解，追加 `solution_request`、`solution_result`，并记录 `step_no`；
10. 追问写入 `followup_question`、`followup_answer`，同一道题只有一条追问线程；
11. 所有结果都先写库，再发送 `commit` 事件；写库失败时前端不会把这次请求当作成功。

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

- 响应是 SSE 流；`commit` 事件的数据：

```json
{
  "msg": "对话成功",
  "answer": "你好，我是普通对话引擎……"
}
```

说明：

- 正文通过 `delta` 事件实时返回，页面不会等待整段回答生成完才显示；
- 服务端不保存会话状态，多轮对话由客户端把 `history` 带上来；
- `history.role` 只支持 `user` 和 `assistant`；
- 默认只保留最近 `app.ai.chat.max-history-messages` 条历史，防止 token 无限增长；
- 视觉引擎和对话引擎互不影响，可以同时调用。

### 4. 历史记录与追问

主页面：

- 识别成功并生成至少一段讲解后，右下角出现“追问”浮动按钮；
- 点击按钮打开追问遮罩层，自动加载这道题之前的追问记录；
- 追问时会把题目复述、已生成讲解、之前追问和用户当前展开到的段落一起发送给解题引擎；
- 原图是否一并发送遵循 `app.ai.solve.include-image` 配置，默认不携带（只依据用户确认过的题目复述）；
- 每道题默认最多追问 3 次，可通过 `app.followup.max-questions` 或环境变量
  `FOLLOWUP_MAX_QUESTIONS` 调整；达到上限后前端会禁用输入，服务端也会拒绝新的追问；
- 追问记录取“本次请求段落”和“此前追问线程最大段落”的较大值，但不会超过已生成的最大段落；
- 追问回复同样通过 SSE 的 `delta` 事件实时显示，落库成功后才发送 `commit`；
- 网络中断、请求超时或服务端 5xx 时，前端会自动重试，并在进度区显示“正在自动重试”；
  图片格式、大小等需要用户处理的 4xx 错误仍会给出明确提示。

`list.html`：

- 展示题目原图、题目复述、分段讲解和追问记录；
- 点击题目原图会在当前页面打开浮层预览，点击空白区域、关闭按钮或按 Esc 退出，不会跳转新页面；
- 卡片顶部只显示时间、是否保存原图和已生成讲解段数，不显示图片文件名；
- 支持按开始日期、结束日期、关键词、是否有追问筛选；
- 分页参数为 `page`、`size`，`size` 最大 50；
- 筛选条件会同步到 URL，刷新后仍然保留；
- 列表加载遇到网络异常或服务端 5xx 时会在后台自动重试，不需要用户反复点击查询。

历史列表请求示例：

```text
GET /api/history?from=2026-10-01&to=2026-10-31&keyword=三角形&followup=true&page=1&size=10
```

追问请求：

```json
{
  "questionId": 1,
  "message": "为什么这里要作这条辅助线？",
  "step": 2
}
```

追问成功后的 `commit` 数据：

```json
{
  "msg": "追问已回答",
  "questionId": 1,
  "step": 2,
  "answer": "### 这一步的依据\n……",
  "modelName": "anthropic/claude-haiku-5.5",
  "createdAt": "2026-10-28T17:30:00+08:00",
  "followupStep": 2
}
```

### 5. 异常处理

流开始前（尚未返回 HTTP 200）的错误统一返回 JSON，避免把堆栈暴露给调用方：

| 场景 | HTTP 状态码 | `msg` 示例 |
| --- | --- | --- |
| 文件超过大小限制 | 413 | 上传文件过大，请压缩图片后重试 |
| 缺少 `file` 字段 | 400 | 缺少文件字段 file，请使用 multipart/form-data 上传图片 |
| 空文件 / 非图片 | 400 | 上传文件不能为空 / 无法识别图片格式…… |
| JSON 格式错误 | 400 | 请求体格式错误，请检查 JSON 格式和字段类型 |
| 不支持的 Content-Type | 415 | 不支持的 Content-Type，请使用 application/json 或 multipart/form-data |
| 不支持的请求方法 | 405 | 不支持的请求方法，请检查请求方式 |
| 历史查询题目不存在 | 404 | 未找到对应题目 |

流开始后的错误通过 SSE `error` 事件返回（此时 HTTP 状态码已经是 200）：

| 场景 | `retryable` | `message` 示例 |
| --- | --- | --- |
| 题目不存在或不属于当前用户 | `false` | 未找到对应题目 |
| 追问次数已达上限 | `false` | 每道题最多追问 3 次，已达到上限 |
| 请求参数有误 | `false` | 请求参数有误，请检查后重试 |
| 模型调用失败或超时 | `true` | 大模型服务调用失败，请检查 API Key、模型名称或网络后重试 |
| 写入数据库失败 | `true` | 服务器内部错误，请稍后重试 |

前端只对 `retryable=true` 的错误自动重试；`retryable=false` 的错误会显示服务端返回的
具体提示。`IllegalArgumentException` 和 `HttpRequestMethodNotSupportedException`
属于正常的错误请求分支：直接返回 JSON 响应，不记录日志，也不把异常继续抛给容器。

### 6. 数据库

- 已加入 `spring-boot-starter-jdbc` 和 PostgreSQL JDBC 驱动；
- 数据源默认连接 `geometry` 数据库；
- 通过 Hikari 的 `schema` 和 `connection-init-sql` 保证每个连接执行
  `SET search_path TO geometry`；
- 默认不自动建表，避免本地没有数据库时启动失败；
- 初始化脚本位于 `src/main/resources/db/init.sql`，可重复执行；
- 当前没有登录功能，所有记录统一归属 `user_id = 0` 的匿名用户。

首次使用前执行：

```powershell
psql -h 127.0.0.1 -U postgres -d geometry -f src/main/resources/db/init.sql
```

持久化表：

| 表 | 作用 |
| --- | --- |
| `geometry_user` | 用户；启动后首次写入时确保 `id=0` 的匿名用户存在 |
| `geometry_question` | 题目主记录，保存原始图片、MIME 类型、文件名和题目复述 |
| `geometry_message` | 识别、分步讲解和追问的全部请求/响应消息 |

`geometry_message` 通过以下字段区分内容：

- `phase`：`recognize`、`solve`、`followup`；
- `message_type`：`recognition_request`、`recognition_result`、
  `solution_request`、`solution_result`、`followup_question`、`followup_answer`；
- `step_no`：讲解或追问关联的段落，从 1 开始；
- `sequence_no`：同一道题内的消息顺序，唯一且连续；
- `extra`：JSONB，保存用户补充说明、阶段、停止原因等附加信息。

同一道题只有一条追问线程，不额外创建追问主表。多行写入使用事务，并在分配
`sequence_no` 前锁定题目行，避免并发时产生重复序号。

---

## 二、目录结构

```text
src/main/java/com/xhr/geometryhelperbackend
├── GeometryHelperBackendApplication.java   # 启动类
├── config
│   ├── AiConfig.java                       # 三引擎 ChatModel / ChatClient
│   ├── AiProperties.java                   # app.ai.* 配置绑定
│   ├── FollowupProperties.java             # app.followup.* 追问次数配置
│   └── StreamingConfig.java                # 流式任务线程池和 SSE 心跳调度器
├── controller
│   ├── PingController.java                 # GET /api/ping
│   ├── SolveController.java                # POST /api/solve（视觉/解题引擎 + 持久化）
│   ├── ChatController.java                 # POST /api/chat（对话引擎）
│   ├── HistoryController.java              # GET /api/history、/api/history/{id}、原图
│   └── FollowupController.java             # POST /api/followup
├── dto
│   ├── ChatMessage.java
│   ├── ChatRequest.java
│   ├── ChatResponse.java
│   ├── ErrorResponse.java
│   ├── PingResponse.java
│   ├── SolveResponse.java
│   ├── HistoryPage.java                    # 历史分页结果
│   ├── QuestionDetail.java                 # 题目完整历史
│   ├── HistoryMessage.java                 # 消息记录
│   ├── SolutionPart.java                   # 分步讲解
│   ├── FollowupMessage.java                # 追问消息
│   ├── FollowupRequest.java                # 追问请求
│   └── FollowupAnswerResponse.java         # 追问响应
├── exception
│   ├── AiServiceException.java
│   └── GlobalExceptionHandler.java
└── service
    ├── ImageSolveService.java              # 图片校验 + 视觉/解题/追问流式调用
    ├── ImageProcessingService.java         # 5MB 限制、缩放、格式转换、JPEG 压缩
    ├── ModelStreamCaller.java              # 消费模型流、过滤思考内容、累积正文
    ├── UploadedImage.java                  # 请求线程内读入内存的上传图片
    ├── SseStreamSession.java               # SSE 事件、心跳、错误和关闭
    ├── SseStreamFactory.java               # 创建 SSE 会话
    ├── StreamTaskRunner.java               # 在虚拟线程中执行流式业务
    ├── StreamClosedException.java          # 浏览器断开时终止模型流
    ├── QuestionHistoryService.java         # PostgreSQL 持久化 + 历史查询
    ├── ModelCallResult.java                # 模型正文和调用元数据
    └── ChatService.java                    # 文本对话 + 历史消息处理

src/test/java/com/xhr/geometryhelperbackend
├── GeometryHelperBackendApplicationTests.java  # 三引擎上下文与协议切换测试
├── config/AiProtocolSelectionTests.java        # 视觉/解题/对话协议自动选择
└── service/ImageProcessingServiceTests.java    # 图片压缩和格式转换
```

配置文件：

```text
src/main/resources
├── application.yaml                # 三引擎、双供应商、数据库、上传限制、日志级别
├── logback-spring.xml              # 简洁日志格式、控制台 + 文件滚动
├── db/init.sql                     # 可选的 schema 初始化脚本
└── static
    ├── index.html                  # 图片识别、分步讲解、追问浮层
    ├── list.html                   # 历史记录页
    ├── css/app.css                 # 主页面和追问浮层样式
    ├── css/list.css                # 历史记录页样式
    ├── js/app.js                   # 主页面交互
    ├── js/list.js                  # 历史筛选和渲染
    ├── js/net.js                   # 共享的自动重试和 SSE 流解析
    ├── js/markdown.js              # 共享的 Markdown + KaTeX 渲染
    ├── js/furina-wait.js           # AI 等待阶段的芙宁娜行走动画
    ├── assets/furina-walk.webp     # 行走精灵图（两行，来源见同目录 LICENSE）
    └── assets/furina-walk.LICENSE.txt
```

---

## 三、配置与启动

### 1. 环境要求

- JDK 25+
- Maven 3.9+（或直接使用项目自带的 `mvnw`）
- PostgreSQL 18（数据源懒加载，但解题、历史和追问功能需要可用的数据库）

> 注意：`pom.xml` 中 `java.version=25`，运行时必须使用 JDK 25 及以上。
> 如果命令行里的 `java -version` 和 `mvn -v` 显示的版本不一致，请先设置
> `JAVA_HOME`，确保 Maven、IDE 和 `java -jar` 使用同一个 JDK。

### 2. 三引擎配置

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
        anthropic-base-url: https://api.ofox.ai/anthropic
        api-key: ${OFOX_API_KEY:}

    vision:
      provider: siliconflow
      model: Qwen/Qwen2.5-VL-72B-Instruct
      timeout: 120s
      # system-prompt / user-prompt ...

    solve:
      provider: ofox
      model: anthropic/claude-haiku-5.5
      include-image: false
      knowledge-scope: 中国国内初二及以前各学科知识（不限于数学）
      # system-prompt ...

    chat:
      provider: siliconflow
      model: Qwen/Qwen2.5-72B-Instruct
      timeout: 60s
      max-history-messages: 20
      # system-prompt ...
```

- `providers` 只保存供应商的连接信息（endpoint、API Key）；
- `vision.provider`、`solve.provider` 和 `chat.provider` 分别选择供应商；
- 三个引擎的模型、温度、token 上限、超时、重试次数都独立配置；
- 只要某个引擎的 `provider=ofox` 且 `model` 以 `anthropic` 开头，该引擎就会自动使用 Anthropic Messages 协议，并读取 `ofox.anthropic-base-url`；
- `solve.knowledge-scope` 控制讲解时允许使用的知识范围，当前默认是中国国内初二及以前各学科知识（不限于数学）。

### 3. 混合供应商示例

视觉和对话引擎都使用硅基流动（解题引擎保持默认 Ofox 配置）：

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
| `OFOX_BASE_URL` | `https://api.ofox.ai/v1` | Ofox OpenAI 兼容 endpoint |

视觉引擎：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `VISION_PROVIDER` | `ofox` | 供应商标识 |
| `VISION_MODEL` | `anthropic/claude-haiku-5.5` | 必须支持图片输入 |
| `VISION_TEMPERATURE` | `0.2` | 采样温度 |
| `VISION_MAX_TOKENS` | `65536` | 最大输出 token |
| `VISION_TIMEOUT` | `600s` | 请求超时 |
| `VISION_MAX_RETRIES` | `2` | SDK 重试次数 |
| `VISION_THINKING` | `off` | 思考模式 `auto`/`off`/`adaptive`（仅 Anthropic 协议） |

解题引擎：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SOLVE_PROVIDER` | `ofox` | 供应商标识 |
| `SOLVE_MODEL` | `anthropic/claude-haiku-5.5` | 建议支持图片输入 |
| `SOLVE_TEMPERATURE` | `0.2` | 采样温度 |
| `SOLVE_MAX_TOKENS` | `65536` | 单段最大输出 token |
| `SOLVE_INCLUDE_IMAGE` | `false` | 讲解和追问时是否携带原图 |
| `SOLVE_EFFORT` | `low` | 解题思考强度（low/medium/high/xhigh/max，留空不发送，仅 Anthropic 协议） |
| `SOLVE_THINKING` | `off` | 思考模式 `auto`/`off`/`adaptive`（仅 Anthropic 协议） |
| `SOLVE_KNOWLEDGE_SCOPE` | `中国国内初二及以前各学科知识（不限于数学）` | 允许使用的知识范围 |

追问：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `FOLLOWUP_MAX_QUESTIONS` | `3` | 每道题最多允许的追问次数；设为 `0` 可关闭追问 |

对话引擎：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CHAT_PROVIDER` | `ofox` | 供应商标识 |
| `CHAT_MODEL` | `anthropic/claude-haiku-5.5` | 纯文本模型 |
| `CHAT_TEMPERATURE` | `0.1` | 采样温度 |
| `CHAT_MAX_TOKENS` | `65536` | 最大输出 token |
| `CHAT_TIMEOUT` | `120s` | 请求超时 |
| `CHAT_MAX_RETRIES` | `2` | SDK 重试次数 |
| `CHAT_THINKING` | `auto` | 思考模式 `auto`/`off`/`adaptive`（仅 Anthropic 协议） |
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
| `DB_POOL_CONNECTION_TIMEOUT` | `30000` |
| `DB_POOL_VALIDATION_TIMEOUT` | `5000` |
| `DB_POOL_KEEPALIVE_TIME` | `60000` |
| `DB_POOL_IDLE_TIMEOUT` | `300000` |
| `DB_POOL_MAX_LIFETIME` | `600000` |

`keepalive-time` 会定期检测空闲连接，`max-lifetime` 会强制回收旧连接。
如果数据库、PgBouncer 或云数据库代理的 idle timeout 更短，需要把这两个值一起调小，
并保证 `keepalive-time < max-lifetime`。

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

也可以直接执行仓库里的初始化脚本：

```powershell
psql -h 127.0.0.1 -U postgres -d geometry -f src/main/resources/db/init.sql
```

如果希望应用启动时自动执行：

```yaml
spring:
  sql:
    init:
      mode: always
      schema-locations: classpath:db/init.sql
```

生产环境建议使用 Flyway / Liquibase，不建议依赖 `spring.sql.init`。

### 6. 上传限制

```yaml
spring.servlet.multipart.max-file-size: 10MB
spring.servlet.multipart.max-request-size: 12MB
```

可通过 `UPLOAD_MAX_FILE_SIZE`、`UPLOAD_MAX_REQUEST_SIZE` 覆盖。

这两个值只控制 Spring 接收上传的请求大小。Bedrock / Claude 对单张图片还有
**5MB 的硬限制**，由 `ImageProcessingService` 在调用模型前处理：

- 原图小于 5MB 且是 JPG/PNG/WebP/GIF、Base64 后仍低于 5MB 时直接使用；
- 由于 Base64 会膨胀约 1/3，实际把原始图片控制在 3.7MB 以内，并给 data URI 和 JSON 留出余量；
- BMP/TIFF 会转换为模型支持的格式；
- 需要压缩时先把最长边缩放到 2400 像素，再以 JPEG 质量 0.85 编码；
- 如果仍然超过限制，依次降低质量、降低分辨率，直到满足限制；
- 原始上传字节不被修改，压缩结果只用于本次模型调用。

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

# 普通对话（单轮，SSE 流式输出）
curl.exe -N -X POST http://localhost:9876/api/chat ^
  -H "Content-Type: application/json" ^
  -H "Accept: text/event-stream" ^
  -d "{\"message\":\"你好\"}"

# 普通对话（多轮，SSE 流式输出）
curl.exe -N -X POST http://localhost:9876/api/chat ^
  -H "Content-Type: application/json" ^
  -H "Accept: text/event-stream" ^
  -d "{\"message\":\"那面积是多少？\",\"history\":[{\"role\":\"user\",\"content\":\"底是 6，高是 4\"},{\"role\":\"assistant\",\"content\":\"面积是 12\"}]}"

# 图片理解（SSE 流式输出）
curl.exe -N -X POST http://localhost:9876/api/solve ^
  -H "Accept: text/event-stream" ^
  -F "file=@D:/test.png"
```

`-N` 关闭 curl 的输出缓冲，便于观察 `event: delta` 和 `event: commit` 帧。
Windows PowerShell 中建议使用 `curl.exe`，避免 `curl` 被解析成
`Invoke-WebRequest` 别名。

本次已实际验证：

- `mvnw -B -DskipTests compile`：BUILD SUCCESS
- `mvnw test`：12 个测试全部通过，Spring 上下文正常启动，三个 ChatClient 为独立实例
- `GET /api/ping`：200，时间格式正确
- `POST /api/chat` + 本地模拟 OpenAI 兼容服务：200，使用对话模型
- `POST /api/solve` + 本地模拟 OpenAI 兼容服务：200，使用视觉模型并携带图片
- 三个引擎同时调用：mock 日志中分别出现 `chat-test` 和 `vision-test` 两个模型
- 缺少 `message`：400；缺少 `file`：400；上游不可用：SSE `error` 事件
- `node --check`：`app.js`、`list.js`、`net.js`、`markdown.js`、`sw.js` 语法检查通过
- HTML 静态检查：`index.html`、`list.html` 无重复 ID，JavaScript 引用的 ID 均存在
- SSE 真实浏览器链路需要配置可用的 PostgreSQL 和模型 API Key 后手动验证；
  `commit` 之前断线会自动重试，`commit` 之后按成功处理

---

## 五、关键实现说明

### 1. 为什么手动创建三个 ChatModel

Spring AI 的 OpenAI starter 默认只根据 `spring.ai.openai.*` 自动创建一个
`OpenAiChatModel`，无法同时表达“视觉模型 A + 对话模型 B”这种组合。

因此项目中：

1. 在 `application.yaml` 中设置 `spring.ai.model.chat=none`，关闭单模型自动配置；
2. 设置 `spring.ai.chat.client.enabled=false`，关闭自动 `ChatClient.Builder`；
3. 在 `AiConfig` 中根据 `app.ai.vision` 和 `app.ai.chat` 分别构建两个
   `OpenAiChatModel`；
4. 再分别构建 `visionChatClient` 和 `chatChatClient` 两个 `ChatClient`。

这样三个引擎的 endpoint、API Key、模型、超时完全解耦。

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

传给 `Media` 的是 `ImageProcessingService` 处理后的图片字节和真实 MIME 类型，
不是原始文件的 Base64 字符串。Spring AI / Anthropic SDK 会在请求序列化时再做
Base64 编码，因此业务层不能重复编码。

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

### 6. 流式响应为什么先入库再 `commit`

模型生成过程中会不断发送 `delta`，但此时回答还没有写完。如果直接在前端认为
成功并写入历史，一旦模型在中途报错，就会留下不完整的记录。

因此服务端把流式响应分成两个阶段：

1. 模型流式生成时只发送 `delta`，正文在内存中累积；
2. 模型完整结束后再写数据库，写入成功才发送 `commit`。

前端以 `commit` 作为唯一成功标志。`commit` 之前的网络错误会自动重试；
`commit` 之后即使 `done` 帧丢失，也按成功处理，避免重复生成和重复入库。

`SseStreamSession` 每 10 秒发送一次注释帧作为心跳，并给每条连接设置 10 分钟
超时。浏览器断开后，下一次写 SSE 会抛出 `StreamClosedException`，从而取消
模型流，避免继续消耗模型额度。

---

## 六、后续如何细化（建议按优先级）

### P0：先让接口真正可用

1. **配置真实 Key 和模型**
   - 视觉引擎必须选择支持图片输入的模型；
   - 对话引擎可以选择成本更低的纯文本模型；
   - 先用 `curl` 分别验证两个供应商的 `/chat/completions`。

2. **调整提示词**
   - 视觉提示词在 `app.ai.vision.user-prompt`；
   - 解题人设和知识范围在 `app.ai.solve.system-prompt`、`app.ai.solve.knowledge-scope`；
   - 对话人设在 `app.ai.chat.system-prompt`；
   - 改完重启即可，不需要改 Java 代码。

3. **校验真实图片格式**
   - 当前主要依赖 `Content-Type` 和扩展名，并支持 JPG/PNG/WebP/GIF/BMP/TIFF；
   - WebP 通过 TwelveMonkeys ImageIO 插件读取；
   - 生产环境仍建议读取文件头（magic bytes）做更严格的格式校验。

### P1：工程化能力

4. **完善持久化记录**
   - 当前已保存原始图片、题目复述、模型请求/响应、分步讲解和追问；
   - 后续可增加按供应商、模型、耗时和 token 用量的统计查询；
   - 图片目前直接存 `bytea`，数据量增大后可迁移到对象存储，只在数据库中保留路径。

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
    - Service 层：Mock `ChatClient`，验证三个引擎不会串用；
    - 集成测试：Testcontainers 启动 PostgreSQL 18，验证 `search_path`；
    - AI 调用测试建议使用 WireMock 模拟 OpenAI 兼容接口。

---

## 七、已知注意事项

1. **API Key 为空时应用仍能启动**  
   Spring AI 会以 no-auth 模式创建客户端，但实际调用会失败。
   这是刻意设计，方便本地只验证 `/api/ping` 和编译。

2. **三个引擎可以同时使用不同供应商**
   `vision.provider` 和 `chat.provider` 分别生效，互不覆盖。
   例如视觉用 Ofox、对话用硅基流动是合法配置。

3. **供应商模型会更新**  
   `application.yaml` 中的默认模型只是示例，请以供应商控制台当前可用的
   模型为准。模型下架或改名时只需要改配置。

4. **图片大小与 token 成本**  
   图片越大，视觉 token 越多。当前已自动把最长边限制在 2400 像素，
   并在必要时压缩到 5MB 以下；后续仍可根据模型价格进一步下调尺寸。

5. **数据库连接**

   Hikari 是懒加载的，但解题、历史和追问接口都会读写数据库。
   使用这些功能前，请确保 PostgreSQL 18 已启动，库、schema 和
   `src/main/resources/db/init.sql` 中的表已创建。
