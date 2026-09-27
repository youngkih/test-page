package com.example.travelagent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import com.anthropic.models.messages.MessageParam;

/**
 * 한 사용자와의 대화 상태.
 *
 * <p>Claude API 는 상태가 없어서(stateless) 매 요청마다 전체 대화 기록을 보내야 한다.
 * {@link #messages} 는 API 로 보낼 원본 기록(도구 호출·검색 결과·생각 블록 포함)이고,
 * {@link #transcript} 는 화면 새로고침 시 다시 보여줄 사람용 기록이다.
 *
 * <p>주의: 기록은 항상 뒤에 추가만 한다(append-only). 중간을 고치면 프롬프트 캐시가 깨지고
 * 최신 모델에서는 생각(thinking) 블록 검증에 실패할 수 있다.
 */
public class Conversation {

    public record ChatLine(String role, String text) {
    }

    private final String id;
    private final List<MessageParam> messages = new ArrayList<>();
    private final List<ChatLine> transcript = new ArrayList<>();
    private final ReentrantLock turnLock = new ReentrantLock();
    private volatile Object itinerary;
    private volatile Object comparison;

    public Conversation(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** 같은 대화에 두 요청이 동시에 들어오면 기록이 꼬이므로 한 번에 한 턴만 처리한다. */
    public ReentrantLock turnLock() {
        return turnLock;
    }

    public synchronized List<MessageParam> messages() {
        return List.copyOf(messages);
    }

    public synchronized void append(MessageParam message) {
        messages.add(message);
    }

    public synchronized List<ChatLine> transcript() {
        return List.copyOf(transcript);
    }

    public synchronized void addTranscript(String role, String text) {
        transcript.add(new ChatLine(role, text));
    }

    public Object itinerary() {
        return itinerary;
    }

    public Object comparison() {
        return comparison;
    }

    public void saveUiData(String type, Object data) {
        switch (type) {
            case "itinerary" -> itinerary = data;
            case "comparison" -> comparison = data;
            default -> {
            }
        }
    }
}
