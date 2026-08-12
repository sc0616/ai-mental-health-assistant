package org.example.aispingboot.controller;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.example.aispingboot.DTO.response.KnowledgeArticleResponseDTO;
import org.example.aispingboot.common.Result;
import org.example.aispingboot.entity.KnowledgeArticle;
import org.example.aispingboot.exception.BusinessException;
import org.example.aispingboot.rag.KnowledgeIndexService;
import org.example.aispingboot.rag.KnowledgeRetriever;
import org.example.aispingboot.rag.RetrievedChunk;
import org.example.aispingboot.service.KnowledgeArticleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识文章接口
 * <p>
 * 前端 api/frontend.js 与 api/admin.js 中的知识模块此前没有对应实现，
 * 这里补齐列表、详情、分类树三个查询接口。
 * <p>
 * 另外提供 RAG（检索增强生成）相关接口：语义检索、索引状态、手动重建索引。
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    @Autowired
    private KnowledgeArticleService knowledgeArticleService;

    /** RAG：语义检索 */
    @Autowired
    private KnowledgeRetriever knowledgeRetriever;

    /** RAG：索引构建与状态 */
    @Autowired
    private KnowledgeIndexService knowledgeIndexService;

    /**
     * 文章分页列表
     * <p>
     * 兼容前端传参：currentPage / size / sortField / sortDirection
     */
    @GetMapping("/article/page")
    public Result<Page<KnowledgeArticleResponseDTO>> pageArticles(
            @RequestParam(defaultValue = "1") long currentPage,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortDirection) {
        return Result.ok(knowledgeArticleService.pageArticles(currentPage, size, sortField, sortDirection));
    }

    /**
     * 文章详情
     * <p>
     * 文章主键是 varchar(36) 的 UUID，所以这里用 String 接收。
     */
    @GetMapping("/article/{id}")
    public Result<KnowledgeArticleResponseDTO> getArticleDetail(@PathVariable String id) {
        KnowledgeArticleResponseDTO detail = knowledgeArticleService.getArticleDetail(id);
        if (detail == null) {
            throw new BusinessException("文章不存在");
        }
        return Result.ok(detail);
    }

    /**
     * 分类树
     */
    @GetMapping("/category/tree")
    public Result<List<Map<String, Object>>> categoryTree() {
        return Result.ok(knowledgeArticleService.categoryTree());
    }

    /**
     * 新增文章（后台）
     * <p>
     * 新增后立即为该文章建立 RAG 索引，保证新内容马上能被 AI 检索到。
     */
    @PostMapping("/article")
    public Result<KnowledgeArticleResponseDTO> createArticle(@RequestBody KnowledgeArticleResponseDTO dto) {
        KnowledgeArticleResponseDTO created = knowledgeArticleService.createArticle(dto);
        reindexArticle(created.getId());
        return Result.ok(created);
    }

    /**
     * 修改文章（后台）
     * <p>
     * 修改后重建该文章的索引片段（先删旧片段再建新的），
     * 避免改短正文后还残留旧内容被检索出来。
     */
    @PutMapping("/article/{id}")
    public Result<KnowledgeArticleResponseDTO> updateArticle(@PathVariable String id,
                                                             @RequestBody KnowledgeArticleResponseDTO dto) {
        KnowledgeArticleResponseDTO updated = knowledgeArticleService.updateArticle(id, dto);
        if (updated == null) {
            throw new BusinessException("文章不存在");
        }
        reindexArticle(id);
        return Result.ok(updated);
    }

    /**
     * 修改文章状态（后台，上架/下架）
     * <p>
     * 下架的文章不应再被 AI 引用，因此状态变化时同步调整索引：
     * 上架则建索引，下架则移除索引。
     */
    @PutMapping("/article/{id}/status")
    public Result<Boolean> changeStatus(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object status = body.get("status");
        if (status == null) {
            throw new BusinessException("缺少 status 参数");
        }
        Integer statusValue = Integer.valueOf(status.toString());
        boolean ok = knowledgeArticleService.changeStatus(id, statusValue);
        if (!ok) {
            throw new BusinessException("文章不存在");
        }
        // 状态 1 = 已发布，才需要进索引
        if (statusValue != null && statusValue == 1) {
            reindexArticle(id);
        } else {
            knowledgeIndexService.removeArticle(id);
        }
        return Result.ok(true);
    }

    /**
     * 删除文章（后台）
     */
    @DeleteMapping("/article/{id}")
    public Result<Boolean> deleteArticle(@PathVariable String id) {
        boolean ok = knowledgeArticleService.deleteArticle(id);
        if (!ok) {
            throw new BusinessException("文章不存在");
        }
        // 文章没了，索引也要一并清掉，否则会检索到已删除的内容
        knowledgeIndexService.removeArticle(id);
        return Result.ok(true);
    }

    // ==================== RAG 检索增强相关接口 ====================

    /**
     * 语义检索知识库
     * <p>
     * 用于验证 RAG 效果：输入一句话，返回最相关的知识片段及相似度得分。
     * 与关键词搜索不同，这里是"按意思找"——例如搜"睡不着怎么办"
     * 也能命中标题为"睡眠质量与心理健康"的文章。
     *
     * @param query    查询语句
     * @param topK     返回条数，默认取 rag.top-k
     * @param minScore 相似度下限，默认取 rag.min-score
     */
    @GetMapping("/search")
    public Result<List<Map<String, Object>>> search(
            @RequestParam String query,
            @RequestParam(required = false) Integer topK,
            @RequestParam(required = false) Double minScore) {
        if (query == null || query.isBlank()) {
            throw new BusinessException("查询内容不能为空");
        }
        List<RetrievedChunk> chunks = knowledgeRetriever.search(query, topK, minScore);

        List<Map<String, Object>> result = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("articleId", chunk.articleId());
            item.put("title", chunk.title());
            item.put("content", chunk.content());
            // 保留 4 位小数，便于观察排序差异
            item.put("score", Math.round(chunk.score() * 10000) / 10000.0);
            result.add(item);
        }
        return Result.ok(result);
    }

    /**
     * 查看 RAG 索引状态：片段数、向量维度、构建时间等
     */
    @GetMapping("/rag/status")
    public Result<JSONObject> ragStatus() {
        return Result.ok(knowledgeIndexService.status());
    }

    /**
     * 手动重建全量索引
     * <p>
     * 会为所有已发布文章重新切分并向量化。文章较多时耗时较长，
     * 因此走异步执行，接口立即返回，具体进度看日志。
     */
    @PostMapping("/rag/rebuild")
    public Result<String> rebuildIndex() {
        knowledgeIndexService.rebuildAllAsync();
        return Result.ok("索引重建已开始，请稍后通过 /api/knowledge/rag/status 查看结果");
    }

    /**
     * 为单篇文章重建索引（内部使用）
     * <p>
     * 直接取数据库实体，而不是走带缓存的详情接口——索引必须基于最新正文，
     * 且不应因为"重建索引"而累加文章阅读量。
     * <p>
     * 这里刻意吞掉异常：索引失败不应导致"保存文章"这个主流程报错，
     * 否则用户会以为文章没保存成功。
     */
    private void reindexArticle(String articleId) {
        try {
            KnowledgeArticle article = knowledgeArticleService.getArticleEntity(articleId);
            if (article == null) {
                return;
            }
            knowledgeIndexService.indexArticle(article);
        } catch (Exception e) {
            log.warn("文章 {} 索引重建失败: {}", articleId, e.getMessage());
        }
    }
}
