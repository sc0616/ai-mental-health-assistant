package org.example.aispingboot.rag;

import java.util.List;

/**
 * 检索命中的一个知识片段
 *
 * @param chunkId   片段ID（形如 {文章ID}#{序号}）
 * @param articleId 所属文章ID（UUID 字符串）
 * @param title     文章标题
 * @param content   片段正文
 * @param score     相似度得分（0~1，越大越相关）
 */
public record RetrievedChunk(
        String chunkId,
        String articleId,
        String title,
        String content,
        double score
) {
    /**
     * 拼装进提示词时的文本形式
     */
    public String toContextLine() {
        return "【" + title + "】\n" + content;
    }

    /**
     * 按得分从高到低排序
     */
    public static void sortByScoreDesc(List<RetrievedChunk> chunks) {
        chunks.sort((a, b) -> Double.compare(b.score(), a.score()));
    }
}
