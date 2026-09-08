package org.example.aispingboot.DTO.response;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 情绪日记响应对象
 * <p>
 * 字段对应前端 emotional.vue（后台列表/详情）与 emotionDiary.vue 的取用：
 * diaryDate / moodScore / dominantEmotion / emotionTriggers / diaryContent /
 * sleepQuality / stressLevel / aiEmotionAnalysis，
 * 另外补上 username / nickname（前端详情弹窗要展示用户信息）。
 */
@Data
public class EmotionDiaryResponseDTO {

    // 日记ID
    private Long id;

    // 归属用户ID
    private Long userId;

    // 用户名（关联 user 表）
    private String username;

    // 昵称（关联 user 表）
    private String nickname;

    // 记录日期
    private LocalDate diaryDate;

    // 情绪评分 1-10
    private Integer moodScore;

    // 主要情绪
    private String dominantEmotion;

    // 情绪触发因素
    private String emotionTriggers;

    // 今日感想
    private String diaryContent;

    // 睡眠质量 1-5
    private Integer sleepQuality;

    // 压力水平 1-5
    private Integer stressLevel;

    // AI 情绪分析结果（JSON 字符串，前端详情页解析后渲染建议列表）
    private String aiEmotionAnalysis;

    // AI 分析更新时间
    private LocalDateTime aiAnalysisUpdatedAt;

    // 创建时间
    private LocalDateTime createdAt;

    // 更新时间
    private LocalDateTime updatedAt;
}