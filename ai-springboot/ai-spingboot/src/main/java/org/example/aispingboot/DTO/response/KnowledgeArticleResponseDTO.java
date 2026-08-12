package org.example.aispingboot.DTO.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识文章响应对象
 * <p>
 * 字段与前端 frontendKnowledge.vue / articleDetail.vue 中的取用保持一致：
 * title / categoryName / authorName / coverImage / readCount / updatedAt / tagArray
 */
@Data
public class KnowledgeArticleResponseDTO {
    // 文章ID（UUID 字符串，与库中 varchar(36) 主键一致）
    private String id;

    // 标题
    private String title;

    // 分类ID
    private Long categoryId;

    // 分类名称（前端列表页展示）
    private String categoryName;

    // 作者ID
    private Long authorId;

    // 作者名称（库中无该列，由 authorId 关联 user 表填充）
    private String authorName;

    // 封面图
    private String coverImage;

    // 摘要
    private String summary;

    // 正文内容（详情页使用）
    private String content;

    // 标签原始字符串
    private String tags;

    // 标签数组（前端详情页 v-for 使用）
    private List<String> tagArray;

    // 阅读量
    private Integer readCount;

    // 发布时间
    private LocalDateTime publishedAt;

    // 更新时间（前端列表页展示日期）
    private LocalDateTime updatedAt;
}
