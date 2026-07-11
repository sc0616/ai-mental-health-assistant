package org.example.aispingboot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识文章实体
 * <p>
 * 对应库 mental_health_assisant 的 knowledge_article 表。
 * 注意两点与常规建表不同：
 * 1. 主键 id 是 varchar(36) 的 UUID 字符串，不是自增数字；
 * 2. 该表没有 author_name 列，作者信息只有 author_id。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_article")
public class KnowledgeArticle {
    // 文章ID（UUID 字符串，库中为 varchar(36)，非自增）
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    // 标题
    private String title;

    // 分类ID
    @TableField("category_id")
    private Long categoryId;

    // 作者ID（库中无 author_name 列，作者名通过该 ID 关联 user 表得到）
    @TableField("author_id")
    private Long authorId;

    // 封面图地址
    @TableField("cover_image")
    private String coverImage;

    // 摘要
    private String summary;

    // 正文内容(HTML)
    private String content;

    // 标签，逗号分隔
    private String tags;

    // 阅读量
    @TableField("read_count")
    private Integer readCount;

    // 状态 0:草稿 1:已发布
    private Integer status;

    // 发布时间
    @TableField("published_at")
    private LocalDateTime publishedAt;

    // 创建时间
    @TableField("created_at")
    private LocalDateTime createdAt;

    // 更新时间
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
