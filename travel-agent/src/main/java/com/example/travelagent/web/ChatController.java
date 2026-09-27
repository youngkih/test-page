package com.example.travelagent.web;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.travelagent.agent.Conversation;
import com.example.travelagent.agent.ConversationStore;
import com.example.travelagent.agent.TravelAgentService;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/sessions")
public class ChatController {

    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(20);

    private final ConversationStore store;
    private final TravelAgentService agent;
    // 한 턴이 몇 분씩 걸릴 수 있으므로(웹 검색 여러 번) 요청 스레드를 붙잡지 않고 가상 스레드에서 실행
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(ConversationStore store, TravelAgentService agent) {
        this.store = store;
        this.agent = agent;
    }

    public record SessionResponse(String sessionId) {
    }

    public record ChatRequest(@NotBlank @Size(max = 8000) String message) {
    }

    public record SessionState(String sessionId, List<Conversation.ChatLine> transcript, Object itinerary,
            Object comparison) {
    }

    @PostMapping
    public SessionResponse create() {
        return new SessionResponse(store.create().id());
    }

    @GetMapping("/{sessionId}")
    public SessionState get(@PathVariable String sessionId) {
        Conversation conversation = find(sessionId);
        return new SessionState(conversation.id(), conversation.transcript(), conversation.itinerary(),
                conversation.comparison());
    }

    /** 메시지를 보내고, 답변을 SSE 스트림(text/event-stream)으로 받는다. */
    @PostMapping(path = "/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable String sessionId, @Valid @RequestBody ChatRequest request) {
        Conversation conversation = find(sessionId);
        SseEmitter emitter = new SseEmitter(TURN_TIMEOUT.toMillis());
        SseAgentEventListener listener = new SseAgentEventListener(emitter);
        executor.submit(() -> agent.chat(conversation, request.message().strip(), listener));
        return emitter;
    }

    private Conversation find(String sessionId) {
        return store.find(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "대화를 찾을 수 없습니다. 새 대화를 시작해 주세요."));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 에러 응답 본문을 단순한 JSON 으로. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", String.valueOf(e.getReason())));
    }
}
