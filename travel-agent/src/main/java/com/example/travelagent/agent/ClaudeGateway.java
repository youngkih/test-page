package com.example.travelagent.agent;

import java.util.function.Consumer;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;

/**
 * Claude API 호출을 감싼 인터페이스.
 * 서비스 로직을 테스트할 때 실제 API 대신 가짜 구현을 넣을 수 있도록 분리했다.
 */
public interface ClaudeGateway {

    /**
     * 스트리밍으로 호출하고, 이벤트가 올 때마다 onEvent 를 부른 뒤, 완성된 Message 를 돌려준다.
     */
    Message stream(MessageCreateParams params, Consumer<RawMessageStreamEvent> onEvent);
}
