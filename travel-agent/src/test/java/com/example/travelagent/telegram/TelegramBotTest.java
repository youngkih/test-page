package com.example.travelagent.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.example.travelagent.agent.ClaudeGateway;
import com.example.travelagent.agent.ConversationStore;
import com.example.travelagent.agent.SystemPromptFactory;
import com.example.travelagent.agent.TravelAgentService;
import com.example.travelagent.config.AgentProperties;
import com.example.travelagent.tool.SaveItineraryTool;
import com.fasterxml.jackson.databind.ObjectMapper;

class TelegramBotTest {

    private static final long OWNER = 1111L;
    private static final long STRANGER = 9999L;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-27T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final FakeTelegramApi api = new FakeTelegramApi();
    private final Deque<String> claudeResponses = new ArrayDeque<>();
    private int claudeCalls;

    private final ClaudeGateway gateway = (params, onEvent) -> {
        claudeCalls++;
        try {
            Message message = ObjectMappers.jsonMapper().readValue(claudeResponses.poll(), Message.class);
            // 실제 API 처럼 텍스트를 스트림 이벤트(text_delta)로도 흘려보낸다
            for (var block : message.content()) {
                if (block.text().isPresent()) {
                    String event = "{\"type\":\"content_block_delta\",\"index\":0,"
                            + "\"delta\":{\"type\":\"text_delta\",\"text\":" + quote(block.text().get().text()) + "}}";
                    onEvent.accept(ObjectMappers.jsonMapper().readValue(event, RawMessageStreamEvent.class));
                }
            }
            return message;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    };

    private TelegramBot bot(Long allowedUserId) {
        AgentProperties agentProperties = new AgentProperties("claude-opus-5", "high", 64000, 15, 20, 5,
                new AgentProperties.Family("서울", "ICN", "한국어", List.of(), List.of()));
        TravelAgentService agent = new TravelAgentService(gateway, agentProperties,
                new SystemPromptFactory(agentProperties, clock), new ObjectMapper(), List.of(new SaveItineraryTool()));
        TelegramProperties properties = new TelegramProperties("123:ABC", allowedUserId, 30, "http://unused");
        // 테스트에서는 에이전트를 같은 스레드에서 바로 실행 (Runnable::run)
        return new TelegramBot(api, agent, new ConversationStore(), properties, new ReportRenderer(), clock,
                Runnable::run);
    }

    private static TelegramApi.Update message(long userId, String chatType, String text) {
        return new TelegramApi.Update(1, new TelegramApi.Message(10,
                new TelegramApi.User(userId, false, "영기", null), new TelegramApi.Chat(userId, chatType), text));
    }

    private static String endTurn(String text) {
        return """
                {"id":"m","type":"message","role":"assistant","model":"claude-opus-5",
                 "content":[{"type":"text","text":%s,"citations":null}],
                 "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}
                """.formatted(quote(text));
    }

    private static String quote(String text) {
        try {
            return new ObjectMapper().writeValueAsString(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 허용되지_않은_사용자는_무시하고_Claude도_호출하지_않는다() {
        bot(OWNER).handle(message(STRANGER, "private", "일본 여행 계획 짜줘"));

        assertThat(api.sent).isEmpty();
        assertThat(claudeCalls).isZero();
    }

    @Test
    void 그룹_채팅은_주인이_보내도_무시한다() {
        bot(OWNER).handle(message(OWNER, "group", "일본 여행 계획 짜줘"));

        assertThat(api.sent).isEmpty();
        assertThat(claudeCalls).isZero();
    }

    @Test
    void 설정_모드에서는_사용자_ID만_알려준다() {
        bot(null).handle(message(STRANGER, "private", "안녕"));

        assertThat(api.sent).singleElement().extracting(FakeTelegramApi.Sent::text).asString()
                .contains("설정 모드").contains(String.valueOf(STRANGER));
        assertThat(claudeCalls).isZero();
    }

    @Test
    void 주인의_메시지는_에이전트가_답하고_마크다운을_HTML로_보낸다() {
        claudeResponses.add(endTurn("## 추천\n**후쿠오카**가 좋아요. [지도](https://www.google.com/maps/search/?api=1&query=a)"));

        bot(OWNER).handle(message(OWNER, "private", "어디가 좋을까?"));

        assertThat(claudeCalls).isEqualTo(1);
        assertThat(api.sent.get(0).text()).contains("요청을 확인"); // 상태 메시지
        assertThat(api.edits).last().isEqualTo("✅ 답변 완료");
        FakeTelegramApi.Sent answer = api.sent.get(1);
        assertThat(answer.html()).isTrue();
        assertThat(answer.text()).isEqualTo("<b>추천</b>\n<b>후쿠오카</b>가 좋아요. "
                + "<a href=\"https://www.google.com/maps/search/?api=1&amp;query=a\">지도</a>");
    }

    @Test
    void HTML_해석에_실패하면_일반_텍스트로_다시_보낸다() {
        api.rejectHtml = true;
        claudeResponses.add(endTurn("**굵게**"));

        bot(OWNER).handle(message(OWNER, "private", "안녕"));

        assertThat(api.sent).last().satisfies(sent -> {
            assertThat(sent.html()).isFalse();
            assertThat(sent.text()).isEqualTo("**굵게**");
        });
    }

    @Test
    void 일정표를_저장하면_HTML_파일로_첨부하고_plan으로_다시_받을_수_있다() {
        claudeResponses.add("""
                {"id":"m1","type":"message","role":"assistant","model":"claude-opus-5",
                 "content":[{"type":"tool_use","id":"t1","name":"save_itinerary","input":{
                   "title":"후쿠오카 <script>","destination":"Fukuoka","start_date":"2026-10-10","end_date":"2026-10-10",
                   "summary":"요약","checklist":[],
                   "days":[{"date":"2026-10-10","theme":"도착","items":[
                     {"start":"10:00","end":"11:30","category":"kids_play","title":"놀이터","place":"공원",
                      "maps_url":"javascript:alert(1)","how":"","kid_notes":"","closed_days":"연중무휴"}]}]}}],
                 "stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}
                """);
        claudeResponses.add(endTurn("일정표를 만들었어요."));
        TelegramBot bot = bot(OWNER);

        bot.handle(message(OWNER, "private", "일정 짜줘"));

        assertThat(api.documents).singleElement().satisfies(doc -> {
            assertThat(doc.fileName()).isEqualTo("itinerary.html");
            assertThat(doc.content()).contains("후쿠오카 &lt;script&gt;").doesNotContain("<script>")
                    .doesNotContain("javascript:");
        });

        bot.handle(message(OWNER, "private", "/plan"));
        assertThat(api.documents).hasSize(2);
    }

    @Test
    void new_명령은_대화를_초기화한다() {
        TelegramBot bot = bot(OWNER);
        bot.handle(message(OWNER, "private", "/new"));
        bot.handle(message(OWNER, "private", "/plan"));

        assertThat(api.sent).extracting(FakeTelegramApi.Sent::text)
                .containsExactly("🆕 새 대화를 시작했어요. 어디로 여행 가고 싶으세요?", "아직 만든 일정표가 없어요.");
        assertThat(claudeCalls).isZero();
    }

    @Test
    void 명령어_파싱() {
        assertThat(TelegramBot.command("/new@travel_bot")).isEqualTo("/new");
        assertThat(TelegramBot.command("/PLAN 지금")).isEqualTo("/plan");
        assertThat(TelegramBot.command("일본 가고 싶어")).isEmpty();
    }
}
