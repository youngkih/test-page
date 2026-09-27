package com.example.travelagent.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Claude 스트림 이벤트를 화면용 이벤트(텍스트 조각, 진행 상황)로 바꿔 전달한다.
 *
 * <p>스트림 이벤트 흐름: content_block_start → content_block_delta(여러 번) → content_block_stop.
 * 웹 검색어는 delta 로 조각조각 오기 때문에 stop 시점에 모아서 파싱한다.
 * 한 번의 API 호출마다 새 인스턴스를 만든다 (index 가 호출마다 0부터 시작하므로).
 */
class StreamForwarder implements Consumer<RawMessageStreamEvent> {

    private final AgentEventListener listener;
    private final ObjectMapper objectMapper;
    private final Map<Long, String> serverToolNames = new HashMap<>();
    private final Map<Long, StringBuilder> serverToolInputs = new HashMap<>();

    StreamForwarder(AgentEventListener listener, ObjectMapper objectMapper) {
        this.listener = listener;
        this.objectMapper = objectMapper;
    }

    @Override
    public void accept(RawMessageStreamEvent event) {
        event.contentBlockStart().ifPresent(start -> onBlockStart(start.index(), start.contentBlock()));

        event.contentBlockDelta().ifPresent(deltaEvent -> {
            var delta = deltaEvent.delta();
            delta.text().ifPresent(text -> listener.onText(text.text()));
            delta.thinking().ifPresent(thinking -> listener.onThinking(thinking.thinking()));
            delta.inputJson().ifPresent(json -> {
                StringBuilder buffer = serverToolInputs.get(deltaEvent.index());
                if (buffer != null) {
                    buffer.append(json.partialJson());
                }
            });
        });

        event.contentBlockStop().ifPresent(stop -> {
            String toolName = serverToolNames.remove(stop.index());
            StringBuilder input = serverToolInputs.remove(stop.index());
            if (toolName != null) {
                listener.onStatus(describeServerTool(toolName, input == null ? "" : input.toString()));
            }
        });
    }

    private void onBlockStart(long index, RawContentBlockStartEvent.ContentBlock block) {
        block.serverToolUse().ifPresent(use -> {
            serverToolNames.put(index, use._name().asString().orElse(use.name().toString()));
            serverToolInputs.put(index, new StringBuilder());
        });
        block.toolUse().ifPresent(use -> listener.onStatus(describeClientTool(use.name())));
        block.thinking().ifPresent(thinking -> listener.onStatus("🤔 생각하는 중..."));
    }

    String describeServerTool(String toolName, String inputJson) {
        String query = "";
        try {
            JsonNode node = objectMapper.readTree(inputJson.isBlank() ? "{}" : inputJson);
            query = node.path("query").asText(node.path("url").asText(""));
        } catch (Exception ignored) {
            // 진행 상황 표시용이라 파싱 실패는 무시
        }
        return switch (toolName) {
            case "web_search" -> "🔎 웹 검색: " + query;
            case "web_fetch" -> "📄 페이지 읽는 중: " + query;
            default -> "⚙️ " + toolName + " " + query;
        };
    }

    static String describeClientTool(String toolName) {
        return switch (toolName) {
            case "booking_search_links" -> "🔗 예약 사이트 링크 만드는 중...";
            case "google_maps_links" -> "🗺️ 구글 지도 링크 만드는 중...";
            case "trip_calendar" -> "📅 날짜·요일·공휴일 확인 중...";
            case "save_itinerary" -> "📝 일정표 작성 중...";
            case "save_price_comparison" -> "💰 가격 비교표 작성 중...";
            default -> "🛠️ " + toolName + " 실행 중...";
        };
    }
}
