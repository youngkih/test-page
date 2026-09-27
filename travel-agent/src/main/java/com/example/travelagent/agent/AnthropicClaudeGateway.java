package com.example.travelagent.agent;

import java.util.function.Consumer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;

@Component
public class AnthropicClaudeGateway implements ClaudeGateway {

    private final ObjectProvider<AnthropicClient> clientProvider;

    public AnthropicClaudeGateway(ObjectProvider<AnthropicClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    @Override
    public Message stream(MessageCreateParams params, Consumer<RawMessageStreamEvent> onEvent) {
        // MessageAccumulator: 스트림 이벤트 조각들을 모아서 최종 Message 객체로 조립해 주는 SDK 헬퍼
        MessageAccumulator accumulator = MessageAccumulator.create();
        try (StreamResponse<RawMessageStreamEvent> response =
                clientProvider.getObject().messages().createStreaming(params)) {
            response.stream().forEach(event -> {
                accumulator.accumulate(event);
                onEvent.accept(event);
            });
        }
        return accumulator.message();
    }
}
