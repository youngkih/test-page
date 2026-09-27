package com.example.travelagent.tool;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

/**
 * Claude 가 호출할 수 있는 "우리 서버 쪽" 도구.
 * (웹 검색/웹 읽기는 Anthropic 서버가 실행하는 server tool 이라 여기에 없다.)
 */
public interface AgentTool {

    String name();

    /** 모델에게 보여줄 도구 정의 (이름, 설명, 입력 JSON 스키마). */
    Tool definition();

    /** 모델이 보낸 입력으로 도구를 실행한다. 예외를 던지지 말고 {@link ToolResult#error} 로 돌려준다. */
    ToolResult execute(JsonValue input);
}
