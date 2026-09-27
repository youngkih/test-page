package com.example.travelagent.telegram;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.travelagent.agent.AgentEventListener;
import com.example.travelagent.tool.SaveItineraryTool;
import com.example.travelagent.tool.SavePriceComparisonTool;

/**
 * 에이전트 이벤트를 텔레그램 메시지로 바꾼다.
 *
 * <ul>
 *   <li>진행 상황: "상태 메시지" 하나를 만들어 두고 계속 수정한다 (메시지 폭탄 방지)</li>
 *   <li>답변: 텔레그램은 글자 단위 스트리밍이 어색하므로 모아 두었다가 끝나면 한 번에 보낸다</li>
 *   <li>일정표/비교표: HTML 파일로 첨부한다</li>
 * </ul>
 */
class TelegramAgentEventListener implements AgentEventListener {

    private static final Logger log = LoggerFactory.getLogger(TelegramAgentEventListener.class);

    /** 텔레그램은 같은 채팅에 너무 자주 수정하면 429(Too Many Requests)를 준다. */
    static final Duration STATUS_EDIT_INTERVAL = Duration.ofSeconds(2);

    private final TelegramApi api;
    private final long chatId;
    private final ReportRenderer renderer;
    private final Clock clock;
    private final StringBuilder answer = new StringBuilder();
    private final Map<String, Object> reports = new LinkedHashMap<>();

    private Long statusMessageId;
    private String lastStatus;
    private Instant lastEdit = Instant.EPOCH;

    TelegramAgentEventListener(TelegramApi api, long chatId, ReportRenderer renderer, Clock clock) {
        this.api = api;
        this.chatId = chatId;
        this.renderer = renderer;
        this.clock = clock;
    }

    /** 턴 시작 시 상태 메시지를 만든다. */
    void start() {
        quietly(() -> api.sendChatAction(chatId, "typing"));
        lastStatus = "🤔 요청을 확인하고 있어요...";
        try {
            statusMessageId = api.sendMessage(chatId, lastStatus, false);
        } catch (TelegramException e) {
            log.warn("상태 메시지 전송 실패: {}", e.getMessage());
        }
    }

    @Override
    public void onText(String delta) {
        answer.append(delta);
    }

    @Override
    public void onThinking(String delta) {
        // 텔레그램에서는 생각 과정을 보여주지 않는다
    }

    @Override
    public void onStatus(String message) {
        Instant now = clock.instant();
        if (message.equals(lastStatus) || now.isBefore(lastEdit.plus(STATUS_EDIT_INTERVAL))) {
            return;
        }
        lastEdit = now;
        editStatus(message);
        quietly(() -> api.sendChatAction(chatId, "typing"));
    }

    @Override
    public void onUiEvent(String type, Object data) {
        reports.put(type, data); // 같은 종류는 마지막 것만 보낸다
    }

    @Override
    public void onDone() {
        editStatus("✅ 답변 완료");
        sendAnswer();
        reports.forEach((type, data) -> sendReport(api, chatId, renderer, type, data));
    }

    @Override
    public void onError(String message) {
        sendAnswer(); // 오류 전까지 쓴 답변이 있으면 보낸다
        if (statusMessageId != null) {
            editStatus("⚠️ " + message);
        } else {
            quietly(() -> api.sendMessage(chatId, "⚠️ " + message, false));
        }
    }

    private void editStatus(String text) {
        lastStatus = text;
        if (statusMessageId == null) {
            return;
        }
        quietly(() -> api.editMessageText(chatId, statusMessageId, text));
    }

    private void sendAnswer() {
        String text = answer.toString().strip();
        answer.setLength(0);
        for (String chunk : TelegramFormatter.split(text, TelegramFormatter.CHUNK_SIZE)) {
            sendFormatted(api, chatId, chunk);
        }
    }

    /** HTML 로 보내 보고, 텔레그램이 해석에 실패하면(400) 원문 그대로 다시 보낸다. */
    static void sendFormatted(TelegramApi api, long chatId, String markdown) {
        try {
            api.sendMessage(chatId, TelegramFormatter.toHtml(markdown), true);
        } catch (TelegramException e) {
            log.debug("HTML 전송 실패, 일반 텍스트로 재전송: {}", e.getMessage());
            quietly(() -> api.sendMessage(chatId, markdown, false));
        }
    }

    static void sendReport(TelegramApi api, long chatId, ReportRenderer renderer, String type, Object data) {
        String html;
        String fileName;
        String caption;
        if (data instanceof SaveItineraryTool.Itinerary itinerary) {
            html = renderer.itinerary(itinerary);
            fileName = "itinerary.html";
            caption = "📝 일정표 — 파일을 열면 시간대별 일정과 지도 링크를 볼 수 있어요.";
        } else if (data instanceof SavePriceComparisonTool.Comparison comparison) {
            html = renderer.comparison(comparison);
            fileName = "price-comparison.html";
            caption = "💰 가격 비교표 — 조사 시점 기준 참고 가격입니다.";
        } else {
            log.debug("알 수 없는 리포트 종류: {}", type);
            return;
        }
        quietly(() -> api.sendChatAction(chatId, "upload_document"));
        quietly(() -> api.sendDocument(chatId, fileName, html.getBytes(StandardCharsets.UTF_8), caption));
    }

    /** 부가 기능(상태 표시 등) 실패로 에이전트 작업 전체가 멈추지 않도록 오류를 기록만 한다. */
    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (TelegramException e) {
            log.warn("텔레그램 호출 실패: {}", e.getMessage());
        }
    }
}
