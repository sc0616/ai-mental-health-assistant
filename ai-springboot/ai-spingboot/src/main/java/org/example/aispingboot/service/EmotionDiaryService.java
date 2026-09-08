package org.example.aispingboot.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.example.aispingboot.AiService.EmotionAnalysisService;
import org.example.aispingboot.DTO.command.EmotionDiaryCreateDTO;
import org.example.aispingboot.DTO.response.EmotionDiaryResponseDTO;
import org.example.aispingboot.entity.EmotionDiary;
import org.example.aispingboot.entity.User;
import org.example.aispingboot.mapper.EmotionDiaryMapper;
import org.example.aispingboot.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 情绪日记服务
 * <p>
 * 对应前端两个入口：
 * <ul>
 *     <li>前台 emotionDiary.vue —— 提交当天情绪记录</li>
 *     <li>后台 emotional.vue —— 分页查看 / 删除全部记录</li>
 * </ul>
 */
@Slf4j
@Service
public class EmotionDiaryService {

    @Autowired
    private EmotionDiaryMapper emotionDiaryMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private EmotionAnalysisService emotionAnalysisService;

    /**
     * 提交情绪日记
     * <p>
     * 同一个用户同一天只保留一条记录：已存在则覆盖更新，避免用户反复提交
     * 产生一堆重复的「今日情绪」。
     * <p>
     * AI 情绪分析是「尽力而为」：大模型不可用时（例如 api-key 未配置）
     * 仍然正常保存日记，只是不写 ai_emotion_analysis，
     * 不会因为 AI 故障让用户提交失败。
     *
     * @param userId 当前登录用户ID
     * @param dto    提交内容
     * @return 保存后的日记
     */
    public EmotionDiaryResponseDTO submit(Long userId, EmotionDiaryCreateDTO dto) {
        LocalDateTime now = LocalDateTime.now();

        // 1. 查当天是否已有记录（存在则更新，不存在则新增）
        LambdaQueryWrapper<EmotionDiary> query = new LambdaQueryWrapper<>();
        query.eq(EmotionDiary::getUserId, userId)
                .eq(EmotionDiary::getDiaryDate, dto.getDiaryDate());
        EmotionDiary existing = emotionDiaryMapper.selectOne(query);

        EmotionDiary diary;
        if (existing != null) {
            existing.setMoodScore(dto.getMoodScore());
            existing.setDominantEmotion(dto.getDominantEmotion());
            existing.setEmotionTriggers(dto.getEmotionTriggers());
            existing.setDiaryContent(dto.getDiaryContent());
            existing.setSleepQuality(dto.getSleepQuality());
            existing.setStressLevel(dto.getStressLevel());
            existing.setUpdatedAt(now);
            diary = existing;
        } else {
            diary = EmotionDiary.builder()
                    .userId(userId)
                    .diaryDate(dto.getDiaryDate())
                    .moodScore(dto.getMoodScore())
                    .dominantEmotion(dto.getDominantEmotion())
                    .emotionTriggers(dto.getEmotionTriggers())
                    .diaryContent(dto.getDiaryContent())
                    .sleepQuality(dto.getSleepQuality())
                    .stressLevel(dto.getStressLevel())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
        }

        // 2. 尽力做一次 AI 情绪分析（失败则保持为空，不阻塞保存）
        String analysis = analyzeQuietly(dto);
        if (analysis != null) {
            diary.setAiEmotionAnalysis(analysis);
            diary.setAiAnalysisUpdatedAt(now);
        }

        // 3. 落库
        if (diary.getId() == null) {
            emotionDiaryMapper.insert(diary);
        } else {
            emotionDiaryMapper.updateById(diary);
        }

        return convert(diary, lookupUsers(Collections.singletonList(diary)));
    }

    /**
     * 后台分页查询情绪日记
     * <p>
     * 前端 emotional.vue 传的是 current / size，以及筛选条件 userId 和
     * moodScreRange（注意前端字段名把 Score 拼成了 Scre，这里两个名字都兼容）。
     *
     * @param current           页码，从 1 开始
     * @param size              每页条数
     * @param userId            按用户筛选，可空
     * @param moodScoreRange    情绪评分区间，形如 "1-3" / "4-6" / "7-10"，可空
     */
    public Page<EmotionDiaryResponseDTO> page(long current, long size, Long userId, String moodScoreRange) {
        LambdaQueryWrapper<EmotionDiary> wrapper = new LambdaQueryWrapper<>();

        if (userId != null) {
            wrapper.eq(EmotionDiary::getUserId, userId);
        }

        // 解析 "1-3" 这种区间筛选
        if (StringUtils.hasText(moodScoreRange)) {
            int[] range = parseRange(moodScoreRange);
            if (range != null) {
                wrapper.ge(EmotionDiary::getMoodScore, range[0])
                        .le(EmotionDiary::getMoodScore, range[1]);
            }
        }

        // 最近日期在前；同一天按 id 倒序保证分页稳定
        wrapper.orderByDesc(EmotionDiary::getDiaryDate)
                .orderByDesc(EmotionDiary::getId);

        Page<EmotionDiary> page = emotionDiaryMapper.selectPage(new Page<>(current, size), wrapper);

        Page<EmotionDiaryResponseDTO> result = new Page<>(
                page.getCurrent(), page.getSize(), page.getTotal());
        Map<Long, User> users = lookupUsers(page.getRecords());
        result.setRecords(page.getRecords().stream()
                .map(diary -> convert(diary, users))
                .collect(Collectors.toList()));
        return result;
    }

    /**
     * 删除一条情绪日记（后台）
     */
    public boolean delete(Long id) {
        if (id == null || emotionDiaryMapper.selectById(id) == null) {
            return false;
        }
        emotionDiaryMapper.deleteById(id);
        return true;
    }

    // ---------------- 私有方法 ----------------

    /**
     * 调用 AI 做情绪分析，返回 JSON 字符串；失败或空内容返回 null
     */
    private String analyzeQuietly(EmotionDiaryCreateDTO dto) {
        StringBuilder text = new StringBuilder();
        text.append("记录日期：").append(dto.getDiaryDate()).append("\n");
        text.append("情绪评分（1-10，越高越积极）：").append(dto.getMoodScore()).append("\n");
        if (StringUtils.hasText(dto.getDominantEmotion())) {
            text.append("自评主要情绪：").append(dto.getDominantEmotion()).append("\n");
        }
        if (dto.getSleepQuality() != null) {
            text.append("睡眠质量（1-5）：").append(dto.getSleepQuality()).append("\n");
        }
        if (dto.getStressLevel() != null) {
            text.append("压力水平（1-5）：").append(dto.getStressLevel()).append("\n");
        }
        if (StringUtils.hasText(dto.getEmotionTriggers())) {
            text.append("情绪触发因素：").append(dto.getEmotionTriggers()).append("\n");
        }
        if (StringUtils.hasText(dto.getDiaryContent())) {
            text.append("今日感想：").append(dto.getDiaryContent()).append("\n");
        }

        Map<String, Object> analysis = emotionAnalysisService.analyzeDiaryEmotion(text.toString());
        if (analysis == null) {
            return null;
        }
        try {
            return JSONUtil.toJsonStr(analysis);
        } catch (Exception e) {
            log.warn("序列化情绪分析结果失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 批量查用户，避免逐条查询造成的 N+1
     */
    private Map<Long, User> lookupUsers(List<EmotionDiary> diaries) {
        Map<Long, User> map = new HashMap<>();
        if (diaries == null || diaries.isEmpty()) {
            return map;
        }
        List<Long> userIds = diaries.stream()
                .map(EmotionDiary::getUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (userIds.isEmpty()) {
            return map;
        }
        try {
            for (User user : userMapper.selectBatchIds(userIds)) {
                map.put(user.getId(), user);
            }
        } catch (Exception e) {
            // 用户信息查不到不应影响日记列表展示
            log.warn("批量查询日记所属用户失败: {}", e.getMessage());
        }
        return map;
    }

    /**
     * 解析 "1-3" 形式的区间，格式非法时返回 null
     */
    private int[] parseRange(String range) {
        String[] parts = range.split("-");
        if (parts.length != 2) {
            return null;
        }
        try {
            int min = Integer.parseInt(parts[0].trim());
            int max = Integer.parseInt(parts[1].trim());
            return min <= max ? new int[]{min, max} : new int[]{max, min};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 实体转 DTO，并补上用户名 / 昵称
     */
    private EmotionDiaryResponseDTO convert(EmotionDiary diary, Map<Long, User> users) {
        EmotionDiaryResponseDTO dto = new EmotionDiaryResponseDTO();
        dto.setId(diary.getId());
        dto.setUserId(diary.getUserId());
        dto.setDiaryDate(diary.getDiaryDate());
        dto.setMoodScore(diary.getMoodScore());
        dto.setDominantEmotion(diary.getDominantEmotion());
        dto.setEmotionTriggers(diary.getEmotionTriggers());
        dto.setDiaryContent(diary.getDiaryContent());
        dto.setSleepQuality(diary.getSleepQuality());
        dto.setStressLevel(diary.getStressLevel());
        dto.setAiEmotionAnalysis(diary.getAiEmotionAnalysis());
        dto.setAiAnalysisUpdatedAt(diary.getAiAnalysisUpdatedAt());
        dto.setCreatedAt(diary.getCreatedAt());
        dto.setUpdatedAt(diary.getUpdatedAt());

        User user = users == null ? null : users.get(diary.getUserId());
        if (user != null) {
            dto.setUsername(user.getUsername());
            dto.setNickname(user.getNickname());
        }
        return dto;
    }
}