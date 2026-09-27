package com.example.travelagent.web;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.travelagent.agent.AgentEventListener;

/**
 * 에이전트 이벤트를 브라우저로 SSE(Server-Sent Events) 전송한다.
 *
 * <p>사용자가 브라우저를 닫아도 에이전트는 끝까지 일하게 둔다 (전송만 멈춤).
 * 그래야 대화 기록과 일정표가 저장되어 새로고침 후에도 볼 수 있다.
 */
class SseAgentEventListener implements AgentEventListener {

    private static final Logger log = LoggerFactory.getLogger(SseAgentEventListener.class);

    private final SseEmitter emitter;
    private volatile boolean disconnected;

    SseAgentEventListener(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(() -> disconnected = true);
        emitter.onTimeout(() -> disconnected = true);
        emitter.onError(e -> disconnected = true);
    }

    @Override
    public void onText(String delta) {
        send("text", Map.of("text", delta));
    }

    @Override
    public void onThinking(String delta) {
        send("thinking", Map.of("text", delta));
    }

    @Override
    public void onStatus(String message) {
        send("status", Map.of("text", message));
    }

    @Override
    public void onUiEvent(String type, Object data) {
        send(type, data);
    }

    @Override
    public void onDone() {
        send("done", Map.of());
        complete();
    }

    @Override
    public void onError(String message) {
        send("error", Map.of("text", message));
        complete();
    }

    private void send(String event, Object data) {
        if (disconnected) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("client disconnected: {}", e.getMessage());
            disconnected = true;
        }
    }

    private void complete() {
        if (!disconnected) {
            emitter.complete();
        }
    }
}
