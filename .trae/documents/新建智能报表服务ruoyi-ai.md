# 新建智能报表服务模块 ruoyi-ai

## Context（背景与目标）

前端 [BACKEND_API_SPEC.md](file:///g:/code/agentReport/ruoyi-cloude-vue2/src/views/application/agentReport/BACKEND_API_SPEC.md) 已就绪，需要后端实现 **一个** 接口 `POST /ai/chat`：前端把用户中文指令 + 当前表格 context 发来，后端调用大模型（Kimi/Moonshot）解析成结构化 JSON `actions` 数组返回，前端执行器照单全收执行表格操作。

需要新建一个完整的若依微服务模块 `ruoyi-ai`，包含：
- 网关路由（`/ai/**` → `lb://ruoyi-ai`）
- Kimi 大模型调用（OpenAI 兼容格式）
- 会话/消息持久化到 MySQL（ry-cloud 库）
- 降级策略（模型不可用返回 `code=503`，前端自动回退本地正则解析）

用户已确认：
- 大模型选型：**Kimi (Moonshot AI)**，OpenAI 兼容格式，端点 `https://api.moonshot.cn/v1/chat/completions`
- 持久化方案：**MySQL**，在 ry-cloud 主库新建 `ai_conversation`、`ai_message` 两张表
- 模块命名：模块名 `ruoyi-ai`，应用名 `ruoyi-ai`，端口 `9301`

## 参考模板

| 模板来源 | 路径 | 用途 |
|------|------|------|
| 带数据源模块 | [ruoyi-modules/ruoyi-system](file:///g:/code/agentReport/ruoyi-cloude-service/ruoyi-modules/ruoyi-system) | pom 依赖、启动类、bootstrap.yml、logback.xml 模板 |
| 最简模块 | [ruoyi-modules/ruoyi-file](file:///g:/code/agentReport/ruoyi-cloude-service/ruoyi-modules/ruoyi-file) | 启动类 ASCII banner、Controller 风格 |
| 统一返回 | [R.java](file:///g:/code/agentReport/ruoyi-cloude-service/ruoyi-common/ruoyi-common-core/src/main/java/com/ruoyi/common/core/domain/R.java) | `R.ok(data)` / `R.fail(code,msg)` |
| 模块聚合 | [ruoyi-modules/pom.xml](file:///g:/code/agentReport/ruoyi-cloude-service/ruoyi-modules/pom.xml) | 需新增 `<module>ruoyi-ai</module>` |
| 网关路由 | nacos: `ruoyi-gateway-dev.yml` | 新增 ruoyi-ai 路由条目 |
| 数据源配置 | nacos: `ruoyi-system-dev.yml`（已修复过） | 数据源配置模板，复制改库名/包名 |

## 模块目录结构

```
ruoyi-modules/ruoyi-ai/
├── pom.xml
├── src/main/java/com/ruoyi/ai/
│   ├── RuoYiAiApplication.java              # 启动类
│   ├── controller/
│   │   └── AiChatController.java             # POST /ai/chat
│   ├── service/
│   │   ├── AiChatService.java                # 接口
│   │   ├── impl/AiChatServiceImpl.java       # 编排：取登录用户→组装prompt→调Kimi→解析→持久化
│   │   ├── LlmClient.java                    # Kimi HTTP 客户端（OkHttp）
│   │   └── PromptBuilder.java                # 系统提示词组装（含 context+schema+few-shot）
│   ├── domain/
│   │   ├── AiConversation.java               # 会话实体
│   │   ├── AiMessage.java                    # 消息实体
│   │   ├── dto/ChatRequest.java              # 前端请求 DTO
│   │   ├── dto/ChatResponse.java             # 响应 DTO（reply/conversationId/actions/needFeedback）
│   │   └── dto/Action.java                    # action DTO（@JsonInclude(NON_NULL)，覆盖21种type字段）
│   ├── mapper/
│   │   ├── AiConversationMapper.java
│   │   └── AiMessageMapper.java
│   └── config/RestTemplateConfig.java        # 可选，如改用 RestTemplate
├── src/main/resources/
│   ├── bootstrap.yml                         # 端口9301，应用名ruoyi-ai
│   ├── logback.xml                           # logs/ruoyi-ai
│   └── mapper/
│       ├── AiConversationMapper.xml
│       └── AiMessageMapper.xml
└── sql/ai_20260823.sql                       # 建表脚本（可导入 ry-cloud 库）
```

## 实施步骤

### 步骤 1：父 pom 注册新模块

修改 [ruoyi-modules/pom.xml](file:///g:/code/agentReport/ruoyi-cloude-service/ruoyi-modules/pom.xml)：在 `<modules>` 末尾追加 `<module>ruoyi-ai</module>`。

### 步骤 2：创建模块 pom.xml

`ruoyi-modules/ruoyi-ai/pom.xml`，artifactId `ruoyi-modules-ai`，依赖参考 [ruoyi-system/pom.xml](file:///g:/code/agentReport/ruoyi-cloude-service/ruoyi-modules/ruoyi-system/pom.xml)：
- spring-cloud-starter-alibaba-nacos-discovery
- spring-cloud-starter-alibaba-nacos-config
- spring-cloud-starter-alibaba-sentinel
- spring-boot-starter-actuator
- mysql-connector-j
- com.ruoyi:ruoyi-common-datasource（dynamic datasource）
- com.ruoyi:ruoyi-common-log（@Log 注解）
- com.ruoyi:ruoyi-common-swagger（springdoc）
- com.ruoyi:ruoyi-common-security（@EnableCustomConfig、@EnableRyFeignClients、SecurityUtils）
- com.squareup.okhttp3:okhttp（Kimi 调用，版本由父 pom 管理；若无则显式 4.9.3）
- com.google.code.gson:gson 或 jackson（JSON 解析，jackson 已随 spring-boot 引入）

### 步骤 3：启动类

`RuoYiAiApplication.java`，参考 [RuoYiSystemApplication.java](file:///g:/code/agentReport/ruoyi-cloude-service/ruoyi-modules/ruoyi-system/src/main/java/com/ruoyi/system/RuoYiSystemApplication.java)：

```java
@EnableCustomConfig
@EnableRyFeignClients
@SpringBootApplication
public class RuoYiAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(RuoYiAiApplication.class, args);
        // banner: AI 智能报表服务模块启动成功
    }
}
```

不加 `exclude = DataSourceAutoConfiguration.class`（要数据源），由 `ruoyi-common-datasource` 提供 dynamic datasource。

### 步骤 4：bootstrap.yml 与 logback.xml

`bootstrap.yml`（端口 9301、应用名 `ruoyi-ai`、nacos 127.0.0.1:8848、shared-configs: application-dev.yml）—— 模板见 [ruoyi-system/bootstrap.yml](file:///g:/code/agentReport/ruoyi-cloude-service/ruoyi-modules/ruoyi-system/src/main/resources/bootstrap.yml)。

`logback.xml`：复制 [ruoyi-system/logback.xml](file:///g:/code/agentReport/ruoyi-cloude-service/ruoyi-modules/ruoyi-system/src/main/resources/logback.xml)，仅改 `log.path = logs/ruoyi-ai`。

### 步骤 5：Nacos 配置 ruoyi-ai-dev.yml

通过 Nacos API（或控制台）发布 `dataId=ruoyi-ai-dev.yml, group=DEFAULT_GROUP, tenant=`，内容参考 `ruoyi-system-dev.yml` 结构（已在前序会话中验证过格式正确），改：
- `mybatis.typeAliasesPackage: com.ruoyi.ai`
- `springdoc.info.title/description` 改 AI 表格助手
- 新增 `ai.model` 配置块：

```yaml
ai:
  model:
    provider: moonshot
    base-url: https://api.moonshot.cn/v1/chat/completions
    api-key: ${AI_API_KEY:}        # 从环境变量读，未配置时服务降级返回503
    model: moonshot-v1-8k
    temperature: 0.3
    max-tokens: 2048
    timeout-ms: 55000              # 略小于前端60s
```

数据源指向 ry-cloud 库（与 system 共库，不新建库，仅新建表）。

### 步骤 6：网关路由 ruoyi-gateway-dev.yml

修改 nacos 中 `ruoyi-gateway-dev.yml` 的 `routes` 数组，新增：

```yaml
# AI 智能报表服务
- id: ruoyi-ai
  uri: lb://ruoyi-ai
  predicates:
    - Path=/ai/**
  filters:
    - StripPrefix=0     # 不剥前缀：后端接口路径就是 /ai/chat，符合前端规范
```

**不**加到 `security.ignore.whites`（保留登录鉴权，前端会带 Bearer token）。

### 步骤 7：数据库表（ry-cloud 库）

新建 `ruoyi-modules/ruoyi-ai/sql/ai_20260823.sql`：

```sql
-- AI 会话表
CREATE TABLE IF NOT EXISTS ai_conversation (
  conversation_id   VARCHAR(64)  NOT NULL COMMENT '会话ID（前端传或后端生成）',
  user_id           BIGINT       NOT NULL COMMENT '用户ID',
  workbook_name     VARCHAR(255) DEFAULT NULL COMMENT '工作簿名',
  sheet_name        VARCHAR(255) DEFAULT NULL COMMENT '工作表名',
  status            CHAR(1)      DEFAULT '0' COMMENT '状态（0正常 1停用）',
  create_by         VARCHAR(64)  DEFAULT '',
  create_time       DATETIME     DEFAULT NULL,
  update_by         VARCHAR(64)  DEFAULT '',
  update_time       DATETIME     DEFAULT NULL,
  remark            VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (conversation_id),
  KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 会话表';

-- AI 消息表
CREATE TABLE IF NOT EXISTS ai_message (
  message_id         BIGINT       NOT NULL AUTO_INCREMENT,
  conversation_id    VARCHAR(64)  NOT NULL,
  role               VARCHAR(16)  NOT NULL COMMENT 'user/assistant',
  content            TEXT         COMMENT '消息内容',
  actions            JSON         DEFAULT NULL COMMENT 'assistant 返回的 actions 数组',
  prompt_tokens      INT          DEFAULT 0,
  completion_tokens  INT          DEFAULT 0,
  total_tokens       INT          DEFAULT 0,
  model              VARCHAR(64)  DEFAULT NULL,
  create_by          VARCHAR(64)  DEFAULT '',
  create_time        DATETIME     DEFAULT NULL,
  PRIMARY KEY (message_id),
  KEY idx_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 消息表';
```

执行方式：复用前序会话已验证的 MySQL 命令行（root/123456/127.0.0.1:3306）导入到 ry-cloud 库。

### 步骤 8：DTO 与实体

**ChatRequest**（前端请求体）：字段 `message`、`conversationId`、`history`（`List<HistoryItem>`）、`context`（`SheetContext`）、`clientMeta`。`SheetContext` 内嵌 `usedRange`、`values`、`formulas`、`merges`、`selection`、`activeCell` 等子对象，全部用 `Map<String,Object>` 或独立内部静态类承载，Jackson 自动反序列化。

**ChatResponse**：`reply`、`conversationId`、`actions`（`List<Action>`）、`needFeedback`（默认 false）。

**Action**：`@JsonInclude(NON_NULL)`，字段覆盖规范第五节 21 种 type 全部可能字段：`type`、`range`、`value`、`values`、`clearWhat`、`formula`、`mode`、`force`、`rowIndex`、`rowPosition`、`columnIndex`、`columnPosition`、`count`、`position`、`height`、`width`、`background`、`fontColor`、`fontSize`、`fontWeight`、`fontFamily`、`hAlign`、`vAlign`、`wrap`、`textRotation`、`sourceRange`、`targetRange`。序列化时只输出非空字段。

### 步骤 9：LlmClient（Kimi 调用）

`service/LlmClient.java`，单例 Bean，用 OkHttp：
- 字段从 `@Value("${ai.model.base-url}")` 等注入
- 方法 `LlmResponse chat(List<Map<String,String>> messages)`
- POST 到 base-url，Header `Authorization: Bearer {api-key}`，Body `{"model":...,"messages":...,"temperature":...,"max_tokens":...,"response_format":{"type":"json_object"}}`
- 解析响应：`choices[0].message.content` + `usage`
- 抛 `LlmUnavailableException` 当 api-key 为空 / HTTP 非 200 / 超时
- 超时设置：connect 5s、read 55s、write 5s

### 步骤 10：PromptBuilder（系统提示词）

`service/PromptBuilder.java`：
- 静态系统提示词常量：任务说明 + context 结构说明 + 21 种 action schema + 公式白名单 + 危险公式禁用 + 输出格式约束（必须输出 `{"reply":"...","actions":[...]}` JSON）
- 3 组 few-shot（用规范第七节示例 + 自造两组）
- 方法 `List<Map<String,String>> buildMessages(ChatRequest req, List<AiMessage> dbHistory)`：组装 `[{system}, ...history, {user:req.message}]`
- context 序列化为 JSON 块嵌入 user 消息：`"当前表格状态：\n" + gson.toJson(req.getContext())`

### 步骤 11：AiChatService 业务编排

`service/impl/AiChatServiceImpl.java` `chat(ChatRequest req)` 流程：
1. `SecurityUtils.getLoginUser().getUserId()` 取用户 ID
2. conversationId 为空 → `UUID.randomUUID().toString().replace("-","")` 生成；查 `ai_conversation`，不存在则 insert
3. 查 `ai_message` 最近 10 条（按 message_id desc）作为 dbHistory，与前端传的 history 合并去重
4. `PromptBuilder.buildMessages(req, dbHistory)` 组装 messages
5. 持久化 user 消息：insert `ai_message` (role=user, content=req.message)
6. try：`llmClient.chat(messages)` → 解析 content 为 `{"reply":...,"actions":...}` → 反序列化为 ChatResponse
   - catch `LlmUnavailableException` 或 JSON 解析失败 → 返回 `R.fail(503, "AI 服务暂时不可用")`（前端自动回退本地 mock）
7. 持久化 assistant 消息：insert `ai_message` (role=assistant, content=reply, actions=JSON, tokens, model)
8. update `ai_conversation.update_time`
9. 返回 `R.ok(chatResponse)`

### 步骤 12：Controller

`controller/AiChatController.java`：

```java
@RestController
@RequestMapping("/ai")
public class AiChatController {
    @Autowired private AiChatService aiChatService;

    @PostMapping("/chat")
    public R<ChatResponse> chat(@RequestBody ChatRequest req) {
        return aiChatService.chat(req);
    }
}
```

不加 `@PreAuthorize`（任何登录用户可用，权限由 AuthFilter 的 token 校验保证）。

### 步骤 13：Mapper XML

`AiConversationMapper.xml`：`insertConversation`、`selectByConvId`、`updateTime`。
`AiMessageMapper.xml`：`insertMessage`、`selectRecentByConvId`（limit 10）。
XML 头部 DOCTYPE 与 ruoyi-system mapper 一致，namespace 对应 Mapper 接口。

### 步骤 14：打包并发布到 Nacos

- 项目根目录执行 `mvn -pl ruoyi-modules/ruoyi-ai -am clean package -Dmaven.test.skip=true`
- 启动前确认 Nacos 中 `ruoyi-ai-dev.yml`、`ruoyi-gateway-dev.yml`（带新路由）已发布
- 启动：`java -Dfile.encoding=utf-8 -jar ruoyi-modules-ai.jar`（或编写 bin/run-ai.bat）

## 关键设计决策

1. **StripPrefix=0 而非默认 1**：前端规范明确"实际到后端就是 /ai/chat"，故路由不去前缀，Controller `@RequestMapping("/ai")` + `@PostMapping("/chat")`。
2. **共用 ry-cloud 库不新建库**：会话/消息表少且独立，无跨库 join 需求，避免额外数据源。
3. **api-key 从环境变量读**：`AI_API_KEY` 环境变量，未配置时服务自动降级返回 503，避免硬编码。
4. **`response_format: json_object`**：Kimi 支持 OpenAI 兼容的 JSON 模式，强制模型输出合法 JSON，降低解析失败率。仍用 try-catch 兜底。
5. **降级 503 而非 500**：前端规范约定 503 时静默回退本地正则解析，用户无感；后端任何异常（key 缺失、超时、JSON 解析失败）统一返回 503。
6. **history 双源合并**：前端传的 history 优先，数据库补全最近 10 条，去重后作为上下文，兼顾无状态和持久化。

## 验证方案

### 1. 编译打包
```
mvn -pl ruoyi-modules/ruoyi-ai -am clean package -Dmaven.test.skip=true
```
应产出 `ruoyi-modules/ruoyi-ai/target/ruoyi-modules-ai.jar`。

### 2. 启动并验证 Nacos 注册
- 启动 ruoyi-ai 服务，日志应出现 `nacos registry, DEFAULT_GROUP ruoyi-ai 192.168.44.1:9301 register finished`
- Nacos 控制台 http://127.0.0.1:8848/nacos 服务列表出现 ruoyi-ai

### 3. 验证路由（无需 token 也可触发 401）
```
curl http://localhost:8080/ai/chat -X POST -H "Content-Type: application/json" -d "{\"message\":\"hi\"}"
```
应返回 401（令牌不能为空）或被 AuthFilter 放行后到 Controller——验证路由打通。

### 4. 端到端联调（带有效 token）
- 先调 `/auth/login` 拿 token
- 带 `Authorization: Bearer {token}` 调 `/ai/chat`，body 用规范第七节示例
- 应返回 `{"code":200,"data":{"reply":"...","actions":[...],"conversationId":"..."}}`
- 查 `ry-cloud.ai_message` 表应有 2 条新记录（user + assistant）

### 5. 降级验证
- 不设 `AI_API_KEY` 环境变量重启服务
- 调 `/ai/chat` 应返回 `{"code":503,"msg":"AI 服务暂时不可用"}`
- 前端应静默回退本地 mock，无报错
