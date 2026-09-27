package com.example.travelagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.example.travelagent.config.AgentProperties;
import com.example.travelagent.tool.SaveItineraryTool;
import com.example.travelagent.tool.TripCalendarTool;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 실제 Claude API 없이 에이전트 루프만 검증한다.
 * FakeGateway 가 미리 정해 둔 응답을 순서대로 돌려준다.
 */
class TravelAgentServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeGateway gateway = new FakeGateway();
    private final RecordingListener listener = new RecordingListener();
    private final AgentProperties properties = new AgentProperties("claude-opus-5", "high", 64000, 15, 20, 5,
            new AgentProperties.Family("서울", "ICN", "한국어",
                    List.of(new AgentProperties.Member("아들", "14개월", 1)), List.of("아이 최우선")));
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-27T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private final TravelAgentService service = new TravelAgentService(gateway, properties,
            new SystemPromptFactory(properties, clock), objectMapper,
            List.of(new TripCalendarTool(objectMapper), new SaveItineraryTool()));

    @Test
    void 도구를_실행하고_결과를_돌려준_뒤_답변을_마친다() {
        gateway.enqueue("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5",
                 "content":[
                   {"type":"text","text":"요일을 확인할게요.","citations":null},
                   {"type":"tool_use","id":"toolu_1","name":"trip_calendar",
                    "input":{"start_date":"2026-10-10","end_date":"2026-10-11","country":"JP"}}],
                 "stop_reason":"tool_use","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":5}}
                """);
        gateway.enqueue("""
                {"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5",
                 "content":[{"type":"text","text":"10월 10일은 토요일입니다.","citations":null}],
                 "stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":20,"output_tokens":5}}
                """);
        Conversation conversation = new Conversation("c1");

        service.chat(conversation, "10월 10일 무슨 요일이야?", listener);

        assertThat(listener.errors).isEmpty();
        assertThat(listener.done).isTrue();
        assertThat(gateway.requests).hasSize(2);

        // user → assistant(tool_use) → user(tool_result) → assistant(end_turn)
        List<MessageParam> history = conversation.messages();
        assertThat(history).extracting(MessageParam::role).containsExactly(
                MessageParam.Role.USER, MessageParam.Role.ASSISTANT, MessageParam.Role.USER,
                MessageParam.Role.ASSISTANT);
        var toolResult = history.get(2).content().asBlockParams().get(0).asToolResult();
        assertThat(toolResult.toolUseId()).isEqualTo("toolu_1");
        assertThat(toolResult.content().orElseThrow().asString()).contains("토요일");
        assertThat(toolResult.isError()).contains(false);

        assertThat(conversation.transcript()).extracting(Conversation.ChatLine::text).containsExactly(
                "10월 10일 무슨 요일이야?", "요일을 확인할게요.\n\n10월 10일은 토요일입니다.");
    }

    @Test
    void 두번째_요청에는_이전_기록과_시스템_프롬프트가_포함된다() {
        gateway.enqueue(endTurn("msg_1", "어디로 가고 싶으세요?"));
        gateway.enqueue(endTurn("msg_2", "좋아요!"));
        Conversation conversation = new Conversation("c1");

        service.chat(conversation, "여행 가고 싶어", listener);
        service.chat(conversation, "일본", listener);

        MessageCreateParams second = gateway.requests.get(1);
        assertThat(second.messages()).hasSize(3);
        assertThat(second.model().asString()).isEqualTo("claude-opus-5");
        String system = second.system().orElseThrow().asTextBlockParams().get(0).text();
        assertThat(system).contains("2026-09-27 (일요일)").contains("아들: 14개월").doesNotContain("{{");
        // 서버 도구 2개 + 우리 도구 2개
        assertThat(second.tools().orElseThrow()).hasSize(4);
    }

    @Test
    void pause_turn_이면_사용자_메시지_없이_이어서_호출한다() {
        gateway.enqueue("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5",
                 "content":[{"type":"text","text":"검색 중...","citations":null}],
                 "stop_reason":"pause_turn","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":5}}
                """);
        gateway.enqueue(endTurn("msg_2", "찾았어요."));
        Conversation conversation = new Conversation("c1");

        service.chat(conversation, "후쿠오카 키즈카페 찾아줘", listener);

        assertThat(gateway.requests).hasSize(2);
        assertThat(gateway.requests.get(1).messages()).extracting(MessageParam::role)
                .containsExactly(MessageParam.Role.USER, MessageParam.Role.ASSISTANT);
    }

    @Test
    void API_오류는_사용자용_메시지로_알린다() {
        Conversation conversation = new Conversation("c1");

        service.chat(conversation, "안녕", listener); // 큐가 비어 있으면 FakeGateway 가 예외를 던진다

        assertThat(listener.errors).singleElement().asString().contains("알 수 없는 오류");
        assertThat(conversation.turnLock().isLocked()).isFalse();
    }

    private static String endTurn(String id, String text) {
        return """
                {"id":"%s","type":"message","role":"assistant","model":"claude-opus-5",
                 "content":[{"type":"text","text":"%s","citations":null}],
                 "stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":10,"output_tokens":5}}
                """.formatted(id, text);
    }

    static class FakeGateway implements ClaudeGateway {
        final List<MessageCreateParams> requests = new ArrayList<>();
        private final Deque<String> responses = new ArrayDeque<>();

        void enqueue(String json) {
            responses.add(json);
        }

        @Override
        public Message stream(MessageCreateParams params, Consumer<RawMessageStreamEvent> onEvent) {
            requests.add(params);
            if (responses.isEmpty()) {
                throw new IllegalStateException("no more fake responses");
            }
            try {
                return ObjectMappers.jsonMapper().readValue(responses.poll(), Message.class);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static class RecordingListener implements AgentEventListener {
        final StringBuilder text = new StringBuilder();
        final List<String> statuses = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        boolean done;

        @Override
        public void onText(String delta) {
            text.append(delta);
        }

        @Override
        public void onThinking(String delta) {
        }

        @Override
        public void onStatus(String message) {
            statuses.add(message);
        }

        @Override
        public void onUiEvent(String type, Object data) {
        }

        @Override
        public void onDone() {
            done = true;
        }

        @Override
        public void onError(String message) {
            errors.add(message);
        }
    }
}
