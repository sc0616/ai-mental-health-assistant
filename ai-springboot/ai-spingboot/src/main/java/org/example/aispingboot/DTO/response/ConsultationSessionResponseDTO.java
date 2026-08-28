package org.example.aispingboot.DTO.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表 / 详情响应对象
 */
@Data
public class ConsultationSessionResponseDTO {
    // 会话ID
    private Long id;

    // 归属用户ID
    private Long userId;

    // 会话标题
    private String sessionTitle;

    // 开始时间
    private LocalDateTime startedAt;

    // 消息条数
    private Integer messageCount;

    // 最后一条消息预览（前端列表展示用）
    private String lastMessage;

    // 最后一次情绪分析结果(JSON字符串)
    private String lastEmotionAnalysis;

    // 最后一次情绪分析更新时间
    private LocalDateTime lastEmotionUpdatedAt;
}
