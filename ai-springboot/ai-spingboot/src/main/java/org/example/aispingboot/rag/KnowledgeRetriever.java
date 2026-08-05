package org.example.aispingboot.rag;

import lombok.extern.slf4j.Slf4j;
import org.example.aispingboot.config.RagProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库检索器（RAG 的 R）
 * <p>
 * 输入用户问题，输出最相关的知识片段。整个链路：
 * <pre>
 *   问题 --向量化--> 查询向量 --余弦相似度--> top-k 片段 --阈值过滤--> 拼装上下文
 * </pre>
 * 任何一步失败都返回空列表，让上层降级为"不带知识库的普通对话"，
 * 而不是把异常抛给用户。
 */
@Slf4j
@Service
public class KnowledgeRetriever {

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RagProperties ragProperties;

    /**
     * 检索与问题最相关的知识片段
     *
     * @param query 用户问题
     * @return 片段列表（已按相关度降序）；无结果或失败时返回空列表
     */
    public List<RetrievedChunk> retrieve(String query) {
        if (!ragProperties.isEnabled()) {
            return new ArrayList<>();
        }
        if (query == null || query.isBlank()) {
            return new ArrayList<>();
        }
        // 索引为空说明尚未构建，直接返回，避免无意义的外部调用
        if (vectorStore.chunkCount() == 0) {
            log.debug("向量索引为空，跳过检索");
            return new ArrayList<>();
        }

        float[] queryVector = embeddingService.embed(query);
        if (queryVector == null) {
            log.warn("问题向量化失败，本次对话不启用知识库检索");
            return new ArrayList<>();
        }

        List<RetrievedChunk> hits = vectorStore.search(
                queryVector, ragProperties.getTopK(), ragProperties.getMinScore());

        if (log.isDebugEnabled()) {
            log.debug("检索到 {} 个片段: {}", hits.size(),
                    hits.stream().map(h -> String.format("%.3f", h.score())).toList());
        }
        return hits;
    }

    /**
     * 把检索结果拼装成可放进提示词的上下文文本
     * <p>
     * 超出 {@code rag.max-context-chars} 的部分会被截断——
     * 检索内容不能挤占对话历史，否则模型会"只看资料不看用户"。
     *
     * @param chunks 检索结果
     * @return 上下文文本；无内容时返回空串
     */
    public String buildContext(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        int limit = ragProperties.getMaxContextChars();
        StringBuilder sb = new StringBuilder();
        int index = 1;

        for (RetrievedChunk chunk : chunks) {
            String block = index + ". " + chunk.toContextLine() + "\n";
            if (sb.length() + block.length() > limit) {
                // 还能放下部分内容时截断使用，放不下就停止
                int remain = limit - sb.length();
                if (remain > 80) {
                    sb.append(block, 0, Math.min(block.length(), remain)).append("...\n");
                }
                break;
            }
            sb.append(block);
            index++;
        }
        return sb.toString().trim();
    }

    /**
     * 检索并直接返回上下文文本（供 AI 对话调用）
     */
    public String retrieveContext(String query) {
        return buildContext(retrieve(query));
    }

    /**
     * 供接口展示：带文章归属信息的检索结果
     */
    public List<RetrievedChunk> search(String query, Integer topK, Double minScore) {
        if (query == null || query.isBlank()) {
            return new ArrayList<>();
        }
        float[] queryVector = embeddingService.embed(query);
        if (queryVector == null) {
            return new ArrayList<>();
        }
        int k = topK == null || topK <= 0 ? ragProperties.getTopK() : topK;
        double min = minScore == null ? ragProperties.getMinScore() : minScore;
        return vectorStore.search(queryVector, k, min);
    }
}
