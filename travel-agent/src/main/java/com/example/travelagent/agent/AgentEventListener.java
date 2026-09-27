package com.example.travelagent.agent;

/** 에이전트가 일하는 동안 화면으로 흘려보낼 이벤트. (웹에서는 SSE 로 전송된다) */
public interface AgentEventListener {

    /** 답변 텍스트 조각 (스트리밍). */
    void onText(String delta);

    /** 모델의 생각 요약 조각. */
    void onThinking(String delta);

    /** "웹 검색 중: ..." 같은 진행 상황. */
    void onStatus(String message);

    /** 일정표/가격 비교표 같은 구조화 데이터. */
    void onUiEvent(String type, Object data);

    void onDone();

    void onError(String message);
}
