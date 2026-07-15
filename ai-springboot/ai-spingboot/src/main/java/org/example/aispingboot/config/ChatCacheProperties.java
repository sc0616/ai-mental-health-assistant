package org.example.aispingboot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 咨询对话缓存配置
 * 对应 application.yml 中的 chat.cache.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "chat.cache")
public class ChatCacheProperties {
    /**
     * 缓存总开关，关闭后所有读写直接走 MySQL（便于排查问题）
     */
    private boolean enabled = true;

    /**
     * 会话消息缓存有效期（小时），默认 7 天
     */
    private long messageTtlHours = 168;

    /**
     * 会话列表缓存有效期（分钟），默认 30 分钟
     */
    private long sessionListTtlMinutes = 30;
}
