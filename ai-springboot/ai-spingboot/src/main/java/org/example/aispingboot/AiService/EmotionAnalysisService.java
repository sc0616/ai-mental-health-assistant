package org.example.aispingboot.AiService;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.example.aispingboot.DTO.response.ConsultationMessageResponseDTO;
import org.example.aispingboot.service.ChatCacheService;
import org.example.aispingboot.service.ConsultationMessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 情绪分析服务
 * <p>
 * 负责对一次咨询会话的内容做情绪判定，结果写入 Redis 缓存，
 * 这样前端刷新后情绪花园卡片也能立刻恢复，不需要重新调用大模型（省 token、省时间）。
 */
@Slf4j
@Service
public class EmotionAnalysisService {

    @Autowired
    @Qualifier("open-ai")
    private org.springframework.ai.chat.client.ChatClient chatClient;

    @Autowired
    private ConsultationMessageService consultationMessageService;

    @Autowired
    private ChatCacheService chatCacheService;

    /**
     * 情绪分析提示词：要求模型只输出 JSON，便于程序解析
     */
    private static final String EMOTION_ANALYSIS_PROMPT = """
            你是一位专业的心理情绪分析专家。请分析下面这段心理咨询对话中，来访者（用户）当前的情绪状态。
            
            请严格只输出一个 JSON 对象，不要输出任何解释、不要使用 markdown 代码块。JSON 格式如下：
            {
              "primaryEmotion": "主要情绪，如：焦虑、悲伤、平静、开心、疲惫、愤怒等",
              "emotionScore": 0到100的整数，数值越大代表情绪越积极,
              "isNegative": true或false，是否为负面情绪,
              "riskLevel": 0到3的整数，0=正常 1=需要关注 2=需要预警 3=危机，出现自伤或自杀倾向时必须为3,
              "suggestion": "一句话的情绪状态描述与温和的建议",
              "improvementSuggestions": ["具体可执行的小建议1", "建议2", "建议3"]
            }
            """;

    /**
     * 获取某会话的情绪分析结果
     * <p>
     * 优先读 Redis 缓存；缓存未命中才调用大模型分析，并把结果写入缓存。
     *
     * @param sessionId 会话ID
     * @param forceRefresh 是否强制重新分析（前端手动刷新时用）
     * @return 情绪分析结果 Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> analyzeSessionEmotion(Long sessionId, boolean forceRefresh) {
        // 1. 非强制刷新时，先尝试读缓存
        if (!forceRefresh) {
            Object cached = chatCacheService.getEmotion(sessionId);
            if (cached instanceof Map<?, ?> map) {
                log.debug("情绪分析命中缓存, sessionId={}", sessionId);
                return (Map<String, Object>) map;
            }
        }

        // 2. 取出会话消息，拼接成待分析文本
        List<ConsultationMessageResponseDTO> messages =
                consultationMessageService.listMessagesBySessionId(sessionId);
        if (messages == null || messages.isEmpty()) {
            return defaultEmotion();
        }

        StringBuilder conversation = new StringBuilder();
        for (ConsultationMessageResponseDTO message : messages) {
            String role = message.getSenderType() != null && message.getSenderType() == 1 ? "来访者" : "咨询师";
            conversation.append(role).append("：").append(message.getContent()).append("\n");
        }

        // 3. 调用大模型做分析
        try {
            String raw = chatClient.prompt()
                    .system(EMOTION_ANALYSIS_PROMPT)
                    .user(conversation.toString())
                    .call()
                    .content();

            Map<String, Object> result = parseEmotionResult(raw);
            // 4. 写入 Redis 缓存，刷新页面时直接命中
            chatCacheService.putEmotion(sessionId, result);
            return result;
        } catch (Exception e) {
            // 大模型不可用时不能让接口直接挂掉，返回兜底结果
            log.warn("情绪分析调用失败, sessionId={}. 原因: {}", sessionId, e.getMessage());
            return defaultEmotion();
        }
    }

    /**
     * 对一条情绪日记做情绪分析
     * <p>
     * 与咨询会话分析复用同一套 JSON 输出约定（EMOTION_ANALYSIS_PROMPT），
     * 这样前端对 ai_emotion_analysis 可以用同一套解析逻辑。
     * <p>
     * 与 {@link #analyzeSessionEmotion} 的关键差别：这里失败时返回 <b>null</b>，
     * 而不是返回兜底结果。日记的 ai_emotion_analysis 允许为空，
     * 但不应写入一条编造出来的分析结论——那会污染后台详情页展示。
     *
     * @param diaryText 由日记各字段拼装成的待分析文本
     * @return 分析结果；无内容或调用失败时返回 null
     */
    public Map<String, Object> analyzeDiaryEmotion(String diaryText) {
        if (diaryText == null || diaryText.isBlank()) {
            return null;
        }
        try {
            String raw = chatClient.prompt()
                    .system(EMOTION_ANALYSIS_PROMPT)
                    .user(diaryText)
                    .call()
                    .content();
            return parseEmotionResult(raw);
        } catch (Exception e) {
            // 大模型不可用（例如 api-key 还是占位符）时不要让提交日记失败
            log.warn("情绪日记分析调用失败. 原因: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析大模型返回的 JSON，并做字段兜底
     */
    private Map<String, Object> parseEmotionResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultEmotion();
        }
        try {
            // 容错：模型有时会包一层 ```json ``` 代码块
            String cleaned = raw.trim();
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }

            JSONObject json = JSONUtil.parseObj(cleaned);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("primaryEmotion", json.getStr("primaryEmotion", "中性"));
            result.put("emotionScore", json.getInt("emotionScore", 50));
            result.put("isNegative", json.getBool("isNegative", false));
            result.put("riskLevel", json.getInt("riskLevel", 0));
            result.put("suggestion", json.getStr("suggestion", "情绪状态平稳"));
            result.put("improvementSuggestions",
                    json.getJSONArray("improvementSuggestions") != null
                            ? json.getJSONArray("improvementSuggestions").toList(String.class)
                            : new ArrayList<String>());
            return result;
        } catch (Exception e) {
            log.warn("情绪分析结果解析失败, 原始内容={}. 原因: {}", raw, e.getMessage());
            return defaultEmotion();
        }
    }

    /**
     * 兜底情绪结果（大模型失败或无消息时使用）
     */
    private Map<String, Object> defaultEmotion() {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("primaryEmotion", "中性");
        fallback.put("emotionScore", 50);
        fallback.put("isNegative", false);
        fallback.put("riskLevel", 0);
        fallback.put("suggestion", "情绪状态平稳");
        fallback.put("improvementSuggestions", new ArrayList<String>());
        return fallback;
    }
}
