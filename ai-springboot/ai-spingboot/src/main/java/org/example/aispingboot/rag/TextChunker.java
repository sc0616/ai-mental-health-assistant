package org.example.aispingboot.rag;

import org.example.aispingboot.config.RagProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切分器
 * <p>
 * RAG 的第一步是把长文切成小片段：整篇文章直接向量化会让语义被"平均"掉，
 * 检索时既不精确也浪费 token。切分策略上有两点讲究：
 * <ul>
 *     <li><b>先清洗 HTML</b>：知识文章正文是富文本，标签本身没有语义，
 *         还会干扰相似度计算，必须先转成纯文本。</li>
 *     <li><b>按句子边界切</b>：硬按字符数截断容易把一句话劈成两半，
 *         导致两个片段都读不通，所以优先在句号/换行处断开。</li>
 * </ul>
 * 相邻片段保留一段重叠（overlap），避免关键信息正好落在切分点上而丢失。
 */
@Component
public class TextChunker {

    @Autowired
    private RagProperties ragProperties;

    /** 句子结束符，用于优先在这些位置断开 */
    private static final String SENTENCE_ENDINGS = "。！？!?；;\n";

    /**
     * 把一段富文本切成若干片段
     *
     * @param rawText 原始文本（可以是 HTML）
     * @return 纯文本片段列表；输入为空时返回空列表
     */
    public List<String> chunk(String rawText) {
        String text = stripHtml(rawText);
        return chunkPlainText(text);
    }

    /**
     * 对已清洗的纯文本做切分
     */
    public List<String> chunkPlainText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        int chunkSize = Math.max(50, ragProperties.getChunkSize());
        int overlap = Math.max(0, Math.min(ragProperties.getChunkOverlap(), chunkSize / 2));

        int start = 0;
        int length = text.length();

        while (start < length) {
            int end = Math.min(start + chunkSize, length);

            // 未到文末时，尝试回退到最近的句子边界，让片段读起来完整
            if (end < length) {
                int boundary = findBoundary(text, start, end, chunkSize);
                if (boundary > start) {
                    end = boundary;
                }
            }

            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }

            if (end >= length) {
                break;
            }
            // 下一段的起点回退 overlap 个字符，形成重叠
            start = Math.max(start + 1, end - overlap);
        }
        return chunks;
    }

    /**
     * 在 [start, end] 区间内寻找合适的切分点。
     * <p>
     * 从 end 往前找句末标点；如果往前找得太远（超过片段长度的一半），
     * 说明这段文字很长且没有标点，此时宁可按字符数硬切，
     * 否则会产生过短的碎片。
     */
    private int findBoundary(String text, int start, int end, int chunkSize) {
        int floor = Math.max(start + chunkSize / 2, start + 1);
        for (int i = end - 1; i >= floor; i--) {
            if (SENTENCE_ENDINGS.indexOf(text.charAt(i)) >= 0) {
                // 标点本身归入前一个片段
                return i + 1;
            }
        }
        return end;
    }

    /**
     * 去除 HTML 标签、脚本样式与多余空白，得到纯文本
     */
    public String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = html;
        // 脚本与样式整块丢弃（内容不是正文）
        text = text.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        text = text.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        // 块级标签换成换行，保留段落结构，便于后续按句子切分
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</(p|div|li|h[1-6]|tr|section|article)>", "\n");
        // 其余标签直接去掉
        text = text.replaceAll("(?s)<[^>]+>", " ");
        // HTML 实体
        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        // 压缩连续空白：空格统一，多空行合并
        text = text.replaceAll("[\\t\\x0B\\f\\r]+", " ");
        text = text.replaceAll(" *\n *", "\n");
        text = text.replaceAll("\n{2,}", "\n");
        text = text.replaceAll(" {2,}", " ");
        return text.trim();
    }
}
