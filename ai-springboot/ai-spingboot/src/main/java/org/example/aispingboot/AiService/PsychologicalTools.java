package org.example.aispingboot.AiService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.aispingboot.entity.EmotionDiary;
import org.example.aispingboot.entity.KnowledgeArticle;
import org.example.aispingboot.entity.KnowledgeCategory;
import org.example.aispingboot.mapper.EmotionDiaryMapper;
import org.example.aispingboot.mapper.KnowledgeArticleMapper;
import org.example.aispingboot.mapper.KnowledgeCategoryMapper;
import org.example.aispingboot.rag.KnowledgeRetriever;
import org.example.aispingboot.rag.RetrievedChunk;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 对话可调用的工具集（Tool Calling）
 * <p>
 * <b>为什么在已有 RAG 的情况下还需要工具？</b>
 * RAG 和 Tool Calling 解决的是不同问题，这里做了明确分工：
 * <ul>
 *     <li><b>RAG（自动检索）</b>：每轮对话都会跑一次，把最相关的知识片段塞进提示词。
 *         适合"用户问题里隐含了某个主题，需要背景知识"的场景。
 *         缺点是<b>只有一次机会</b>——检索结果不理想时模型无法补救。</li>
 *     <li><b>Tool Calling（按需调用）</b>：由模型自己决定要不要调用、调用几次、
 *         用什么参数。适合"模型意识到当前信息不够，需要主动再查一次"的场景。</li>
 * </ul>
 * 举个具体例子：用户问"我最近情绪怎么样"，RAG 检索不到任何有用的知识文章
 * （因为这问的是<b>用户自己的历史数据</b>，知识库里没有）；而模型借助
 * {@link #getUserEmotionTrend} 就能查到该用户近期的日记并如实回答。
 * <p>
 * <b>安全边界</b>（很重要）：所有涉及用户数据的工具都<b>不接受用户ID参数</b>，
 * userId 由服务端在每次对话开始时通过 {@link #bindUser} 绑定到当前线程，
 * 模型无法伪造。否则模型可能被诱导去查询别人的情绪日记。
 */
@Slf4j
@Component
public class PsychologicalTools {

    @Autowired
    private KnowledgeRetriever knowledgeRetriever;

    @Autowired
    private KnowledgeArticleMapper knowledgeArticleMapper;

    @Autowired
    private KnowledgeCategoryMapper knowledgeCategoryMapper;

    @Autowired
    private EmotionDiaryMapper emotionDiaryMapper;

    /**
     * 当前会话所属用户ID。
     * <p>
     * 用 ThreadLocal 保存：工具方法是在模型发起的那次请求线程里被调用的。
     * 不把它做成方法参数，是为了避免模型自行填写用户ID。
     */
    private static final ThreadLocal<Long> CURRENT_USER = new ThreadLocal<>();

    /**
     * ToolContext 中存放用户ID的 key。
     * <p>
     * <b>为什么 ThreadLocal 不够用？</b>
     * 实测发现：当请求经过 {@code MessageChatMemoryAdvisor} 时，工具的执行会
     * 被切换到 Reactor 的另一个线程上，ThreadLocal 里的 userId 变成 null，
     * 工具只好返回"无法确定当前用户"，用户就觉得查询失灵了
     * （但去掉 advisor 就正常，这也是为什么该问题只在流式+记忆组合下出现）。
     * <p>
     * Spring AI 的 ToolContext 是随请求传递的 Map，能跨线程带到工具执行处，
     * 因此以它为主、ThreadLocal 作为兜底，两种场景都能拿到 userId。
     */
    private static final String USER_ID_KEY = "userId";

    /**
     * 工具调用次数上限，防止模型陷入"反复调用同一个工具"的循环。
     * <p>
     * 每次对话开始时重置；超出后工具会返回提示而不是继续查询，
     * 保证单次对话不会因为工具循环而卡死或烧掉大量 token。
     */
    private static final ThreadLocal<Integer> CALL_COUNTER = ThreadLocal.withInitial(() -> 0);

    private static final int MAX_TOOL_CALLS = 6;

    /**
     * 绑定当前用户，进入对话前调用
     */
    public void bindUser(Long userId) {
        CURRENT_USER.set(userId);
        CALL_COUNTER.set(0);
    }

    /**
     * 清理 ThreadLocal，避免线程池复用导致用户身份串号
     * <p>
     * 这一步必须做：Tomcat 线程是复用的，不清理会让下一个请求
     * 意外继承上一个用户的身份，属于严重的越权隐患。
     */
    public void clear() {
        CURRENT_USER.remove();
        CALL_COUNTER.remove();
    }

    /**
     * 计数并判断是否超出上限
     */
    private boolean overLimit() {
        int count = CALL_COUNTER.get() + 1;
        CALL_COUNTER.set(count);
        if (count > MAX_TOOL_CALLS) {
            log.warn("工具调用次数超出上限 {}", MAX_TOOL_CALLS);
            return true;
        }
        return false;
    }

    /**
     * 所有工具的统一返回前缀，超出上限时提示模型停止调用
     */
    private static final String LIMIT_HINT =
            "（本次对话的工具调用次数已达上限，请直接基于已知信息回答用户）";

    /**
     * 解析当前用户ID：优先取 ToolContext（可跨线程），其次取 ThreadLocal
     */
    private Long resolveUserId(org.springframework.ai.chat.model.ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object value = toolContext.getContext().get(USER_ID_KEY);
            if (value instanceof Long id) {
                return id;
            }
            if (value instanceof Number number) {
                return number.longValue();
            }
        }
        return CURRENT_USER.get();
    }

    /**
     * 构造随请求传递的 ToolContext，把当前用户带给工具执行线程
     */
    public java.util.Map<String, Object> buildToolContext() {
        java.util.Map<String, Object> context = new java.util.HashMap<>();
        Long userId = CURRENT_USER.get();
        if (userId != null) {
            context.put(USER_ID_KEY, userId);
        }
        return context;
    }

    /**
     * 构建本工具集的 ToolCallback 列表
     * <p>
     * 不用 {@code .tools(bean)} 的自动扫描，而是显式构建。
     * 原因有两点：
     * <ol>
     *     <li>可以打日志确认"工具到底注册了没有"——自动扫描出问题时
     *         没有任何反馈，排查成本很高；</li>
     *     <li>能把 {@link #MAX_TOOL_CALLS} 之类的上限信息一次性说清楚。</li>
     * </ol>
     * 构建结果在每次对话时调用，保证取到的是当前实例的方法。
     */
    public List<org.springframework.ai.tool.ToolCallback> buildToolCallbacks() {
        List<org.springframework.ai.tool.ToolCallback> callbacks = new ArrayList<>();

        // search_knowledge(String query)
        callbacks.add(org.springframework.ai.tool.method.MethodToolCallback.builder()
                .toolMethod(findMethod("searchKnowledge", String.class))
                .toolObject(this)
                .toolDefinition(org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name("search_knowledge")
                        .description("""
                                在心理健康知识库中做语义检索，返回相关文章的标题、正文片段和相似度。
                                适用场景：需要查找某个心理话题的专业知识，而系统已提供的参考资料不够用、
                                或者你想换个角度再查一次时使用。
                                注意：如果系统提示词里已经给出了参考资料且足以回答，就不需要调用本工具。""")
                        .inputSchema("""
                                {"type":"object","properties":{"query":{"type":"string","description":"检索用的自然语言query，例如：如何缓解考试焦虑"}},"required":["query"]}""")
                        .build())
                .build());

        // get_article_detail(String articleId)
        callbacks.add(org.springframework.ai.tool.method.MethodToolCallback.builder()
                .toolMethod(findMethod("getArticleDetail", String.class))
                .toolObject(this)
                .toolDefinition(org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name("get_article_detail")
                        .description("""
                                根据文章ID获取一篇心理健康文章的完整内容。
                                通常在 search_knowledge 返回了某篇文章但片段被截断、需要看全文时使用。""")
                        .inputSchema("""
                                {"type":"object","properties":{"articleId":{"type":"string","description":"文章ID，来自 search_knowledge 的检索结果"}},"required":["articleId"]}""")
                        .build())
                .build());

        // list_knowledge_categories()
        callbacks.add(org.springframework.ai.tool.method.MethodToolCallback.builder()
                .toolMethod(findMethod("listKnowledgeCategories"))
                .toolObject(this)
                .toolDefinition(org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name("list_knowledge_categories")
                        .description("""
                                列出知识库中的所有文章分类，以及每个分类下的文章标题。
                                适用场景：用户想了解"知识库里都有什么内容"、想让你推荐阅读材料，
                                或者你不确定该用什么关键词检索时。""")
                        .inputSchema("""
                                {"type":"object","properties":{}}""")
                        .build())
                .build());

        // get_user_emotion_trend(Integer days, ToolContext toolContext)
        callbacks.add(org.springframework.ai.tool.method.MethodToolCallback.builder()
                .toolMethod(findMethod("getUserEmotionTrend", Integer.class,
                        org.springframework.ai.chat.model.ToolContext.class))
                .toolObject(this)
                .toolDefinition(org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name("get_user_emotion_trend")
                        .description("""
                                查询【当前用户本人】最近若干天的情绪日记，包括情绪评分(1-10)、主要情绪、
                                睡眠质量(1-5)、压力水平(1-5)和触发事件。
                                适用场景：用户询问"我最近情绪怎么样""我睡眠好不好""我是不是压力很大"等
                                需要依据其历史记录才能回答的问题。
                                如果查不到记录，说明用户还没写过日记，请如实告知，不要编造数据。""")
                        .inputSchema("""
                                {"type":"object","properties":{"days":{"type":"integer","description":"查询最近多少天的记录，默认7天，最大30天"}}}""")
                        .build())
                .build());

        log.info("已注册 {} 个工具: {}", callbacks.size(),
                callbacks.stream().map(c -> c.getToolDefinition().name()).toList());
        return callbacks;
    }

    /**
     * 按名称与参数类型查找本类的方法，供显式构建 ToolCallback 使用
     */
    private java.lang.reflect.Method findMethod(String name, Class<?>... parameterTypes) {
        try {
            return PsychologicalTools.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("工具方法不存在: " + name, e);
        }
    }

    // ==================== 工具 1：语义检索知识库 ====================

    @Tool(name = "search_knowledge", description = """
            在心理健康知识库中做语义检索，返回相关文章的标题、正文片段和相似度。
            适用场景：需要查找某个心理话题的专业知识，而系统已提供的参考资料不够用、
            或者你想换个角度再查一次时使用。
            注意：如果系统提示词里已经给出了【可参考的心理知识库资料】且足以回答，
            就不需要再调用本工具。
            """)
    public String searchKnowledge(
            @ToolParam(description = "检索用的自然语言query，例如：如何缓解考试焦虑") String query) {
        if (query == null || query.isBlank()) {
            return "查询内容为空，无法检索。";
        }
        if (overLimit()) {
            return LIMIT_HINT;
        }
        try {
            List<RetrievedChunk> chunks = knowledgeRetriever.search(query, 3, 0.3);
            if (chunks.isEmpty()) {
                return "知识库中没有找到与「" + query + "」相关的内容。请基于你的专业能力回答，不要编造资料。";
            }
            StringBuilder sb = new StringBuilder("检索到以下知识片段：\n");
            for (RetrievedChunk chunk : chunks) {
                sb.append("- 【").append(chunk.title()).append("】(相关度 ")
                        .append(String.format("%.2f", chunk.score())).append(")\n")
                        .append("  ").append(chunk.content()).append("\n");
            }
            log.info("工具 search_knowledge 被调用: query={}, 命中 {}", query, chunks.size());
            return sb.toString();
        } catch (Exception e) {
            log.warn("工具 search_knowledge 执行失败: {}", e.getMessage());
            return "检索失败，请基于你的专业能力回答。";
        }
    }

    // ==================== 工具 2：查看文章详情 ====================

    @Tool(name = "get_article_detail", description = """
            根据文章ID获取一篇心理健康文章的完整内容。
            通常在 search_knowledge 返回了某篇文章但片段被截断、需要看全文时使用。
            """)
    public String getArticleDetail(
            @ToolParam(description = "文章ID，来自 search_knowledge 的检索结果") String articleId) {
        if (articleId == null || articleId.isBlank()) {
            return "文章ID为空。";
        }
        if (overLimit()) {
            return LIMIT_HINT;
        }
        try {
            KnowledgeArticle article = knowledgeArticleMapper.selectById(articleId.trim());
            if (article == null) {
                return "没有找到ID为 " + articleId + " 的文章。";
            }
            // 只取正文前 1500 字，避免一次塞进太多内容挤占上下文
            String content = article.getContent() == null ? "" : article.getContent();
            String clipped = content.length() > 1500 ? content.substring(0, 1500) + "……（内容较长已截断）" : content;
            log.info("工具 get_article_detail 被调用: articleId={}", articleId);
            return "标题：" + article.getTitle() + "\n"
                    + "摘要：" + (article.getSummary() == null ? "无" : article.getSummary()) + "\n"
                    + "正文：" + clipped;
        } catch (Exception e) {
            log.warn("工具 get_article_detail 执行失败: {}", e.getMessage());
            return "获取文章详情失败。";
        }
    }

    // ==================== 工具 3：浏览知识库分类 ====================

    @Tool(name = "list_knowledge_categories", description = """
            列出知识库中的所有文章分类，以及每个分类下的文章标题。
            适用场景：用户想了解"知识库里都有什么内容"、想让你推荐阅读材料，
            或者你不确定该用什么关键词检索时，先看分类再做检索。
            """)
    public String listKnowledgeCategories() {
        if (overLimit()) {
            return LIMIT_HINT;
        }
        try {
            List<KnowledgeCategory> categories = knowledgeCategoryMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeCategory>().orderByAsc(KnowledgeCategory::getSortOrder));
            if (categories.isEmpty()) {
                return "知识库暂无分类。";
            }
            StringBuilder sb = new StringBuilder("知识库分类及文章：\n");
            for (KnowledgeCategory category : categories) {
                List<KnowledgeArticle> articles = knowledgeArticleMapper.selectList(
                        new LambdaQueryWrapper<KnowledgeArticle>()
                                .eq(KnowledgeArticle::getCategoryId, category.getId())
                                .eq(KnowledgeArticle::getStatus, 1));
                sb.append("- ").append(category.getName());
                if (articles.isEmpty()) {
                    sb.append("（暂无文章）\n");
                } else {
                    sb.append("：")
                            .append(articles.stream().map(KnowledgeArticle::getTitle).collect(Collectors.joining("、")))
                            .append("\n");
                }
            }
            log.info("工具 list_knowledge_categories 被调用");
            return sb.toString();
        } catch (Exception e) {
            log.warn("工具 list_knowledge_categories 执行失败: {}", e.getMessage());
            return "获取分类失败。";
        }
    }

    // ==================== 工具 4：查询用户自己的情绪记录 ====================

    @Tool(name = "get_user_emotion_trend", description = """
            查询【当前用户本人】最近若干天的情绪日记，包括情绪评分(1-10)、主要情绪、
            睡眠质量(1-5)、压力水平(1-5)和触发事件。
            适用场景：用户询问"我最近情绪怎么样""我睡眠好不好""我是不是压力很大"等
            需要依据其历史记录才能回答的问题。
            注意：只能查到当前用户自己的数据。如果查不到记录，说明用户还没写过日记，
            请如实告知并鼓励他记录，不要编造数据。
            """)
    public String getUserEmotionTrend(
            @ToolParam(description = "查询最近多少天的记录，默认7天，最大30天") Integer days,
            org.springframework.ai.chat.model.ToolContext toolContext) {
        if (overLimit()) {
            return LIMIT_HINT;
        }
        Long userId = resolveUserId(toolContext);
        if (userId == null) {
            return "无法确定当前用户，暂时查不到情绪记录。";
        }

        int range = days == null || days <= 0 ? 7 : Math.min(days, 30);
        try {
            LocalDate since = LocalDate.now().minusDays(range);
            List<EmotionDiary> diaries = emotionDiaryMapper.selectList(
                    new LambdaQueryWrapper<EmotionDiary>()
                            .eq(EmotionDiary::getUserId, userId)
                            .ge(EmotionDiary::getDiaryDate, since)
                            .orderByDesc(EmotionDiary::getDiaryDate));

            if (diaries.isEmpty()) {
                return "用户最近 " + range + " 天没有记录情绪日记。请如实告知，并可以温和地建议他记录情绪。";
            }

            StringBuilder sb = new StringBuilder("该用户最近 " + range + " 天的情绪记录（共 "
                    + diaries.size() + " 条）：\n");
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM月dd日");
            for (EmotionDiary d : diaries) {
                sb.append("- ").append(d.getDiaryDate().format(fmt))
                        .append("：情绪").append(d.getMoodScore()).append("/10");
                if (d.getDominantEmotion() != null && !d.getDominantEmotion().isBlank()) {
                    sb.append("，主要情绪「").append(d.getDominantEmotion()).append("」");
                }
                if (d.getSleepQuality() != null) {
                    sb.append("，睡眠").append(d.getSleepQuality()).append("/5");
                }
                if (d.getStressLevel() != null) {
                    sb.append("，压力").append(d.getStressLevel()).append("/5");
                }
                if (d.getEmotionTriggers() != null && !d.getEmotionTriggers().isBlank()) {
                    sb.append("，触发事件：").append(d.getEmotionTriggers());
                }
                sb.append("\n");
            }
            log.info("工具 get_user_emotion_trend 被调用: userId={}, days={}, 命中 {} 条",
                    userId, range, diaries.size());
            return sb.toString();
        } catch (Exception e) {
            log.warn("工具 get_user_emotion_trend 执行失败: {}", e.getMessage());
            return "查询情绪记录失败。";
        }
    }
}
