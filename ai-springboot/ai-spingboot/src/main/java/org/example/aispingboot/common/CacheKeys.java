package org.example.aispingboot.common;

/**
 * Redis 缓存 key 统一管理，避免各处以字符串硬编码导致 key 冲突或拼写错误。
 * <p>
 * 命名规范：{@code mental: 业务: 标识}
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    /** 全局前缀 */
    private static final String PREFIX = "mental:";

    /**
     * 某个会话的消息列表（对话主体内容）
     * 例：mental:chat:messages:12
     */
    public static String chatMessages(Long sessionId) {
        return PREFIX + "chat:messages:" + sessionId;
    }

    /**
     * 某个用户的会话列表（左侧历史会话）
     * 例：mental:chat:sessions:3
     */
    public static String userSessions(Long userId) {
        return PREFIX + "chat:sessions:" + userId;
    }

    /**
     * 某个会话的最新情绪分析结果
     * 例：mental:chat:emotion:12
     */
    public static String sessionEmotion(Long sessionId) {
        return PREFIX + "chat:emotion:" + sessionId;
    }
}
