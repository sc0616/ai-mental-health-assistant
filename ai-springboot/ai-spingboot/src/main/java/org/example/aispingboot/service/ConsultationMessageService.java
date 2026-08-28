package org.example.aispingboot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.aispingboot.DTO.response.ConsultationMessageResponseDTO;
import org.example.aispingboot.entity.ConsultationMessage;
import org.example.aispingboot.entity.ConsultationSession;
import org.example.aispingboot.mapper.ConsultationMessageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ConsultationMessageService {
    @Autowired
    private ConsultationMessageMapper consultationMessageMapper;

    @Autowired
    private ChatCacheService chatCacheService;

    public ConsultationMessage saveUserMessage(Long sessionId, String content, String emotion_tag) {
        // 构建用户消息实体
         ConsultationMessage userMessage = ConsultationMessage.builder()
            .sessionId(sessionId)
            .senderType(1)
            .messageType(1)
            .content(content)
            .emotionTag(emotion_tag)
            .createdAt(LocalDateTime.now())
            .build();

        consultationMessageMapper.insert(userMessage);
        // 把新消息同步进 Redis，这样用户刷新页面也能看到刚发的这句
        chatCacheService.appendMessage(sessionId, convertToResponseDTO(userMessage));
        return userMessage;
    }

    public ConsultationMessage saveAimessage(Long sessionId, String content, String aiModel) {
        ConsultationMessage message = ConsultationMessage.builder()
                .sessionId(sessionId)
                .senderType(2)
                .messageType(1)
                .content(content)
                .aiModel(aiModel)
                .createdAt(LocalDateTime.now())
                .build();
        // 插入数据库
        consultationMessageMapper.insert(message);
        // AI 回复落库后同样同步缓存，这是「刷新后仍能看见 AI 回复」的关键一步
        chatCacheService.appendMessage(sessionId, convertToResponseDTO(message));
        return message;
    }

    /**
     * 查询某个会话的全部消息（按时间正序）
     * <p>
     * 这是「刷新后仍能看见历史对话」的核心查询，采用 Cache-Aside 模式：
     * 先查 Redis，命中直接返回；未命中查 MySQL 并回填 Redis。
     */
    public List<ConsultationMessageResponseDTO> listMessagesBySessionId(Long sessionId) {
        // 1. 先查缓存
        List<ConsultationMessageResponseDTO> cached = chatCacheService.getMessages(sessionId);
        if (cached != null) {
            return cached;
        }

        // 2. 缓存未命中，回源数据库
        LambdaQueryWrapper<ConsultationMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConsultationMessage::getSessionId, sessionId)
                .orderByAsc(ConsultationMessage::getCreatedAt)
                .orderByAsc(ConsultationMessage::getId);
        List<ConsultationMessage> messages = consultationMessageMapper.selectList(queryWrapper);

        List<ConsultationMessageResponseDTO> result = messages.stream()
                .map(this::convertToResponseDTO)
                .toList();

        // 3. 回填缓存，供后续刷新直接命中，避免每次都打数据库
        chatCacheService.putMessages(sessionId, result);
        return result;
    }

    /**
     * 删除某个会话下的全部消息（删除会话时一并清理）
     */
    public void deleteBySessionId(Long sessionId) {
        LambdaQueryWrapper<ConsultationMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConsultationMessage::getSessionId, sessionId);
        consultationMessageMapper.delete(queryWrapper);
        chatCacheService.evictMessages(sessionId);
    }

    /**
     * 统计某用户所有会话的消息总数（用于会话列表展示）
     */
    public Integer countBySessionIds(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        LambdaQueryWrapper<ConsultationMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(ConsultationMessage::getSessionId, sessionIds);
        Long count = consultationMessageMapper.selectCount(queryWrapper);
        return count.intValue();
    }

    /**
     * 查询某会话最后一条消息内容（用于会话列表预览）
     */
    public String getLastContentBySessionId(Long sessionId) {
        ConsultationMessageResponseDTO last = getLastMessageBySessionId(sessionId);
        return last != null ? last.getContent() : null;
    }

    public Integer getMessageCountBySessionId(Long sessionId) {
        LambdaQueryWrapper<ConsultationMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConsultationMessage::getSessionId, sessionId);

       Long count = consultationMessageMapper.selectCount(queryWrapper);
       return count.intValue();
    }

    // 获取会话的最后一条消息
    public ConsultationMessageResponseDTO getLastMessageBySessionId(Long sessionId) {
        LambdaQueryWrapper<ConsultationMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConsultationMessage::getSessionId, sessionId)
                .orderByDesc(ConsultationMessage::getCreatedAt)
                .last("limit 1");

        ConsultationMessage lastMessage = consultationMessageMapper.selectOne(queryWrapper);
        return lastMessage != null ? convertToResponseDTO(lastMessage) : null;

    }

    private ConsultationMessageResponseDTO convertToResponseDTO(ConsultationMessage message) {
        if (message == null) {
            return null;
        }

        // 手动逐字段赋值，确保转换的准确性和可控性
        ConsultationMessageResponseDTO responseDTO = new ConsultationMessageResponseDTO();
        responseDTO.setId(message.getId());
        responseDTO.setSessionId(message.getSessionId());
        responseDTO.setSenderType(message.getSenderType());
        responseDTO.setMessageType(message.getMessageType());
        responseDTO.setContent(message.getContent());
        responseDTO.setEmotionTag(message.getEmotionTag());
        responseDTO.setAiModel(message.getAiModel());
        responseDTO.setCreatedAt(message.getCreatedAt());

        // 设置描述字段（通过实体方法获取）
        responseDTO.setSenderTypeDesc(message.getSenderTypeDesc());
        responseDTO.setMessageTypeDesc(message.getMessageTypeDesc());

        // 计算消息长度
        responseDTO.calculateContentLength();

        return responseDTO;
    }
}
