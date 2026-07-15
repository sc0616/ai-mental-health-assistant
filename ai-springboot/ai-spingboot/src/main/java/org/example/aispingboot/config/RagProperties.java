package org.example.aispingboot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库检索增强（RAG）配置
 * 对应 application.yml 中的 rag.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /**
     * 总开关。关闭后 AI 对话不再检索知识库，等价于改造前的行为，
     * 便于对比效果或排查"是不是检索导致的回答异常"。
     */
    private boolean enabled = true;

    /**
     * 应用启动后是否自动为所有已发布文章建立索引
     */
    private boolean indexOnStartup = true;

    /**
     * 切分后每个片段的字符数
     */
    private int chunkSize = 400;

    /**
     * 相邻片段的重叠字符数，避免关键句子正好落在切分点上被截断
     */
    private int chunkOverlap = 80;

    /**
     * 每次检索召回的片段数量
     */
    private int topK = 4;

    /**
     * 相似度阈值（0~1）。低于该值的片段不参与上下文拼装，
     * 避免知识库没有相关内容时把无关片段硬塞给模型。
     */
    private double minScore = 0.35;

    /**
     * 拼装进提示词的检索内容最大字符数，防止挤占对话上下文
     */
    private int maxContextChars = 2000;

    /**
     * 是否启用 Tool Calling（工具调用）。
     * <p>
     * 关闭后模型只能用系统提示词里的资料回答，不能在对话过程中
     * 主动查知识库或用户的情绪记录。
     */
    private boolean toolCallingEnabled = true;

    /**
     * 是否注入"可用工具"的使用说明到系统提示词。
     * <p>
     * 说明能显著提升模型主动调用工具的意愿；如果发现模型频繁调用
     * 不必要的工具，可以关掉它让模型只在必要时才用。
     */
    private boolean toolPromptEnabled = true;
}
