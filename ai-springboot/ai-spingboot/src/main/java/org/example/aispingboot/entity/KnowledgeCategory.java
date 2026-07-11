package org.example.aispingboot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文章分类实体
 * <p>
 * 对应库 mental_health_assisant 的 knowledge_category 表，
 * 该表分类名字段为 category_name（不是 name）。
 */
@Data
@TableName("knowledge_category")
@Builder
public class KnowledgeCategory {
    // 分类ID
    @TableId(type = IdType.AUTO)
    private Long id;

    // 分类名称（库中列名为 category_name）
    @TableField("category_name")
    private String name;

    // 分类编码（库中存在，唯一）
    @TableField("category_code")
    private String categoryCode;

    // 父级分类ID，顶级为 0
    @TableField("parent_id")
    private Long parentId;

    // 排序值，越小越靠前
    @TableField("sort_order")
    private Integer sortOrder;

    // 分类描述
    private String description;

    // 状态 0:禁用 1:启用
    private Integer status;

    // 创建时间
    @TableField("created_at")
    private LocalDateTime createdAt;

    // 更新时间
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
