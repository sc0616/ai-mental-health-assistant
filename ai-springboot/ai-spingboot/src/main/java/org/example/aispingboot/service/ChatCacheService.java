package org.example.aispingboot.service;

import lombok.extern.slf4j.Slf4j;
import org.example.aispingboot.common.CacheKeys;
import org.example.aispingboot.config.ChatCacheProperties;
import org.example.aispingboot.DTO.response.ConsultationMessageResponseDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 咨询对话缓存服务
 * <p>
 * 核心设计原则：<b>Redis 只是加速层，MySQL 才是唯一事实来源</b>。
 * 因此这里所有 Redis 操作都做了异常兜底——一旦 Redis 连不上（没装、挂了、网络抖动），
 * 方法会打日志并返回「未命中 / 空」，上层自然回源查 MySQL，业务功能不受影响。
 * 这样才能保证「加了缓存」不会变成「加了故障点」。
 */
@Slf4j
@Service
public class ChatCacheService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ChatCacheProperties cacheProperties;

    /**
     * 读取会话消息缓存
     *
     * @return 命中返回消息列表；未命中或 Redis 异常返回 null
     */
    @SuppressWarnings("unchecked")
    public List<ConsultationMessageResponseDTO> getMessages(Long sessionId) {
        if (!cacheProperties.isEnabled()) {
            return null;
        }
        String key = CacheKeys.chatMessages(sessionId);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof List<?> list) {
                log.debug("会话消息命中缓存, sessionId={}, size={}", sessionId, list.size());
                return (List<ConsultationMessageResponseDTO>) list;
            }
            return null;
        } catch (Exception e) {
            // 缓存读失败不能影响主流程
            log.warn("读取会话消息缓存失败, sessionId={}, 将回源数据库. 原因: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 写入会话消息缓存（覆盖式写入全量消息）
     */
    public void putMessages(Long sessionId, List<ConsultationMessageResponseDTO> messages) {
        if (!cacheProperties.isEnabled() || messages == null) {
            return;
        }
        String key = CacheKeys.chatMessages(sessionId);
        try {
            redisTemplate.opsForValue().set(
                    key,
                    new ArrayList<>(messages),
                    cacheProperties.getMessageTtlHours(),
                    TimeUnit.HOURS
            );
            log.debug("写入会话消息缓存, sessionId={}, size={}", sessionId, messages.size());
        } catch (Exception e) {
            log.warn("写入会话消息缓存失败, sessionId={}. 原因: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 追加一条消息到缓存
     * <p>
     * 对话过程中每产生一条消息就调用一次，这样即使不查库也能拿到最新上下文。
     * 若缓存不存在（未命中），这里不主动创建——避免缓存与数据库不一致，
     * 交由下次查询时从数据库回填。
     *
     * @return true 表示追加成功
     */
    public boolean appendMessage(Long sessionId, ConsultationMessageResponseDTO message) {
        if (!cacheProperties.isEnabled() || message == null) {
            return false;
        }
        String key = CacheKeys.chatMessages(sessionId);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof List<?> list) {
                List<ConsultationMessageResponseDTO> messages = new ArrayList<>((List<ConsultationMessageResponseDTO>) list);
                messages.add(message);
                redisTemplate.opsForValue().set(
                        key,
                        messages,
                        cacheProperties.getMessageTtlHours(),
                        TimeUnit.HOURS
                );
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("追加会话消息缓存失败, sessionId={}. 原因: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 失效某个会话的消息缓存
     * <p>
     * 新增/删除消息后调用，保证下次读取能拿到最新数据。
     * 注意：这里选择「删除」而不是「更新」，是为了避免并发写导致缓存里出现脏数据。
     */
    public void evictMessages(Long sessionId) {
        try {
            redisTemplate.delete(CacheKeys.chatMessages(sessionId));
            log.debug("清除会话消息缓存, sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("清除会话消息缓存失败, sessionId={}. 原因: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 失效某个用户的会话列表缓存
     */
    public void evictUserSessions(Long userId) {
        try {
            redisTemplate.delete(CacheKeys.userSessions(userId));
            log.debug("清除用户会话列表缓存, userId={}", userId);
        } catch (Exception e) {
            log.warn("清除用户会话列表缓存失败, userId={}. 原因: {}", userId, e.getMessage());
        }
    }

    /**
     * 读取会话情绪分析缓存
     */
    public Object getEmotion(Long sessionId) {
        if (!cacheProperties.isEnabled()) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().get(CacheKeys.sessionEmotion(sessionId));
        } catch (Exception e) {
            log.warn("读取会话情绪缓存失败, sessionId={}. 原因: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 写入会话情绪分析缓存，与消息缓存同周期
     */
    public void putEmotion(Long sessionId, Object emotion) {
        if (!cacheProperties.isEnabled() || emotion == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    CacheKeys.sessionEmotion(sessionId),
                    emotion,
                    cacheProperties.getMessageTtlHours(),
                    TimeUnit.HOURS
            );
        } catch (Exception e) {
            log.warn("写入会话情绪缓存失败, sessionId={}. 原因: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 探测 Redis 是否可用，用于健康检查接口
     */
    public boolean isAvailable() {
        if (!cacheProperties.isEnabled()) {
            return false;
        }
        try {
            redisTemplate.hasKey(CacheKeys.chatMessages(-1L));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
