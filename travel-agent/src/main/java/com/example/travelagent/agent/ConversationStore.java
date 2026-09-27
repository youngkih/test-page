package com.example.travelagent.agent;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

/**
 * 대화 저장소 (메모리). 서버를 재시작하면 사라진다.
 * 오래 보관하려면 이 클래스를 JPA/Redis 구현으로 바꾸면 된다.
 */
@Component
public class ConversationStore {

    private final ConcurrentMap<String, Conversation> conversations = new ConcurrentHashMap<>();

    public Conversation create() {
        String id = UUID.randomUUID().toString();
        Conversation conversation = new Conversation(id);
        conversations.put(id, conversation);
        return conversation;
    }

    public Optional<Conversation> find(String id) {
        return Optional.ofNullable(conversations.get(id));
    }
}
