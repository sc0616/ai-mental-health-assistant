package org.example.aispingboot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.aispingboot.entity.ConsultationMessage;
import org.example.aispingboot.entity.ConsultationSession;
import org.example.aispingboot.entity.EmotionDiary;
import org.example.aispingboot.entity.User;
import org.example.aispingboot.mapper.ConsultationMessageMapper;
import org.example.aispingboot.mapper.ConsultationSessionMapper;
import org.example.aispingboot.mapper.EmotionDiaryMapper;
import org.example.aispingboot.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 后台数据统计服务
 * <p>
 * 对应前端 dashboard.vue（管理端"数据分析"页）。该页面调用
 * {@code GET /api/data-analytics/overview}，但后端此前没有这个接口，
 * 导致页面拿到 404 → 所有卡片和图表都空白。
 * <p>
 * <b>返回结构必须与前端严格对齐</b>（字段名写错前端不会报错，只会静默显示空）：
 * <pre>
 * {
 *   "systemOverview":   { totalUsers, activeUsers, totalDiaries, todayNewDiaries,
 *                         totalSessions, todayNewSessions, avgMoodScore },
 *   "consultationStats":{ totalSessions, avgDurationMinutes, dailyTrend:[{date,sessionCount,userCount}] },
 *   "emotionTrend":     [{ date, avgMoodScore, recordCount }],
 *   "userActivity":     [{ date, activeUsers, newUsers, diaryUsers, consultationUsers }]
 * }
 * </pre>
 * <p>
 * 统计口径说明：
 * <ul>
 *     <li>时间范围统一取<b>最近 N 天</b>（含今天），趋势类数据按日期补齐空缺，
 *         保证前端 X 轴连续、不会因为某天没数据而断档。</li>
 *     <li>"活跃用户"定义为近 N 天内产生过日记或咨询记录的去重用户数。</li>
 *     <li>会话时长：本项目没有记录会话结束时间，因此用该会话首尾消息的时间差估算。</li>
 * </ul>
 */
@Slf4j
@Service
public class DataAnalyticsService {

    /** 趋势类数据的默认统计天数 */
    private static final int DEFAULT_TREND_DAYS = 7;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private EmotionDiaryMapper emotionDiaryMapper;

    @Autowired
    private ConsultationSessionMapper consultationSessionMapper;

    @Autowired
    private ConsultationMessageMapper consultationMessageMapper;

    /**
     * 汇总后台数据分析页所需的全部数据
     *
     * @param days 趋势统计天数；为空或非正数时使用默认值
     */
    public Map<String, Object> overview(Integer days) {
        int trendDays = (days == null || days <= 0) ? DEFAULT_TREND_DAYS : days;
        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusDays(trendDays - 1L);

        // 一次性把区间内的数据查出来，避免按天循环查库（N 天 = N×4 次查询）
        List<EmotionDiary> diaries = emotionDiaryMapper.selectList(
                new LambdaQueryWrapper<EmotionDiary>()
                        .ge(EmotionDiary::getDiaryDate, fromDate)
                        .le(EmotionDiary::getDiaryDate, today));

        LocalDateTime fromTime = fromDate.atStartOfDay();
        List<ConsultationSession> sessions = consultationSessionMapper.selectList(
                new LambdaQueryWrapper<ConsultationSession>()
                        .ge(ConsultationSession::getStartedAt, fromTime));
        List<User> users = userMapper.selectList(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("systemOverview", buildSystemOverview(users, diaries, sessions));
        result.put("consultationStats", buildConsultationStats(sessions, trendDays, fromDate, today));
        result.put("emotionTrend", buildEmotionTrend(diaries, trendDays, fromDate, today));
        result.put("userActivity", buildUserActivity(users, diaries, sessions, trendDays, fromDate, today));
        return result;
    }

    /**
     * 顶部四张统计卡片
     */
    private Map<String, Object> buildSystemOverview(List<User> users,
                                                    List<EmotionDiary> diaries,
                                                    List<ConsultationSession> sessions) {
        LocalDate today = LocalDate.now();

        // 去重的作用：count() 会把同一天写了多条日记的用户重复计数
        Set<Long> activeUserIds = diaries.stream()
                .map(EmotionDiary::getUserId)
                .collect(Collectors.toSet());
        sessions.stream().map(ConsultationSession::getUserId).forEach(activeUserIds::add);

        long todayNewDiaries = diaries.stream()
                .filter(d -> today.equals(d.getDiaryDate()))
                .count();
        long todayNewSessions = sessions.stream()
                .filter(s -> s.getStartedAt() != null && today.equals(s.getStartedAt().toLocalDate()))
                .count();

        // 平均情绪评分保留一位小数；没有数据时返回 0 而不是 null，避免前端显示 "null/10"
        double avgMood = diaries.stream()
                .filter(d -> d.getMoodScore() != null)
                .mapToInt(EmotionDiary::getMoodScore)
                .average()
                .orElse(0.0);

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalUsers", users.size());
        overview.put("activeUsers", activeUserIds.size());
        overview.put("totalDiaries", diaries.size());
        overview.put("todayNewDiaries", todayNewDiaries);
        overview.put("totalSessions", sessions.size());
        overview.put("todayNewSessions", todayNewSessions);
        overview.put("avgMoodScore", Math.round(avgMood * 10) / 10.0);
        return overview;
    }

    /**
     * 咨询活动统计（柱状图 + 两个汇总值）
     */
    private Map<String, Object> buildConsultationStats(List<ConsultationSession> sessions,
                                                       int trendDays,
                                                       LocalDate fromDate,
                                                       LocalDate today) {
        // 按日期分组，统计每天的会话数与去重用户数
        Map<LocalDate, List<ConsultationSession>> byDate = sessions.stream()
                .filter(s -> s.getStartedAt() != null)
                .collect(Collectors.groupingBy(s -> s.getStartedAt().toLocalDate()));

        List<Map<String, Object>> dailyTrend = new ArrayList<>();
        for (int i = 0; i < trendDays; i++) {
            LocalDate date = fromDate.plusDays(i);
            List<ConsultationSession> daySessions = byDate.getOrDefault(date, List.of());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date.toString());
            item.put("sessionCount", daySessions.size());
            item.put("userCount", daySessions.stream()
                    .map(ConsultationSession::getUserId)
                    .collect(Collectors.toSet()).size());
            dailyTrend.add(item);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalSessions", sessions.size());
        stats.put("avgDurationMinutes", estimateAvgDurationMinutes(sessions));
        stats.put("dailyTrend", dailyTrend);
        return stats;
    }

    /**
     * 估算平均会话时长（分钟）
     * <p>
     * 数据库没有记录会话结束时间，因此用「同一会话内首条与末条消息的时间差」近似。
     * 只有一条消息的会话时长为 0，不计入平均。
     */
    private long estimateAvgDurationMinutes(List<ConsultationSession> sessions) {
        if (sessions.isEmpty()) {
            return 0L;
        }
        List<Long> sessionIds = sessions.stream().map(ConsultationSession::getId).toList();
        List<ConsultationMessage> messages = consultationMessageMapper.selectList(
                new LambdaQueryWrapper<ConsultationMessage>()
                        .in(ConsultationMessage::getSessionId, sessionIds)
                        .orderByAsc(ConsultationMessage::getCreatedAt));

        Map<Long, LocalDateTime> firstSeen = new LinkedHashMap<>();
        Map<Long, LocalDateTime> lastSeen = new LinkedHashMap<>();
        for (ConsultationMessage m : messages) {
            if (m.getCreatedAt() == null) {
                continue;
            }
            firstSeen.putIfAbsent(m.getSessionId(), m.getCreatedAt());
            lastSeen.put(m.getSessionId(), m.getCreatedAt());
        }

        List<Long> durations = new ArrayList<>();
        for (Map.Entry<Long, LocalDateTime> entry : lastSeen.entrySet()) {
            LocalDateTime start = firstSeen.get(entry.getKey());
            if (start == null) {
                continue;
            }
            long minutes = java.time.Duration.between(start, entry.getValue()).toMinutes();
            if (minutes > 0) {
                durations.add(minutes);
            }
        }
        if (durations.isEmpty()) {
            return 0L;
        }
        return Math.round(durations.stream().mapToLong(Long::longValue).average().orElse(0.0));
    }

    /**
     * 情绪趋势（折线图：平均情绪评分 + 记录数量）
     */
    private List<Map<String, Object>> buildEmotionTrend(List<EmotionDiary> diaries,
                                                        int trendDays,
                                                        LocalDate fromDate,
                                                        LocalDate today) {
        Map<LocalDate, List<EmotionDiary>> byDate = diaries.stream()
                .filter(d -> d.getDiaryDate() != null)
                .collect(Collectors.groupingBy(EmotionDiary::getDiaryDate));

        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 0; i < trendDays; i++) {
            LocalDate date = fromDate.plusDays(i);
            List<EmotionDiary> dayDiaries = byDate.getOrDefault(date, List.of());

            double avg = dayDiaries.stream()
                    .filter(d -> d.getMoodScore() != null)
                    .mapToInt(EmotionDiary::getMoodScore)
                    .average()
                    .orElse(0.0);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date.toString());
            item.put("avgMoodScore", Math.round(avg * 10) / 10.0);
            item.put("recordCount", dayDiaries.size());
            trend.add(item);
        }
        return trend;
    }

    /**
     * 用户活跃度趋势（四条折线）
     * <p>
     * 注意 newUsers 用的是用户注册时间；本项目 User.createdAt 是"记录创建时间"，
     * 与注册时间一致，因此直接用它判断当天新增用户。
     */
    private List<Map<String, Object>> buildUserActivity(List<User> users,
                                                        List<EmotionDiary> diaries,
                                                        List<ConsultationSession> sessions,
                                                        int trendDays,
                                                        LocalDate fromDate,
                                                        LocalDate today) {
        Map<LocalDate, Set<Long>> diaryUsersByDate = diaries.stream()
                .filter(d -> d.getDiaryDate() != null)
                .collect(Collectors.groupingBy(EmotionDiary::getDiaryDate,
                        Collectors.mapping(EmotionDiary::getUserId, Collectors.toSet())));

        Map<LocalDate, Set<Long>> sessionUsersByDate = sessions.stream()
                .filter(s -> s.getStartedAt() != null)
                .collect(Collectors.groupingBy(s -> s.getStartedAt().toLocalDate(),
                        Collectors.mapping(ConsultationSession::getUserId, Collectors.toSet())));

        Map<LocalDate, Long> newUsersByDate = users.stream()
                .filter(u -> u.getCreatedAt() != null)
                .collect(Collectors.groupingBy(u -> u.getCreatedAt().toLocalDate(),
                        Collectors.counting()));

        List<Map<String, Object>> activity = new ArrayList<>();
        for (int i = 0; i < trendDays; i++) {
            LocalDate date = fromDate.plusDays(i);
            Set<Long> diaryUsers = diaryUsersByDate.getOrDefault(date, Set.of());
            Set<Long> sessionUsers = sessionUsersByDate.getOrDefault(date, Set.of());

            // 当天活跃 = 写过日记 或 有过咨询 的去重用户
            Set<Long> activeUsers = new java.util.HashSet<>(diaryUsers);
            activeUsers.addAll(sessionUsers);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date.toString());
            item.put("activeUsers", activeUsers.size());
            item.put("newUsers", newUsersByDate.getOrDefault(date, 0L));
            item.put("diaryUsers", diaryUsers.size());
            item.put("consultationUsers", sessionUsers.size());
            activity.add(item);
        }
        return activity;
    }
}
