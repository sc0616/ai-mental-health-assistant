package org.example.aispingboot.rag;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 知识库向量存储
 * <p>
 * <b>为什么不用 Redis 原生的向量检索？</b>
 * Redis 要支持向量检索需要 RediSearch 模块（FT.CREATE / FT.SEARCH），
 * 而本项目环境里是 Redis 3.0，既没有该模块也没有对应命令。
 * 因此这里用"Redis 存向量 + 应用内算相似度"的方式实现：
 * <ul>
 *     <li>每个片段存成一个 Redis String，内容是 {文章ID, 标题, 正文, 向量} 的 JSON</li>
 *     <li>检索时把候选片段读出来，在 JVM 内算余弦相似度并排序</li>
 * </ul>
 * 知识库体量很小（几十篇文章、几百个片段），全量比对的开销远小于一次大模型调用；
 * 若将来文章数量上到万级，应换成带向量索引的方案（如 Redis Stack / Milvus）。
 * <p>
 * 向量以 JSON 数组保存，而不是紧凑的二进制。原因是体积在这个量级完全可接受，
 * 而可读性带来的排查便利更重要（可以直接 redis-cli 看到内容）。
 */
@Slf4j
@Service
public class VectorStore {

    /** 片段 key 前缀：mental:rag:chunk:{chunkId} */
    private static final String CHUNK_PREFIX = "mental:rag:chunk:";

    /** 片段ID集合，用于快速枚举全部片段 */
    private static final String CHUNK_IDS_KEY = "mental:rag:chunk:ids";

    /** 索引元信息（记录向量维度与构建时间），便于判断索引是否过期 */
    private static final String META_KEY = "mental:rag:meta";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 写入/覆盖一个片段
     *
     * @param chunkId 片段ID
     * @param articleId 文章ID
     * @param title 文章标题
     * @param content 片段正文
     * @param embedding 向量
     */
    public void put(String chunkId, String articleId, String title, String content, float[] embedding) {
        if (chunkId == null || embedding == null) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.set("articleId", articleId);
            json.set("title", title);
            json.set("content", content);
            // 向量转成数组存储
            JSONArray vector = new JSONArray();
            for (float v : embedding) {
                // 用 double 存，避免 JSON 序列化时精度丢失过多
                vector.add((double) v);
            }
            json.set("vector", vector);

            stringRedisTemplate.opsForValue().set(CHUNK_PREFIX + chunkId, json.toString());
            stringRedisTemplate.opsForSet().add(CHUNK_IDS_KEY, chunkId);
        } catch (Exception e) {
            log.warn("写入向量片段失败, chunkId={}: {}", chunkId, e.getMessage());
        }
    }

    /**
     * 删除指定文章的全部片段（文章被修改或删除时调用）
     *
     * @return 实际删除的片段数
     */
    public int deleteByArticleId(String articleId) {
        if (articleId == null) {
            return 0;
        }
        int removed = 0;
        try {
            for (String chunkId : listChunkIds()) {
                if (chunkId.startsWith(articleId + "#")) {
                    stringRedisTemplate.delete(CHUNK_PREFIX + chunkId);
                    stringRedisTemplate.opsForSet().remove(CHUNK_IDS_KEY, chunkId);
                    removed++;
                }
            }
        } catch (Exception e) {
            log.warn("删除文章向量片段失败, articleId={}: {}", articleId, e.getMessage());
        }
        return removed;
    }

    /**
     * 清空整个向量索引（重建前调用）
     */
    public void clear() {
        try {
            Set<String> ids = stringRedisTemplate.opsForSet().members(CHUNK_IDS_KEY);
            if (ids != null && !ids.isEmpty()) {
                List<String> keys = new ArrayList<>();
                for (String id : ids) {
                    keys.add(CHUNK_PREFIX + id);
                }
                stringRedisTemplate.delete(keys);
            }
            stringRedisTemplate.delete(CHUNK_IDS_KEY);
            stringRedisTemplate.delete(META_KEY);
        } catch (Exception e) {
            log.warn("清空向量索引失败: {}", e.getMessage());
        }
    }

    /**
     * 记录索引元信息
     */
    public void writeMeta(int chunkCount, int dimensions, long articleCount) {
        try {
            JSONObject meta = new JSONObject();
            meta.set("chunkCount", chunkCount);
            meta.set("dimensions", dimensions);
            meta.set("articleCount", articleCount);
            meta.set("builtAt", System.currentTimeMillis());
            stringRedisTemplate.opsForValue().set(META_KEY, meta.toString());
        } catch (Exception e) {
            log.warn("写入索引元信息失败: {}", e.getMessage());
        }
    }

    /**
     * 读取索引元信息；不存在时返回 null
     */
    public JSONObject readMeta() {
        try {
            String raw = stringRedisTemplate.opsForValue().get(META_KEY);
            return raw == null ? null : JSONUtil.parseObj(raw);
        } catch (Exception e) {
            log.warn("读取索引元信息失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 已索引的片段数量
     */
    public long chunkCount() {
        try {
            Long size = stringRedisTemplate.opsForSet().size(CHUNK_IDS_KEY);
            return size == null ? 0 : size;
        } catch (Exception e) {
            log.warn("统计片段数量失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 检索：返回与查询向量最相似的片段
     * <p>
     * 计算方式为余弦相似度。bge-m3 输出的向量已做归一化，
     * 但这里仍按标准公式计算，不依赖"已归一化"这一前提。
     *
     * @param queryVector 查询向量
     * @param topK        最多返回多少条
     * @param minScore    相似度下限，低于该值不返回
     */
    public List<RetrievedChunk> search(float[] queryVector, int topK, double minScore) {
        List<RetrievedChunk> hits = new ArrayList<>();
        if (queryVector == null || queryVector.length == 0) {
            return hits;
        }

        try {
            Set<String> ids = stringRedisTemplate.opsForSet().members(CHUNK_IDS_KEY);
            if (ids == null || ids.isEmpty()) {
                return hits;
            }

            double queryNorm = norm(queryVector);
            if (queryNorm == 0) {
                return hits;
            }

            for (String chunkId : ids) {
                String raw = stringRedisTemplate.opsForValue().get(CHUNK_PREFIX + chunkId);
                if (raw == null) {
                    // 索引里有 ID 但内容已失效，顺手清掉，避免反复读到空值
                    stringRedisTemplate.opsForSet().remove(CHUNK_IDS_KEY, chunkId);
                    continue;
                }
                JSONObject json;
                try {
                    json = JSONUtil.parseObj(raw);
                } catch (Exception e) {
                    continue;
                }

                JSONArray vectorArray = json.getJSONArray("vector");
                if (vectorArray == null || vectorArray.size() != queryVector.length) {
                    // 维度不一致说明索引是旧模型建的，跳过（重建索引即可修复）
                    continue;
                }

                float[] vector = new float[vectorArray.size()];
                for (int i = 0; i < vectorArray.size(); i++) {
                    vector[i] = vectorArray.getFloat(i);
                }

                double score = cosineSimilarity(queryVector, vector, queryNorm);
                if (score >= minScore) {
                    hits.add(new RetrievedChunk(
                            chunkId,
                            json.getStr("articleId"),
                            json.getStr("title"),
                            json.getStr("content"),
                            score));
                }
            }

            RetrievedChunk.sortByScoreDesc(hits);
            if (hits.size() > topK) {
                return new ArrayList<>(hits.subList(0, topK));
            }
            return hits;
        } catch (Exception e) {
            log.warn("向量检索失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 列出全部片段ID
     */
    private Set<String> listChunkIds() {
        Set<String> ids = stringRedisTemplate.opsForSet().members(CHUNK_IDS_KEY);
        return ids == null ? java.util.Collections.emptySet() : ids;
    }

    /**
     * 余弦相似度 = 点积 / (模长乘积)
     */
    private double cosineSimilarity(float[] a, float[] b, double normA) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        double normB = norm(b);
        if (normB == 0) {
            return 0;
        }
        return dot / (normA * normB);
    }

    private double norm(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        return Math.sqrt(sum);
    }

    /**
     * 便于调试：查看某篇文章当前的片段ID
     */
    public List<String> chunkIdsOf(String articleId) {
        List<String> result = new ArrayList<>();
        if (articleId == null) {
            return result;
        }
        try {
            for (String id : listChunkIds()) {
                if (id.startsWith(articleId + "#")) {
                    result.add(id);
                }
            }
        } catch (Exception e) {
            log.warn("查询文章片段ID失败: {}", e.getMessage());
        }
        return result;
    }

    /** 供外部拼接片段ID */
    public static String buildChunkId(String articleId, int index) {
        return articleId + "#" + index;
    }

    /** 调试用：向量维度的字符串表示 */
    public static String describe(float[] vector) {
        return vector == null ? "null" : Arrays.toString(new int[]{vector.length});
    }
}
