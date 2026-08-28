package org.example.aispingboot.controller;

import cn.hutool.json.JSONUtil;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import org.example.aispingboot.AiService.EmotionAnalysisService;
import org.example.aispingboot.AiService.PsychologicalSupportService;
import org.example.aispingboot.AiService.StructOutPut;
import org.example.aispingboot.DTO.command.ConsultationSessionCreateDTO;
import org.example.aispingboot.DTO.command.ConsultationStreamDTO;
import org.example.aispingboot.DTO.response.ConsultationMessageResponseDTO;
import org.example.aispingboot.DTO.response.ConsultationSessionResponseDTO;
import org.example.aispingboot.common.Result;
import org.example.aispingboot.common.ResultCode;
import org.example.aispingboot.entity.ConsultationSession;
import org.example.aispingboot.exception.BusinessException;
import org.example.aispingboot.service.ConsultationMessageService;
import org.example.aispingboot.service.ConsultationSessionService;
import org.example.aispingboot.util.JwtTokenUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.result.view.Fragment;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/psychological-chat")
public class PsychologicalChat {
    @Autowired
    private PsychologicalSupportService psychologicalSupportService;

    @Autowired
    private ConsultationSessionService consultationSessionService;

    @Autowired
    private ConsultationMessageService consultationMessageService;

    @Autowired
    private EmotionAnalysisService emotionAnalysisService;

    /**
     * 从当前请求的 token 中解析用户ID
     */
    private Long currentUserId() {
        String token = JwtTokenUtil.getCurrentToken();
        DecodedJWT jwt = JwtTokenUtil.verifyToken(token);
        return jwt.getClaim("userId").asLong();
    }

    @PostMapping("/session/start")
    public Result<StructOutPut.StreamChatSession> startSession(@Valid @RequestBody ConsultationSessionCreateDTO createDTO) {
        // 获取当前用户
        Long userId = currentUserId();
        StructOutPut.StreamChatSession session = psychologicalSupportService.startSession(userId, createDTO);
        return Result.ok(session);
    }

    /**
     * 分页查询当前用户的会话列表（对应前端左侧「历史会话」）
     */
    @GetMapping("/sessions")
    public Result<Page<ConsultationSessionResponseDTO>> listSessions(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        Long userId = currentUserId();
        return Result.ok(consultationSessionService.pageSessions(userId, pageNum, pageSize));
    }

    /**
     * 查询某个会话的历史消息
     * <p>
     * 这个接口是「刷新后仍能看见对话」的关键：前端刷新页面时会重新调用它，
     * 服务端优先从 Redis 读取，因此不必回查数据库就能返回完整对话。
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ConsultationMessageResponseDTO>> listSessionMessages(@PathVariable Long sessionId) {
        Long userId = currentUserId();
        // 校验会话归属，防止越权查看他人对话
        ConsultationSession session = consultationSessionService.getSessionById(sessionId, userId);
        if (session == null) {
            throw new BusinessException("会话不存在或无权访问");
        }
        return Result.ok(consultationMessageService.listMessagesBySessionId(sessionId));
    }

    /**
     * 删除会话及其全部消息
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Boolean> deleteSession(@PathVariable Long sessionId) {
        Long userId = currentUserId();
        boolean deleted = consultationSessionService.deleteSession(sessionId, userId);
        if (!deleted) {
            throw new BusinessException("会话不存在或无权删除");
        }
        return Result.ok(true);
    }

    /**
     * 获取会话的情绪分析结果（结果缓存在 Redis，刷新后可复用，省一次大模型调用）
     *
     * @param refresh 传 true 时强制重新分析，默认走缓存
     */
    @GetMapping("/session/{sessionId}/emotion")
    public Result<Map<String, Object>> getSessionEmotion(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "false") boolean refresh) {
        Long userId = currentUserId();
        Long dbSessionId = extractSessionId(sessionId);
        if (dbSessionId == null) {
            throw new BusinessException("会话ID格式错误");
        }
        ConsultationSession session = consultationSessionService.getSessionById(dbSessionId, userId);
        if (session == null) {
            throw new BusinessException("会话不存在或无权访问");
        }
        return Result.ok(emotionAnalysisService.analyzeSessionEmotion(dbSessionId, refresh));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@Valid @RequestBody ConsultationStreamDTO streamDTO) {
        // 获取当前用户
        Long userId = currentUserId();

        if (userId == null) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data(JSONUtil.toJsonStr(Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), "用户未登录")))
                    .build());
        }

        // 开始流式对话（传入 userId，供工具调用查询"当前用户"自己的数据）
        return psychologicalSupportService.streamPsychologicalChat(userId, streamDTO.getSessionId(), streamDTO.getUserMessage())
                .map(Fragment -> {
                    return ServerSentEvent.<String>builder()
                            .event("message")
                            .data(JSONUtil.toJsonStr(Result.ok(Map.of("content", Fragment, "type", "normal"))))
                            .build();
                })
                .concatWith(Flux.just(ServerSentEvent.<String>builder()
                        .event("done")
                        .data("{}")
                        .build()
                ))
                .delayElements(Duration.ofMillis(50)); // 添加延迟确保流式数据的体验
    }

    /**
     * 从 "session_12" 这类字符串中解析出数据库自增ID
     */
    public Long extractSessionId(String sessionId) {
        if (sessionId != null && sessionId.startsWith("session_")) {
            try {
                return Long.parseLong(sessionId.substring("session_".length()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
