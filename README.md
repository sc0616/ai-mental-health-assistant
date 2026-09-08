# AI 心理健康智能助手

> 面向大学生心理健康的 AI 咨询平台。用户可与 AI 进行心理疏导对话、记录每日情绪日记、浏览心理知识库；
> AI 回答时会自动检索知识库作为专业依据，并能主动调用工具查询用户自己的情绪记录。

[![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1-blue)](https://spring.io/projects/spring-ai)
[![Vue](https://img.shields.io/badge/Vue-3.5-42b883)](https://vuejs.org/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 项目亮点

| 亮点 | 说明 |
|---|---|
| **RAG 检索增强** | 知识文章切分向量化构建语义索引，检索相关片段注入 Prompt，让 AI 回答有专业依据 |
| **Tool Calling** | 提供知识库检索、分类浏览、用户情绪查询等工具，由模型按需自主调用 |
| **Redis 多级缓存** | 按数据变动频率差异化设置 TTL，文章增删改时精准清除缓存，支持降级直连 MySQL |
| **情绪分析与风险分级** | 输出情绪倾向与 0-3 级风险分级，可识别自伤自杀等危机信号 |
| **SSE 流式输出** | 基于 WebFlux + SSE 实时推送生成内容，大幅降低首字等待时间 |

### RAG 与 Tool Calling 的分工

两者不是重复建设，而是解决不同问题：

| | RAG（自动检索） | Tool Calling（按需调用） |
|---|---|---|
| **触发方式** | 每轮对话自动执行一次 | 模型决定是否调用、调用几次 |
| **适用场景** | 问题隐含某主题，需要背景知识 | 模型意识到信息不足，主动再查 |
| **局限** | 只有一次机会，检索不佳无法补救 | 依赖模型的判断力 |

**具体例子**：用户问「我最近情绪怎么样」——RAG 永远检索不到有用内容，因为这问的是**用户自己的历史数据**，
知识库里根本没有；而模型借助 `get_user_emotion_trend` 工具就能查到该用户的日记并如实回答。

---

## 技术栈

**后端**：Java 17、Spring Boot 3.5、Spring AI 1.1、Spring Security、WebFlux、JWT、MyBatis-Plus 3.5、Redis、MySQL 5.7

**前端**：Vue 3.5、Vite 7、Element Plus、Pinia、Vue Router、ECharts、Axios

**AI 服务**：硅基流动（OpenAI 兼容接口）
- 对话模型：`deepseek-ai/DeepSeek-V3`
- 向量模型：`BAAI/bge-m3`（1024 维）

---

## 架构

```
┌─────────────────────────────────────────────────────┐
│  Vue 3 + Element Plus + Pinia + ECharts  (5173)     │
│  ├─ 用户端：AI 对话、情绪日记、知识库浏览              │
│  └─ 管理端：文章管理、数据分析                        │
└───────────────────────┬─────────────────────────────┘
                        │ HTTP / SSE  (/api 代理)
┌───────────────────────▼─────────────────────────────┐
│  Spring Boot 3.5  (1236)                            │
│                                                     │
│  Controller 层 ── JWT 过滤器 ── SecurityConfig        │
│       │                                             │
│  Service 层                                         │
│    ├─ PsychologicalSupportService  ← 对话主流程       │
│    │     ├─ KnowledgeRetriever  → RAG 检索           │
│    │     └─ PsychologicalTools  → Tool Calling      │
│    ├─ EmotionAnalysisService   → 情绪风险分级         │
│    ├─ DataAnalyticsService     → 后台数据统计         │
│    └─ ChatCacheService         → Redis 缓存          │
│       │                                             │
│  ├── MySQL 5.7  用户/会话/消息/日记/文章               │
│  └── Redis      业务缓存 + 向量索引 + Token 黑名单      │
└─────────────────────────────────────────────────────┘
```

### 一次对话的完整链路

```
用户提问
   ├─ 1. JWT 过滤器解析身份 → SecurityContext
   ├─ 2. 保存用户消息到 MySQL
   ├─ 3. RAG 检索：问题向量化 → 余弦相似度 → top-4 片段 → 阈值过滤
   ├─ 4. 拼接系统提示词：人设 + 知识片段 + 工具说明
   ├─ 5. 绑定当前用户 ID（供工具使用）
   ├─ 6. 调用模型（携带 ChatMemory 历史 + 4 个工具）
   │      └─ 模型可能发起工具调用 → 执行 → 结果回灌 → 再生成
   ├─ 7. SSE 流式推送内容给前端
   └─ 8. 保存 AI 回复 + 写入会话记忆
```

---

## 快速开始

### 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | Spring Boot 3.x 最低要求 |
| Maven | 3.8+ | 或使用项目自带的 `mvnw` |
| MySQL | 5.7 / 8.0 | 存储业务数据 |
| Redis | 3.0+ | 缓存与向量索引（无需 RediSearch 模块） |
| Node.js | 18+ | 前端构建 |

### 1. 初始化数据库

```bash
mysql -uroot -p < ai-springboot/ai-spingboot/src/main/resources/db/schema.sql
mysql -uroot -p < ai-springboot/ai-spingboot/src/main/resources/db/seed_knowledge.sql
```

> 建表脚本创建的库名为 `mental_health_assisant`（沿用原项目命名），如需改名请同步修改 `application.yml` 中的连接串。

### 2. 配置密钥（重要）

`application.yml` 中**不含任何真实密钥**，全部使用占位符。请通过以下任一方式注入：

**方式 A：本地配置文件（推荐）**

在 `ai-springboot/ai-spingboot/src/main/resources/` 下新建 `application-local.yml`
（该文件已被 `.gitignore` 排除，不会误提交）：

```yaml
spring:
  datasource:
    password: 你的数据库密码
  ai:
    openai:
      api-key: sk-你的硅基流动密钥

jwt:
  secret: 你的JWT签名密钥
```

启动时激活 profile：

```bash
java -jar target/ai-spingboot-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

**方式 B：环境变量**

```bash
export AI_API_KEY=sk-xxx
export JWT_SECRET=your-secret
export MYSQL_PASSWORD=your-password
```

**方式 C：启动参数**

```bash
java -jar xxx.jar \
  --spring.ai.openai.api-key=sk-xxx \
  --jwt.secret=your-secret \
  --spring.datasource.password=your-password
```

### 3. 启动后端

```bash
cd ai-springboot/ai-spingboot
./mvnw clean package -DskipTests
java -jar target/ai-spingboot-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

服务运行在 `http://localhost:1236`。

启动后会自动为知识库文章建立向量索引，日志中可见：

```
知识库索引构建完成：N 篇文章 -> M 个片段，维度 1024
```

### 4. 启动前端

```bash
cd ai-vue
npm install
npm run dev
```

访问 `http://localhost:5173`。Vite 已配置 `/api` 代理到 `http://127.0.0.1:1236`。

---

## 功能模块

| 模块 | 说明 |
|---|---|
| AI 心理咨询 | 流式对话、多轮上下文、基于知识库回答、可调用工具 |
| 情绪日记 | 记录情绪评分、睡眠质量、压力水平与触发事件 |
| 心理知识库 | 文章分类浏览、详情阅读、后台管理 |
| 情绪分析 | 分析对话与日记的情绪倾向，输出风险分级 |
| 数据分析 | 后台统计：用户、日记、会话、情绪趋势、活跃度 |
| 用户与认证 | 注册登录、JWT 无状态认证、登出黑名单 |

---

## 核心实现说明

### RAG 索引构建

```
知识文章（富文本）
   ├─ ① HTML 清洗 → 纯文本（标签无语义，且会干扰相似度计算）
   ├─ ② 文本切分 → 片段
   │      · 按句子边界断开（。！？!?；; \n），避免把句子劈成两半
   │      · 相邻片段保留 80 字符重叠，防止关键信息落在切分点
   │      · 目标片段长度 400 字符
   ├─ ③ 向量化 → 1024 维（标题+摘要+正文一起参与，提升命中率）
   └─ ④ 写入 Redis 建立语义索引
```

检索时：问题向量化 → 与所有片段算余弦相似度 → 取 top-4 → **阈值 0.35 过滤** → 拼接注入 Prompt（上限 2000 字符）。

**关于存储方案**：Redis 原生向量检索需要 RediSearch 模块，本项目环境的 Redis 3.0 不具备该能力，
因此采用「Redis 存向量 + 应用内算相似度」。在当前体量（几十篇文章、几百个片段）下，全量比对的开销
远小于一次大模型调用；**若文章数上到万级，应换成带向量索引的方案（Redis Stack / Milvus）**。

### Tool Calling 的安全边界

**所有涉及用户数据的工具都不接受用户 ID 参数**：

```java
// ❌ 错误做法：userId 暴露给模型，可能被提示词注入诱导查询他人数据
String getUserEmotionTrend(Long userId, Integer days)

// ✅ 实际做法：userId 由服务端绑定，模型无法伪造
String getUserEmotionTrend(Integer days, ToolContext ctx)
```

用户 ID 来自 JWT 解析结果，经 `ToolContext` 跨线程传递给工具（因为工具执行在 Reactor 线程上，
`ThreadLocal` 不够用），并设置单轮调用次数上限（6 次）防止循环调用。

### 缓存设计

| 数据 | TTL | 理由 |
|---|---|---|
| 文章详情 | 1 小时 | 内容稳定，命中率高 |
| 文章列表分页 | 10 分钟 | key 含分页排序参数，组合多、变动快 |
| 会话列表 | 30 分钟 | 列表变动较频繁 |
| 会话消息 | 7 天 | 与前端历史会话展示周期一致 |

文章增删改时**精准清除对应缓存**保证一致性；并提供总开关 `chat.cache.enabled`，
关闭后完全走 MySQL，用于排查「数据对不上」类问题。

---

## 项目结构

```
.
├── ai-springboot/ai-spingboot/          # 后端
│   ├── src/main/java/org/example/aispingboot/
│   │   ├── AiService/                   # AI 对话、工具、情绪分析
│   │   │   ├── PsychologicalSupportService.java   # 对话主流程
│   │   │   ├── PsychologicalTools.java            # Tool Calling 工具集
│   │   │   ├── EmotionAnalysisService.java        # 情绪风险分级
│   │   │   └── PromptManage.java                  # 提示词模板
│   │   ├── rag/                         # RAG 检索增强
│   │   │   ├── TextChunker.java         # 文本切分
│   │   │   ├── EmbeddingService.java    # 向量化
│   │   │   ├── VectorStore.java         # 向量存储
│   │   │   ├── KnowledgeRetriever.java  # 语义检索
│   │   │   └── KnowledgeIndexService.java # 索引维护
│   │   ├── controller/                  # 接口层
│   │   ├── service/                     # 业务层（含缓存、统计）
│   │   ├── config/                      # 配置（Security / Redis / RAG）
│   │   ├── entity/ mapper/ DTO/         # 数据层
│   │   └── util/                        # JWT 等工具
│   └── src/main/resources/
│       ├── application.yml              # 主配置（仅占位符）
│       └── db/                          # 建表与初始数据脚本
└── ai-vue/                              # 前端
    └── src/
        ├── views/                       # 页面
        ├── components/                  # 组件
        ├── api/                         # 接口封装
        ├── router/                      # 路由与守卫
        ├── stores/                      # Pinia 状态
        └── utils/                       # axios 封装
```

---

## 配置项说明

```yaml
rag:
  enabled: true                 # 总开关：关闭后不检索知识库
  index-on-startup: true        # 启动后自动建索引
  chunk-size: 400               # 片段目标长度
  chunk-overlap: 80             # 相邻片段重叠字符数
  top-k: 4                      # 检索返回片段数
  min-score: 0.35               # 相似度下限，过低则丢弃
  max-context-chars: 2000       # 注入提示词的片段总长度上限
  tool-calling-enabled: true    # 是否允许模型调用工具
  tool-prompt-enabled: true     # 是否注入工具说明（明显提升调用意愿）

chat:
  cache:
    enabled: true               # 缓存总开关，关闭后直连 MySQL
    message-ttl-hours: 168
    session-list-ttl-minutes: 30
```

---

## 已知不足与改进方向

诚实记录当前实现的局限：

| 方向 | 现状 | 改进方案 |
|---|---|---|
| 向量检索扩展性 | 应用内全量比对，万级数据不可用 | 引入 Redis Stack / Milvus 等带向量索引的方案 |
| 相似度阈值 | `0.35` 偏宽松，无关查询也可能达到 0.45 | 调高阈值或引入 rerank 模型二次排序 |
| Embedding 成本 | 每次检索都调用一次向量化接口 | 增加查询向量缓存 |
| 权限控制 | 后台管理接口未做角色校验 | 补充 `@PreAuthorize` 或自定义权限注解 |
| 对话记忆 | 固定窗口 30 条，可能丢失早期信息 | 改用摘要记忆 |
| 危机干预 | 仅输出风险等级，无后续动作 | 高等级时触发人工介入或转介资源 |
| 单元测试 | 核心链路缺少自动化测试 | 补充 RAG 检索、工具调用的集成测试 |

---

## 说明

本项目为个人学习与求职作品，用于展示 RAG、Tool Calling 等 AI 应用技术的工程实践。
心理健康场景涉及隐私与伦理，**当前实现仅供技术演示，不可直接用于生产环境的心理服务**。

## License

[MIT](LICENSE)
