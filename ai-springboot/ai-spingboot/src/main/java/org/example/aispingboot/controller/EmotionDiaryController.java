package org.example.aispingboot.controller;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.example.aispingboot.DTO.command.EmotionDiaryCreateDTO;
import org.example.aispingboot.DTO.response.EmotionDiaryResponseDTO;
import org.example.aispingboot.common.Result;
import org.example.aispingboot.exception.BusinessException;
import org.example.aispingboot.service.EmotionDiaryService;
import org.example.aispingboot.util.JwtTokenUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 情绪日记接口
 * <p>
 * 补齐前端此前调用但后端不存在的三个接口：
 * <ul>
 *     <li>POST   /api/emotion-diary                  前台提交日记（emotionDiary.vue）</li>
 *     <li>GET    /api/emotion-diary/admin/page       后台分页列表（emotional.vue）</li>
 *     <li>DELETE /api/emotion-diary/admin/{id}       后台删除（emotional.vue）</li>
 * </ul>
 * 这些路径都不在 SecurityConfig 的公开白名单里，必须携带有效 token 才能访问。
 */
@Slf4j
@RestController
@RequestMapping("/api/emotion-diary")
public class EmotionDiaryController {

    @Autowired
    private EmotionDiaryService emotionDiaryService;

    /**
     * 从当前请求的 token 中解析用户ID
     * <p>
     * 与 PsychologicalChat 中的做法保持一致：只信任 token 里的 userId，
     * 不接受前端传用户ID，避免越权写入他人数据。
     */
    private Long currentUserId() {
        String token = JwtTokenUtil.getCurrentToken();
        DecodedJWT jwt = JwtTokenUtil.verifyToken(token);
        return jwt.getClaim("userId").asLong();
    }

    /**
     * 提交情绪日记
     * <p>
     * 同一用户同一天重复提交会覆盖上一条，不会产生重复记录。
     * AI 分析失败不影响保存。
     */
    @PostMapping
    public Result<EmotionDiaryResponseDTO> submit(@Valid @RequestBody EmotionDiaryCreateDTO dto) {
        Long userId = currentUserId();
        return Result.ok(emotionDiaryService.submit(userId, dto));
    }

    /**
     * 后台分页查询情绪日记
     * <p>
     * 兼容前端字段名：前端传的是 moodScreRange（Score 拼写有误），
     * 这里同时接受 moodScoreRange，避免前端改动才能生效。
     */
    @GetMapping("/admin/page")
    public Result<Page<EmotionDiaryResponseDTO>> adminPage(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String moodScreRange,
            @RequestParam(required = false) String moodScoreRange) {
        // 两个参数名都支持，优先取拼写正确的那一个
        String range = moodScoreRange != null ? moodScoreRange : moodScreRange;
        return Result.ok(emotionDiaryService.page(current, size, userId, range));
    }

    /**
     * 后台删除一条情绪日记
     */
    @DeleteMapping("/admin/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        if (!emotionDiaryService.delete(id)) {
            throw new BusinessException("记录不存在");
        }
        return Result.ok(true);
    }
}