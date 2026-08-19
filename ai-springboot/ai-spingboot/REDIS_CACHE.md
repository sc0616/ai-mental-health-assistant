# Redis 会话缓存说明

## 一、这次解决了什么问题

**现象**：AI 咨询对话刷新页面后就看不见了。

**真正的根因有两个，而且第一个才是主因**：

1. **前端调用的 4 个接口，后端根本不存在**
   前端 `src/api/frontend.js` 里调用了这些接口，但后端 `PsychologicalChat` 控制器里只有 `/session/start` 和 `/stream`：

   | 前端调用的接口 | 作用 | 改造前状态 |
   |---|---|---|
   | `GET /psychological-chat/sessions` | 左侧历史会话列表 | ❌ 404 |
   | `GET /psychological-chat/sessions/{id}/messages` | **加载对话内容（刷新关键）** | ❌ 404 |
   | `DELETE /psychological-chat/sessions/{id}` | 删除会话 | ❌ 404 |
   | `GET /psychological-chat/session/{id}/emotion` | 情绪花园分析 | ❌ 404 |

   所以刷新后前端请求历史消息直接 404，`messages` 拿不到数据 → 页面空白。
   **只加 Redis 不补接口，问题是修不好的。**

2. **对话没有可恢复的会话上下文**：即使接口存在，前端刷新后也不知道"上次在看哪个会话"。

## 二、Redis 在这里承担什么角色

采用 **Cache-Aside（旁路缓存）** 模式，核心原则：

> **Redis 只是加速层，MySQL 才是唯一事实来源。**

因此所有 Redis 操作都做了异常兜底：Redis 连不上时打日志并返回"未命中"，
上层自然回源查 MySQL，**业务功能不受影响**。这样"加缓存"才不会变成"加故障点"。

### 缓存键设计

| Key | 内容 | TTL | 说明 |
|---|---|---|---|
| `mental:chat:messages:{sessionId}` | 该会话的全部消息列表 | 7 天 | 刷新后加载对话的核心缓存 |
| `mental:chat:emotion:{sessionId}` | 情绪分析结果 | 7 天 | 避免重复调用大模型，省 token |
| `mental:chat:sessions:{userId}` | 会话列表 | 30 分钟 | 预留，列表变动频繁故 TTL 较短 |
| `mental:knowledge:article:{id}` | 知识文章详情 | 1 小时 | 读多写少，命中率极高 |
| `mental:knowledge:page:{分页参数}` | 知识文章列表页 | 10 分钟 | key 含分页与排序参数 |
| `mental:knowledge:category:tree` | 文章分类树 | 1 小时 | 几乎不变 |
| `mental:token:blacklist:{token}` | 已退出的 token | =token 剩余有效期 | 实现"退出登录后 token 立即失效" |

### 数据流

```
用户发消息
   └─> 前端 push 消息到页面
   └─> SSE 流式调用 /stream
          └─> AI 回复完成
                 └─> saveAimessage() 写入 MySQL
                        └─> appendMessage() 同步追加进 Redis   ← 关键
                               └─> 用户刷新页面
                                      └─> GET /sessions/{id}/messages
                                             └─> 先查 Redis 命中 → 直接返回完整对话 ✅
                                                 （未命中则查 MySQL 并回填 Redis）
```

## 三、改动清单

### 新增文件

| 文件 | 作用 |
|---|---|
| `config/RedisConfig.java` | Redis 序列化配置（key 字符串 + value JSON，方便 `redis-cli` 排查） |
| `config/ChatCacheProperties.java` | 缓存开关与 TTL 配置绑定 |
| `config/MybatisPlusConfig.java` | 分页插件（原本缺失，`selectPage` 会退化成内存分页） |
| `common/CacheKeys.java` | 缓存 key 统一管理，避免硬编码拼错 |
| `service/ChatCacheService.java` | 缓存读写服务，**全部异常兜底** |
| `AiService/EmotionAnalysisService.java` | 情绪分析 + 结果缓存 |
| `DTO/response/ConsultationSessionResponseDTO.java` | 会话列表响应对象 |
| `service/TokenBlacklistService.java` | **Token 黑名单**：退出登录后让 token 立即失效 |
| `controller/KnowledgeController.java` | **知识文章接口**（列表/详情/分类树/增删改） |
| `service/KnowledgeArticleService.java` | 知识文章业务 + Redis 缓存 |
| `entity/KnowledgeArticle.java`、`entity/KnowledgeCategory.java` | 知识文章与分类实体 |
| `mapper/KnowledgeArticleMapper.java`、`mapper/KnowledgeCategoryMapper.java` | 对应 Mapper |
| `DTO/response/KnowledgeArticleResponseDTO.java` | 文章响应对象（含前端要的 `categoryName`/`tagArray`） |
| `resources/db/schema.sql` | 建库建表脚本（原项目缺失） |
| `resources/db/seed_knowledge.sql` | 知识文章演示数据（5 篇 + 4 个分类） |

### 修改文件

| 文件 | 改动 |
|---|---|
| `pom.xml` | 加入 `spring-boot-starter-data-redis`、`commons-pool2`；Spring AI 版本修正 |
| `application.yml` | Redis 连接配置、`chat.cache` 缓存开关与 TTL |
| `controller/PsychologicalChat.java` | **补齐 4 个缺失接口**，并做会话归属校验防越权 |
| `controller/User.java` | 新增 **`POST /user/logout`** 退出登录接口 |
| `util/JwtAuthticationFilter.java` | 接入黑名单校验；改为构造器注入；**修复缺少 `return` 导致 403 的 bug** |
| `config/SecurityConfig.java` | 过滤器改为 Bean 注入依赖 |
| `service/ConsultationMessageService.java` | 新增列表查询、删除、计数；消息保存后同步缓存 |
| `service/ConsultationSessionService.java` | 新增分页查询、删除会话（连带删消息） |
| `AiService/PsychologicalSupportService.java` | AI 回复落库后同步缓存 |
| 前端 `views/consultation.vue` | 刷新后恢复上次会话、自动滚动到底部 |
| 前端 `vite.config.js` | 代理指向本地后端；`host: 127.0.0.1` |

## 三点五、退出登录为什么需要 Redis

JWT 是**无状态**的：签发后在有效期内始终有效，服务端不保存任何记录。
所以如果退出登录只是「前端删掉 localStorage 里的 token」，那这个 token
**依然能用**——被人抓包拿到就能继续访问，这是个真实的安全漏洞。

解决方式是**黑名单**：退出时把 token 写进 Redis，TTL 设为该 token 的剩余有效期
（过期后自动清理，不会无限占用内存）。之后每个请求都会先查黑名单，命中就拒绝。

实测：退出后同一个 token 再访问 `/user/current` 和 `/knowledge/article/page` 均返回 **401**。

> 说明：黑名单校验在 Redis 故障时会**放行**而非拒绝。这是刻意的取舍——
> 可用性优先，避免 Redis 抖动导致全部用户被强制登出。

## 四、怎么验证"刷新后还能看见"

1. 打开 http://localhost:5173/ ，登录，进入 AI 咨询，发几条消息
2. **按 F5 刷新** → 对话仍在，且自动定位到刚才的会话
3. 查看 Redis 缓存：
   ```powershell
   & "C:\Program Files\Redis\redis-cli.exe" KEYS "mental:*"
   & "C:\Program Files\Redis\redis-cli.exe" TTL "mental:chat:messages:1"
   ```

实测结果：即使**把后端完全停掉再重启**，Redis 中的对话依然存在，刷新后可完整读回。

## 四点五、知识文章缓存怎么验证

```powershell
# 1. 清掉某篇文章的缓存
& "C:\Program Files\Redis\redis-cli.exe" DEL "mental:knowledge:article:1"
# 2. 访问详情（会回源 MySQL 并自动回填缓存）
#    浏览器打开 http://localhost:5173/knowledge/article/1
# 3. 确认缓存已回填，TTL 约 3600 秒
& "C:\Program Files\Redis\redis-cli.exe" TTL "mental:knowledge:article:1"
```

## 五、开关与降级

`application.yml`：

```yaml
chat:
  cache:
    enabled: true          # 改成 false 则完全走 MySQL，便于排查问题
    message-ttl-hours: 168 # 7天
    session-list-ttl-minutes: 30
```

- **想验证"没缓存也能用"**：把 `enabled` 设为 `false` 重启即可，功能不受影响，只是每次都查库。
- **Redis 挂了**：`ChatCacheService` 会打 WARN 日志并回源 MySQL，页面照常工作。

## 六、注意事项

1. **`application.yml` 里的 `api-key: you-key` 是占位符**，必须去[硅基流动](https://siliconflow.cn)申请真实 key 填入，否则 AI 回复和情绪分析会走兜底逻辑。
2. **前端代理已改为 `http://localhost:1236`**（原为远程 `159.75.169.224:1235`，实测返回 403 且不含新接口）。若要连其他后端，改 `vite.config.js` 的 `target`。
3. **端口 1236 独占**：如果 IntelliJ 里还开着旧项目（`E:\Java code\ai-springboot`），会抢占 1236 导致启动失败，需先停掉。
4. 缓存与数据库的**一致性策略是"写库后同步写缓存 + 删除时失效"**。若后续要做多实例部署，建议改为「先更新数据库，再删除缓存」并引入延迟双删。
5. **知识文章写操作会主动清理缓存**（详情 key + 所有列表分页 key），保证后台改完文章前台立刻可见。
6. 知识列表的 `sortField` 做了**白名单校验**后才会拼进 SQL 排序，避免 SQL 注入。
