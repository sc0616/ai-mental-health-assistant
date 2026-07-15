package org.example.aispingboot.config;

import cn.hutool.core.text.AntPathMatcher;
import org.example.aispingboot.service.TokenBlacklistService;
import org.example.aispingboot.service.UserService;
import org.example.aispingboot.util.JwtAuthticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    private static final AntPathMatcher antPathMatcher = new AntPathMatcher();
    private  static final String[] PUBLIC_PATHS = {
            "/",
            "/api/test",
            "/api/user/login",
            "/api/user/add"
    };

    public static Boolean isPublicPATH(String requestUri) {
        for (String publicPath : PUBLIC_PATHS) {
            if (antPathMatcher.match(publicPath, requestUri)) {
                return true;
            }
        }
        return false;
    }

    @Bean
    public JwtAuthticationFilter jwtAuthticationFilter(UserService userService,
                                                       TokenBlacklistService tokenBlacklistService) {
        // 过滤器需要依赖用户服务与 token 黑名单服务，这里显式注入
        return new JwtAuthticationFilter(userService, tokenBlacklistService);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthticationFilter jwtAuthticationFilter) throws Exception {
        http
                // 禁用CSRF保护 （API服务通常不需要）
                .csrf(AbstractHttpConfigurer::disable)
                // 配置会话管理为无状态（JWT需要）
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // SSE 流式接口（/api/psychological-chat/stream）会走 Servlet 异步分发。
                // JwtAuthticationFilter 继承自 OncePerRequestFilter，默认跳过异步分发，
                // 导致异步回填时 SecurityContext 为空，而 AuthorizationFilter 仍会执行，
                // 于是抛出 AuthorizationDeniedException（响应已提交，最终表现为流中断/为空）。
                // 下面这行让安全上下文跟随异步线程传递，保证异步分发时依然是已认证状态。
                .securityContext(context -> context
                        .requireExplicitSave(false)
                        .securityContextRepository(new RequestAttributeSecurityContextRepository()))
                // 配置请求的授权规则
                .authorizeHttpRequests(auth -> auth
                        // 公开的路径，无需登录即可访问
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // 其他请求都需要认证
                        .anyRequest().authenticated()
                )
                // 添加JWT认证过滤器
                .addFilterBefore(jwtAuthticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
