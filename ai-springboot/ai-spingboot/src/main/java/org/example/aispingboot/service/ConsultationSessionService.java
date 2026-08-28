package org.example.aispingboot.service;

import cn.hutool.core.date.DateUnit;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.aispingboot.DTO.command.ConsultationSessionCreateDTO;
import org.example.aispingboot.DTO.response.ConsultationSessionResponseDTO;
import org.example.aispingboot.entity.ConsultationSession;
import org.example.aispingboot.entity.User;
import org.example.aispingboot.mapper.ConsultationSessionMapper;
import org.example.aispingboot.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ConsultationSessionService {
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ConsultationSessionMapper consultationSessionMapper;

    @Autowired
    private ConsultationMessageService consultationMessageService;

    public ConsultationSession createSession(Long userId, ConsultationSessionCreateDTO createDTO) {
        // 验证用户是否存在
        User user =userMapper.selectById(userId);
        if (user != null) {
            // 创建会话记录
             ConsultationSession session = ConsultationSession.builder()
                    .userId(userId)
                    .sessionTitle(createDTO.getSessionTitle())
                    .startedAt(LocalDateTime.now())
                    .build();
            // 如果未提供标题
            if (StrUtil.isBlank(createDTO.getSessionTitle())) {
                session.setSessionTitle(String.format("宁渡AI助手 - " + DateUtil.format(LocalDateTime.now(), "MM-dd HH:mm")));
            }

            // 插入记录
            consultationSessionMapper.insert(session);
            return session;
        }

        return null;
    }

    /**
     * 分页查询某用户的会话列表（按开始时间倒序）
     * <p>
     * 对应前端左侧「历史会话」列表。每条记录额外带上消息条数与最后一条消息预览，
     * 这样前端不需要为每个会话再发一次请求。
     */
    public Page<ConsultationSessionResponseDTO> pageSessions(Long userId, long pageNum, long pageSize) {
        Page<ConsultationSession> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ConsultationSession> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConsultationSession::getUserId, userId)
                .orderByDesc(ConsultationSession::getStartedAt);

        Page<ConsultationSession> sessionPage = consultationSessionMapper.selectPage(page, queryWrapper);

        // 转换成带统计信息的 DTO 分页对象
        Page<ConsultationSessionResponseDTO> result = new Page<>(
                sessionPage.getCurrent(),
                sessionPage.getSize(),
                sessionPage.getTotal()
        );
        List<ConsultationSessionResponseDTO> records = sessionPage.getRecords().stream()
                .map(this::convertToResponseDTO)
                .toList();
        result.setRecords(records);
        return result;
    }

    /**
     * 查询单个会话，并校验归属，防止越权访问他人会话
     */
    public ConsultationSession getSessionById(Long sessionId, Long userId) {
        ConsultationSession session = consultationSessionMapper.selectById(sessionId);
        if (session == null) {
            return null;
        }
        // 只能访问自己的会话
        if (userId != null && !userId.equals(session.getUserId())) {
            return null;
        }
        return session;
    }

    /**
     * 删除会话及其全部消息（先删消息，再删会话，避免留下孤儿数据）
     */
    public boolean deleteSession(Long sessionId, Long userId) {
        ConsultationSession session = getSessionById(sessionId, userId);
        if (session == null) {
            return false;
        }
        consultationMessageService.deleteBySessionId(sessionId);
        consultationSessionMapper.deleteById(sessionId);
        return true;
    }

    /**
     * 转换为响应对象，附带消息统计
     */
    private ConsultationSessionResponseDTO convertToResponseDTO(ConsultationSession session) {
        ConsultationSessionResponseDTO dto = new ConsultationSessionResponseDTO();
        dto.setId(session.getId());
        dto.setUserId(session.getUserId());
        dto.setSessionTitle(session.getSessionTitle());
        dto.setStartedAt(session.getStartedAt());
        dto.setLastEmotionAnalysis(session.getLastEmotionAnalysis());
        dto.setLastEmotionUpdatedAt(session.getLastEmotionUpdatedAt());

        // 消息条数与最后一条消息预览（用于列表展示）
        try {
            dto.setMessageCount(consultationMessageService.getMessageCountBySessionId(session.getId()));
            dto.setLastMessage(consultationMessageService.getLastContentBySessionId(session.getId()));
        } catch (Exception e) {
            // 统计失败不影响列表返回
            dto.setMessageCount(0);
        }
        return dto;
    }
}
