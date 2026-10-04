package com.vibemusic.controller.agent;

import com.vibemusic.agent.MusicAgentTools;
import com.vibemusic.agent.AgentStreamParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibemusic.model.dto.AgentChatDTO;
import com.vibemusic.util.ThreadLocalUtil;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/agent")
public class AgentController {
    interface Assistant {
        @SystemMessage("你是 Vibe Music 音乐资料助手。推荐时先用工具查询本站曲库。先用自然语言介绍最多 5 首歌曲。本站仅展示资料，不提供播放或下载。正文中不要写 URL、文件路径、歌曲 ID 或 JSON。推荐回复的最后单独一行必须输出 <vibe-songs>[歌曲ID数字数组]</vibe-songs>，例如 <vibe-songs>[12,34]</vibe-songs>。数组只包含本次工具查到的真实 songId，最多 5 个。除此之外不要输出内部字段。闲聊时不输出该标签。")
        TokenStream chat(@MemoryId String memoryId, @UserMessage String message);
    }

    private static final int MAX_SESSIONS = 100;
    private static final long SESSION_TTL_MS = Duration.ofMinutes(30).toMillis();
    private record Session(Assistant assistant, long usedAt) {}
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Semaphore concurrency = new Semaphore(2);
    private final StreamingChatModel chatModel;
    private final MusicAgentTools tools;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AgentController(MusicAgentTools tools, StringRedisTemplate redis, ObjectMapper objectMapper,
                           @Value("${vibe-music.ai.dashscope.api-key}") String apiKey,
                           @Value("${vibe-music.ai.dashscope.model:qwen3.8-flash}") String model) {
        this.tools = tools;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.chatModel = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl("https://maas.qianwenaiapi.com/compatible-mode/v1")
                .modelName(model)
                .timeout(Duration.ofSeconds(45))
                .maxTokens(700)
                .build();
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody AgentChatDTO dto) {
        Map<String, Object> claims = ThreadLocalUtil.get();
        Object userId = claims == null ? null : claims.get("userId");
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        long now = System.currentTimeMillis();
        String key = userId + ":" + dto.getConversationId();
        sessions.entrySet().removeIf(e -> now - e.getValue().usedAt() > SESSION_TTL_MS);
        if (!sessions.containsKey(key) && sessions.size() >= MAX_SESSIONS)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "会话数已达上限");
        Boolean allowed = redis.opsForValue().setIfAbsent("agent:interval:" + userId, "1", Duration.ofSeconds(12));
        if (!Boolean.TRUE.equals(allowed))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请稍后再提问");
        if (!concurrency.tryAcquire())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "助手正忙，请稍后再试");
        String dailyKey = "agent:daily:" + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")) + ":" + userId;
        try {
            Long dailyCalls = redis.opsForValue().increment(dailyKey);
            if (dailyCalls != null && dailyCalls == 1) redis.expire(dailyKey, Duration.ofDays(2));
            if (dailyCalls != null && dailyCalls > 30) {
                concurrency.release();
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "今日助手使用次数已达上限");
            }
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            concurrency.release();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "限流服务暂不可用");
        }
        Session session = sessions.compute(key, (id, existing) -> new Session(
                existing != null ? existing.assistant() : AiServices.builder(Assistant.class)
                        .streamingChatModel(chatModel)
                        .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(12))
                        .tools(tools)
                        .build(), now));
        SseEmitter emitter = new SseEmitter(60_000L);
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable release = () -> { if (finished.compareAndSet(false, true)) concurrency.release(); };
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(error -> release.run());
        try {
            AgentStreamParser parser = new AgentStreamParser(objectMapper, visible -> {
                try { emitter.send(SseEmitter.event().name("text").data(visible)); }
                catch (Exception error) { emitter.completeWithError(error); release.run(); }
            });
            session.assistant().chat(key, dto.getQuery())
                    .onPartialResponse(parser::accept)
                    .onCompleteResponse(response -> {
                        try {
                            var cards = tools.cardsForIds(parser.finish());
                            if (!cards.isEmpty()) emitter.send(SseEmitter.event().name("songs").data(objectMapper.writeValueAsString(cards)));
                            emitter.complete();
                        } catch (Exception error) { emitter.completeWithError(error); }
                        finally { release.run(); }
                    })
                    .onError(error -> { emitter.completeWithError(error); release.run(); })
                    .start();
        } catch (Exception error) {
            release.run();
            throw error;
        }
        return emitter;
    }
}
