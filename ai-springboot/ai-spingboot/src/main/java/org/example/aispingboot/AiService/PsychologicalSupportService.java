package org.example.aispingboot.AiService;

import lombok.extern.slf4j.Slf4j;
import org.example.aispingboot.DTO.command.ConsultationSessionCreateDTO;
import org.example.aispingboot.DTO.response.ConsultationMessageResponseDTO;
import org.example.aispingboot.entity.ConsultationSession;
import org.example.aispingboot.config.RagProperties;
import org.example.aispingboot.rag.KnowledgeRetriever;
import org.example.aispingboot.rag.RetrievedChunk;
import org.example.aispingboot.service.ConsultationMessageService;
import org.example.aispingboot.service.ConsultationSessionService;
import org.example.aispingboot.service.ChatCacheService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class PsychologicalSupportService {
    @Autowired
    @Qualifier("open-ai")
    private ChatClient chatClient;

    @Autowired
    private ConsultationSessionService consultationSessionService;

    @Autowired
    private ConsultationMessageService consultationMessageService;

    @Autowired
    private ChatCacheService chatCacheService;

    /** RAG：对话前检索知识库 */
    @Autowired
    private KnowledgeRetriever knowledgeRetriever;

    /** Tool Calling：供模型按需调用的工具集 */
    @Autowired
    private PsychologicalTools psychologicalTools;

    /** Tool Calling 开关（rag.tool-calling-enabled） */
    @Autowired
    private RagProperties ragProperties;

    public StructOutPut.StreamChatSession startSession(Long userId, ConsultationSessionCreateDTO createDTO) {
        // 创建数据库会话记录
        ConsultationSession consultationSession = consultationSessionService.createSession(userId, createDTO);

        // 将初始用户消息保存到Message表
        consultationMessageService.saveUserMessage(consultationSession.getId(), createDTO.getInitialMessage(), null);

        // 创建会话信息
        String sessionId = "session_" + consultationSession.getId();
        return new StructOutPut.StreamChatSession(
                sessionId,
                userId,
                createDTO.getInitialMessage(),
                System.currentTimeMillis(),
                System.currentTimeMillis() + 86400000L, // 24小时
                1,
                "ACTIVE"
        );
    }

    /**
     * 流式心理对话
     * <p>
     * userId 由 Controller 从 JWT 中解析后传入，是工具调用查询用户数据的唯一依据。
     * 这里不把它交给模型，模型也就无法越权访问他人数据。
     */
    public Flux<String> streamPsychologicalChat(Long userId, String sessionId, String userMessage) {
        // 创建响应流
        return Flux.create(sink -> {
            // sink.next("数据1") // 发布数据
            // sink.complete(); // 完成流
            // sink.error(exception); // 发布错误
            Long dbSessionId = extractSessionId(sessionId);
            if (dbSessionId == null) {
                sink.error(new RuntimeException("会话ID格式错误"));
                return;
            }
            // 是否为初始消息
            boolean isInitialMessage = false;
            // 检查是否为初始消息，避免重复保存
            Integer messageCount = consultationMessageService.getMessageCountBySessionId(dbSessionId);
            if (messageCount == 1) {
               ConsultationMessageResponseDTO lastMessage = consultationMessageService.getLastMessageBySessionId(dbSessionId);
                if (lastMessage != null && lastMessage.getSenderType() == 1 && userMessage.equals(lastMessage.getContent())) {
                    isInitialMessage = true;
                }
            }
            if (!isInitialMessage) {
                // 保存用户消息到数据库
                consultationMessageService.saveUserMessage(dbSessionId, userMessage, null);
            }

            // 进行流式对话
            // 生成对话记忆管理
            String conversationId = "conversation_" + sessionId;

            // ===== RAG：先检索知识库，把相关资料拼进系统提示词 =====
            // 检索失败或无命中时 context 为空串，此时行为与改造前完全一致，
            // 保证"知识库不可用"不会影响正常对话。
            String context = "";
            List<RetrievedChunk> hits = new ArrayList<>();
            try {
                hits = knowledgeRetriever.retrieve(userMessage);
                context = knowledgeRetriever.buildContext(hits);
            } catch (Exception e) {
                log.warn("知识库检索失败，本次对话降级为普通模式: {}", e.getMessage());
            }

            // 注意：这里不再手动把用户消息塞进 chatMemory。
            // MessageChatMemoryAdvisor 已经会自动保存本轮的用户消息与 AI 回复，
            // 手动再存一次会让模型收到两条一模一样的用户消息。
            // 实测后果很隐蔽：模型只会回一句"稍等，我来帮你看看"，
            // 而不会真正发起工具调用（去掉这行后工具立刻正常）。

            // 构建系统提示词：基础人设 + （命中时）知识库资料 + 工具说明
            StringBuilder systemPrompt = new StringBuilder(PromptManage.PSYCHOLOGICAL_SUPPORT_SYSTEM_PROMPT);
            if (!context.isBlank()) {
                systemPrompt.append(String.format(PromptManage.RAG_CONTEXT_TEMPLATE, context));
                log.info("会话 {} 命中 {} 个知识片段，已注入提示词（{} 字符）",
                        sessionId, hits.size(), context.length());
            }
            // 告诉模型"你还能主动查资料"，否则多数模型不会想到去调用工具
            boolean useTools = ragProperties.isToolCallingEnabled();
            if (useTools && ragProperties.isToolPromptEnabled()) {
                systemPrompt.append(PromptManage.TOOL_USAGE_PROMPT);
            }

            //用于存储AI完成的响应
            StringBuilder fullResponse = new StringBuilder();

            // ===== Tool Calling 准备 =====
            // 把当前用户绑定到线程，供工具方法读取。
            // 关键点：userId 不通过方法参数传给模型，模型无法伪造他人身份。
            psychologicalTools.bindUser(userId);

            // 一次性链式构建请求。
            // <b>两个关键点（都是实测得出的）</b>：
            // 1. tools / toolContext 必须放在 advisors <b>之前</b>。先 advisors 再 tools 时，
            //    模型只会回一句"我这就去查"却不真正发起工具调用。
            // 2. 用 stream().content() 而不是 stream().chatResponse()。后者在
            //    内部工具执行（模型先返回 tool_calls、执行工具、再发起第二轮）时，
            //    只能拿到第一轮的"过渡话术"，第二轮的正式回答会丢。
            var requestSpec = chatClient.prompt()
                    .system(systemPrompt.toString())
                    .user(userMessage);

            if (useTools) {
                requestSpec = requestSpec
                        .toolCallbacks(psychologicalTools.buildToolCallbacks())
                        // 把 userId 放进 ToolContext 随请求传递。
                        // 这一步是必须的：经过 MessageChatMemoryAdvisor 时，工具会在
                        // Reactor 的另一个线程上执行，只靠 ThreadLocal 取不到用户身份，
                        // 会导致"查不到情绪记录"。ToolContext 能跨线程带过去。
                        .toolContext(psychologicalTools.buildToolContext());
            }

            requestSpec
                    .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .stream()
                    .content()
                    .doOnNext(fragment -> {
                        fullResponse.append(fragment);
                        sink.next(fragment);
                    })
                    .doOnComplete(() -> {
                        try {
                            String completeRes = fullResponse.toString();
                            // 将AI返回的内容保存到数据库
                            // 注意：saveAimessage 内部会同步把这条消息追加进 Redis 缓存，
                            // 因此用户刷新页面时依然能看到完整的 AI 回复
                            if (!completeRes.isBlank()) {
                                consultationMessageService.saveAimessage(dbSessionId, completeRes, "openai");
                            }
                            // AI 回复同样由 MessageChatMemoryAdvisor 写入 chatMemory，
                            // 这里不再手动添加，避免与同上问题一样产生重复消息。
                        } finally {
                            // 无论保存是否成功，都要清理线程绑定
                            psychologicalTools.clear();
                        }
                        sink.complete();
                    })
                    .doOnError(error -> {
                        // 出错路径同样要清理，否则线程被复用时会带着上一个用户的身份
                        psychologicalTools.clear();
                        sink.error(error);
                    })
                    .subscribe(); // 订阅并启动流
        });
    }

    // 获取参数中的sessionId
    public Long extractSessionId(String sessionId) {
        if (sessionId != null && sessionId.startsWith("session_")) {
            String idStr = sessionId.substring("session_".length());
            return Long.parseLong(idStr);
        }
        return null;
    }
}
