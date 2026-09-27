package com.example.travelagent.tool;

/**
 * 도구 실행 결과.
 *
 * @param content 모델에게 돌려줄 텍스트(JSON 문자열 등)
 * @param error   true 이면 모델이 입력을 고쳐서 다시 시도하도록 is_error 로 전달된다
 * @param uiEvent 웹 화면에 따로 보여줄 데이터 (일정표, 가격 비교표 등). 없으면 null
 */
public record ToolResult(String content, boolean error, UiEvent uiEvent) {

    public static ToolResult ok(String content) {
        return new ToolResult(content, false, null);
    }

    public static ToolResult ok(String content, UiEvent uiEvent) {
        return new ToolResult(content, false, uiEvent);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, true, null);
    }

    /** @param type SSE 이벤트 이름 (예: itinerary, comparison) */
    public record UiEvent(String type, Object data) {
    }
}
