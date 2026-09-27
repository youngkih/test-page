package com.example.travelagent.telegram;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.travelagent.agent.Conversation;
import com.example.travelagent.agent.ConversationStore;
import com.example.travelagent.agent.TravelAgentService;

/**
 * 텔레그램 메시지 한 건을 처리한다: 보안 검사 → 명령어 처리 → 에이전트 호출.
 *
 * <p><b>보안 규칙</b>
 * <ol>
 *   <li>1:1 채팅(private)만 처리한다. 그룹에 초대돼도 무시한다.</li>
 *   <li>허용된 사용자 ID 한 명에게만 응답한다. 다른 사람에게는 아무 답도 하지 않는다
 *       (봇이 살아 있다는 것도 알리지 않고, Claude API 도 호출하지 않아 비용이 나가지 않는다).</li>
 *   <li>사용자 ID 는 숫자라서 바뀌지 않는다. 바꿀 수 있는 @username 으로는 확인하지 않는다.</li>
 *   <li>메시지 내용은 로그에 남기지 않는다.</li>
 * </ol>
 */
@Component
public class TelegramBot {

    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);

    static final String HELP = """
            👶✈️ 가족 여행 에이전트예요.
            가고 싶은 곳, 날짜, 예산을 자유롭게 말씀해 주세요. 제가 질문하면서 함께 계획을 세울게요.

            /new — 새 여행 계획 시작 (지금 대화 초기화)
            /plan — 마지막 일정표 다시 받기
            /prices — 마지막 가격 비교표 다시 받기
            /help — 도움말""";

    private final TelegramApi api;
    private final TravelAgentService agent;
    private final ConversationStore store;
    private final TelegramProperties properties;
    private final ReportRenderer renderer;
    private final Clock clock;
    private final Executor executor;
    private final Map<Long, Conversation> conversations = new ConcurrentHashMap<>();

    @Autowired
    public TelegramBot(TelegramApi api, TravelAgentService agent, ConversationStore store,
            TelegramProperties properties, ReportRenderer renderer, Clock clock) {
        // 에이전트 한 턴은 몇 분 걸릴 수 있어서, 폴링 스레드를 막지 않도록 가상 스레드에서 실행한다
        this(api, agent, store, properties, renderer, clock, Executors.newVirtualThreadPerTaskExecutor());
    }

    TelegramBot(TelegramApi api, TravelAgentService agent, ConversationStore store, TelegramProperties properties,
            ReportRenderer renderer, Clock clock, Executor executor) {
        this.api = api;
        this.agent = agent;
        this.store = store;
        this.properties = properties;
        this.renderer = renderer;
        this.clock = clock;
        this.executor = executor;
    }

    public void handle(TelegramApi.Update update) {
        TelegramApi.Message message = update.message();
        if (message == null || message.from() == null || message.chat() == null) {
            return;
        }
        if (!"private".equals(message.chat().type()) || message.from().isBot()) {
            return;
        }
        long chatId = message.chat().id();
        long userId = message.from().id();

        if (properties.allowedUserId() == null) {
            // 설정 모드: 허용 사용자를 아직 정하지 않았을 때만, 본인 ID 확인용으로 답한다 (Claude 는 호출하지 않음)
            log.info("[설정 모드] 텔레그램 사용자 ID {} 에게서 메시지 수신", userId);
            safeSend(chatId, "🔧 설정 모드입니다.\n당신의 텔레그램 사용자 ID: " + userId
                    + "\n\n이 값을 TELEGRAM_ALLOWED_USER_ID 로 설정하고 서버를 다시 시작하세요.");
            return;
        }
        if (!Objects.equals(properties.allowedUserId(), userId)) {
            log.warn("허용되지 않은 텔레그램 사용자 ID {} 의 메시지를 무시했습니다", userId);
            return;
        }

        String text = message.text() == null ? "" : message.text().strip();
        if (text.isEmpty()) {
            safeSend(chatId, "지금은 글자 메시지만 이해할 수 있어요. 🙏");
            return;
        }
        switch (command(text)) {
            case "/start", "/help" -> safeSend(chatId, HELP);
            case "/new" -> {
                conversations.put(chatId, store.create());
                safeSend(chatId, "🆕 새 대화를 시작했어요. 어디로 여행 가고 싶으세요?");
            }
            case "/plan" -> sendSavedReport(chatId, "itinerary", "아직 만든 일정표가 없어요.");
            case "/prices" -> sendSavedReport(chatId, "comparison", "아직 만든 가격 비교표가 없어요.");
            default -> executor.execute(() -> runTurn(chatId, text));
        }
    }

    private void runTurn(long chatId, String text) {
        Conversation conversation = conversation(chatId);
        TelegramAgentEventListener listener = new TelegramAgentEventListener(api, chatId, renderer, clock);
        listener.start();
        agent.chat(conversation, text, listener);
    }

    private void sendSavedReport(long chatId, String type, String emptyMessage) {
        Conversation conversation = conversation(chatId);
        Object data = "itinerary".equals(type) ? conversation.itinerary() : conversation.comparison();
        if (data == null) {
            safeSend(chatId, emptyMessage);
            return;
        }
        TelegramAgentEventListener.sendReport(api, chatId, renderer, type, data);
    }

    private Conversation conversation(long chatId) {
        return conversations.computeIfAbsent(chatId, id -> store.create());
    }

    /** "/new@MyBot 뭐든" → "/new". 명령어가 아니면 빈 문자열. */
    static String command(String text) {
        if (!text.startsWith("/")) {
            return "";
        }
        String first = text.split("\\s+", 2)[0];
        int at = first.indexOf('@');
        return (at > 0 ? first.substring(0, at) : first).toLowerCase();
    }

    private void safeSend(long chatId, String text) {
        try {
            api.sendMessage(chatId, text, false);
        } catch (TelegramException e) {
            log.warn("메시지 전송 실패: {}", e.getMessage());
        }
    }
}
