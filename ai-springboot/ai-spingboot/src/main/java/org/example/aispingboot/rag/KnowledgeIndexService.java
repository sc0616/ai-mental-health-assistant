package org.example.aispingboot.rag;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.aispingboot.config.RagProperties;
import org.example.aispingboot.entity.KnowledgeArticle;
import org.example.aispingboot.mapper.KnowledgeArticleMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库索引构建服务
 * <p>
 * 负责把知识文章切分、向量化并写入 {@link VectorStore}。
 * <p>
 * <b>索引内容的选取</b>：标题 + 摘要 + 正文一起参与向量化。
 * 标题和摘要往往浓缩了文章主题，把它们拼进去能显著提升检索命中率。
 * 但存进片段的正文只保留片段本身，避免重复占用上下文。
 * <p>
 * <b>何时重建</b>：
 * <ul>
 *     <li>应用启动后（可配置）——保证重启不会丢索引</li>
 *     <li>文章新增 / 修改 / 删除时——只重建这一篇，不做全量重建</li>
 * </ul>
 * 重建在异步线程执行，不阻塞接口响应。
 */
@Slf4j
@Service
public class KnowledgeIndexService {

    @Autowired
    private KnowledgeArticleMapper knowledgeArticleMapper;

    @Autowired
    private TextChunker textChunker;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RagProperties ragProperties;

    /**
     * 应用就绪后自动建索引
     * <p>
     * 用 ApplicationReadyEvent 而不是 @PostConstruct：此时数据源与
     * 外部客户端都已初始化完成，避免过早调用导致连接不可用。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!ragProperties.isEnabled()) {
            log.info("RAG 已关闭（rag.enabled=false），跳过知识库索引构建");
            return;
        }
        if (!ragProperties.isIndexOnStartup()) {
            log.info("rag.index-on-startup=false，跳过启动时索引构建");
            return;
        }
        rebuildAllAsync();
    }

    /**
     * 异步全量重建索引
     */
    @Async
    public void rebuildAllAsync() {
        try {
            rebuildAll();
        } catch (Exception e) {
            log.error("知识库索引重建失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 全量重建索引：清空后重新索引所有已发布文章
     *
     * @return 建好的片段数
     */
    public synchronized int rebuildAll() {
        long start = System.currentTimeMillis();

        if (!embeddingService.isAvailable()) {
            log.warn("EmbeddingModel 不可用，跳过索引构建");
            return 0;
        }

        List<KnowledgeArticle> articles = knowledgeArticleMapper.selectList(
                new LambdaQueryWrapper<KnowledgeArticle>().eq(KnowledgeArticle::getStatus, 1));

        if (articles.isEmpty()) {
            log.info("没有已发布文章，跳过索引构建");
            return 0;
        }

        // 先清空，保证不会残留已删除文章的旧片段
        vectorStore.clear();

        int totalChunks = 0;
        int failed = 0;
        for (KnowledgeArticle article : articles) {
            int built = indexArticle(article);
            totalChunks += built;
            if (built == 0) {
                failed++;
            }
        }

        int dimensions = embeddingService.dimensions();
        vectorStore.writeMeta(totalChunks, dimensions, articles.size());

        log.info("知识库索引构建完成：{} 篇文章 -> {} 个片段，维度 {}，失败 {} 篇，耗时 {} ms",
                articles.size(), totalChunks, dimensions, failed, System.currentTimeMillis() - start);
        return totalChunks;
    }

    /**
     * 为一篇文章建立索引（会先清掉它的旧片段）
     *
     * @return 生成的片段数
     */
    public int indexArticle(KnowledgeArticle article) {
        if (article == null || article.getId() == null) {
            return 0;
        }
        if (!embeddingService.isAvailable()) {
            return 0;
        }

        // 先删旧片段，避免文章改短后残留老内容
        vectorStore.deleteByArticleId(article.getId());

        // 拼装用于向量化的文本：标题 + 摘要 + 正文
        StringBuilder source = new StringBuilder();
        if (article.getTitle() != null) {
            source.append(article.getTitle()).append("\n");
        }
        if (article.getSummary() != null && !article.getSummary().isBlank()) {
            source.append(article.getSummary()).append("\n");
        }
        if (article.getContent() != null) {
            source.append(article.getContent());
        }

        List<String> chunks = textChunker.chunk(source.toString());
        if (chunks.isEmpty()) {
            return 0;
        }

        int saved = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            float[] vector = embeddingService.embed(chunk);
            if (vector == null) {
                continue;
            }
            vectorStore.put(
                    VectorStore.buildChunkId(article.getId(), i),
                    article.getId(),
                    article.getTitle(),
                    chunk,
                    vector);
            saved++;
        }

        // 单篇重建后刷新元信息中的片段总数
        refreshMetaIfExists();
        return saved;
    }

    /**
     * 删除某篇文章的索引
     */
    public void removeArticle(String articleId) {
        int removed = vectorStore.deleteByArticleId(articleId);
        if (removed > 0) {
            log.info("已移除文章 {} 的 {} 个索引片段", articleId, removed);
            refreshMetaIfExists();
        }
    }

    /**
     * 索引元信息
     */
    public JSONObject status() {
        JSONObject meta = vectorStore.readMeta();
        if (meta == null) {
            meta = new JSONObject();
            meta.set("chunkCount", vectorStore.chunkCount());
            meta.set("dimensions", -1);
            meta.set("articleCount", 0);
            meta.set("builtAt", null);
        }
        meta.set("enabled", ragProperties.isEnabled());
        meta.set("topK", ragProperties.getTopK());
        meta.set("minScore", ragProperties.getMinScore());
        meta.set("chunkSize", ragProperties.getChunkSize());
        meta.set("currentChunkCount", vectorStore.chunkCount());
        return meta;
    }

    /**
     * 元信息已存在时，按当前实际片段数刷新它
     */
    private void refreshMetaIfExists() {
        JSONObject meta = vectorStore.readMeta();
        if (meta == null) {
            return;
        }
        Long chunkCount = vectorStore.chunkCount();
        vectorStore.writeMeta(
                chunkCount == null ? 0 : chunkCount.intValue(),
                meta.getInt("dimensions", -1),
                meta.getInt("articleCount", 0));
    }

    /**
     * 便于测试与维护：返回当前索引覆盖的文章ID
     */
    public List<String> indexedArticleIds() {
        List<String> ids = new ArrayList<>();
        JSONObject meta = vectorStore.readMeta();
        if (meta != null) {
            ids.addAll(vectorStore.chunkIdsOf(meta.getStr("articleId", "")));
        }
        return ids;
    }
}
