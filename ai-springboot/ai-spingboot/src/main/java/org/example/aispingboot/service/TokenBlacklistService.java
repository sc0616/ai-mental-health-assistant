package org.example.aispingboot.service;

import com.auth0.jwt.interfaces.DecodedJWT;
import lombok.extern.slf4j.Slf4j;
import org.example.aispingboot.config.ChatCacheProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Token 黑名单服务（基于 Redis）
 * <p>
 * 解决的问题：JWT 是无状态的，签发后在过期时间内始终有效。
 * 用户点「退出登录」后，如果只是前端删掉 localStorage 里的 token，
 * 这个 token 其实**依然能用**（被人抓包拿到就能继续访问），存在安全风险。
 * <p>
 * 方案：退出登录时把该 token 加入 Redis 黑名单，并设置 TTL = token 剩余有效期。
 * 之后每个请求都会校验黑名单，命中则拒绝。TTL 到期后自动清理，不会无限占用内存。
 */
@Slf4j
@Service
public class TokenBlacklistService {

    private static final String PREFIX = "mental:token:blacklist:";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ChatCacheProperties cacheProperties;

    /**
     * 将 token 加入黑名单
     *
     * @param token 待失效的 token
     */
    public void blacklist(String token) {
        if (!cacheProperties.isEnabled() || token == null || token.isBlank()) {
            return;
        }
        try {
            long ttlMillis = remainingValidity(token);
            if (ttlMillis <= 0) {
                // token 本身已过期，无需再拉黑
                return;
            }
            stringRedisTemplate.opsForValue().set(
                    PREFIX + token,
                    "1",
                    ttlMillis,
                    TimeUnit.MILLISECONDS
            );
            log.info("Token 已加入黑名单, 剩余有效期 {} 毫秒", ttlMillis);
        } catch (Exception e) {
            // Redis 不可用时不能阻断退出登录流程，前端仍会清除本地 token
            log.warn("写入 token 黑名单失败: {}", e.getMessage());
        }
    }

    /**
     * 判断 token 是否已被拉黑
     *
     * @return true 表示该 token 已失效
     */
    public boolean isBlacklisted(String token) {
        if (!cacheProperties.isEnabled() || token == null || token.isBlank()) {
            // 缓存关闭时退化为「不校验黑名单」，保持原有行为
            return false;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(PREFIX + token));
        } catch (Exception e) {
            // Redis 故障时放行，避免整个系统登录态全部失效（可用性优先）
            log.warn("校验 token 黑名单失败, 本次放行: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 计算 token 距离过期还有多久
     */
    private long remainingValidity(String token) {
        try {
            DecodedJWT jwt = com.auth0.jwt.JWT.decode(token);
            Date expiresAt = jwt.getExpiresAt();
            if (expiresAt == null) {
                // 没有过期时间的 token，给一个保守的 24 小时
                return TimeUnit.HOURS.toMillis(24);
            }
            return expiresAt.getTime() - System.currentTimeMillis();
        } catch (Exception e) {
            // 解析失败时给个保守值，至少保证短期内被拦住
            return TimeUnit.HOURS.toMillis(24);
        }
    }
}
