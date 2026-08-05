package org.example.aispingboot.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 向量化服务
 * <p>
 * 把文本转成向量（embedding）。这里直接复用 Spring AI 的 {@link EmbeddingModel}，
 * 它由 spring-ai-starter-model-openai 自动装配，指向 application.yml 中配置的
 * 硅基流动 bge-m3（输出 1024 维）。
 * <p>
 * 全部方法都做了异常兜底并返回空结果：向量化依赖外部接口，
 * 一旦网络或额度出问题，RAG 应当"降级为不检索"，而不是让聊天直接报错。
 */
@Slf4j
@Service
public class EmbeddingService {

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    /**
     * 向量维度，首次调用后缓存下来
     */
    private volatile Integer dimensions;

    /**
     * 单条文本向量化
     *
     * @return 向量；失败或未配置时返回 null
     */
    public float[] embed(String text) {
        if (embeddingModel == null || text == null || text.isBlank()) {
            return null;
        }
        try {
            float[] vector = embeddingModel.embed(text);
            if (vector != null && vector.length > 0) {
                dimensions = vector.length;
            }
            return vector;
        } catch (Exception e) {
            log.warn("文本向量化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 批量向量化
     * <p>
     * 走批接口可以减少 HTTP 往返次数；若批接口失败则退化为逐条调用，
     * 避免一条文本有问题导致整批失败。
     *
     * @return 与入参等长的列表，失败位置为 null
     */
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> result = new ArrayList<>();
        if (texts == null || texts.isEmpty()) {
            return result;
        }
        if (embeddingModel == null) {
            return new ArrayList<>(Collections.nCopies(texts.size(), null));
        }

        try {
            List<float[]> vectors = embeddingModel.embed(texts);
            if (vectors != null && vectors.size() == texts.size()) {
                if (!vectors.isEmpty() && vectors.get(0) != null) {
                    dimensions = vectors.get(0).length;
                }
                return vectors;
            }
        } catch (Exception e) {
            log.warn("批量向量化失败，改为逐条处理: {}", e.getMessage());
        }

        // 退化路径：逐条调用
        for (String text : texts) {
            result.add(embed(text));
        }
        return result;
    }

    /**
     * 当前使用的向量维度（探测失败时返回 -1）
     */
    public int dimensions() {
        if (dimensions != null) {
            return dimensions;
        }
        if (embeddingModel == null) {
            return -1;
        }
        try {
            float[] probe = embeddingModel.embed("维度探测");
            if (probe != null) {
                dimensions = probe.length;
                return dimensions;
            }
        } catch (Exception e) {
            log.warn("探测向量维度失败: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * 向量化能力是否可用
     */
    public boolean isAvailable() {
        return embeddingModel != null;
    }
}
