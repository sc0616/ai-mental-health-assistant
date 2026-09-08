package org.example.aispingboot.DTO.command;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 提交情绪日记的请求参数
 * <p>
 * 字段与前端 emotionDiary.vue 的 diaryForm 一一对应。
 */
@Data
public class EmotionDiaryCreateDTO {

    // 记录日期，前端默认传当天（格式 yyyy-MM-dd）
    @NotNull(message = "记录日期不能为空")
    private LocalDate diaryDate;

    // 情绪评分 1-10，对应前端 el-rate :max="10"
    @NotNull(message = "情绪评分不能为空")
    @Min(value = 1, message = "情绪评分最低为1")
    @Max(value = 10, message = "情绪评分最高为10")
    private Integer moodScore;

    // 主要情绪
    @Size(max = 50, message = "主要情绪长度不能超过50个字符")
    private String dominantEmotion;

    // 情绪触发因素
    @Size(max = 1000, message = "情绪触发因素长度不能超过1000个字符")
    private String emotionTriggers;

    // 今日感想
    @Size(max = 2000, message = "今日感想长度不能超过2000个字符")
    private String diaryContent;

    // 睡眠质量 1-5
    @Min(value = 1, message = "睡眠质量最低为1")
    @Max(value = 5, message = "睡眠质量最高为5")
    private Integer sleepQuality;

    // 压力水平 1-5
    @Min(value = 1, message = "压力水平最低为1")
    @Max(value = 5, message = "压力水平最高为5")
    private Integer stressLevel;
}