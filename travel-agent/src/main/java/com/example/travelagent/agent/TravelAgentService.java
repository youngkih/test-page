package com.example.travelagent.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.NoCredentialsException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.WebFetchTool20260209;
import com.anthropic.models.messages.WebSearchTool20260209;
import com.example.travelagent.config.AgentProperties;
import com.example.travelagent.tool.AgentTool;
import com.example.travelagent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 여행 에이전트의 핵심: "에이전트 루프".
 *
 * <pre>
 *  사용자 메시지 추가
 *     └▶ Claude 호출 ──▶ stop_reason 확인
 *            ├ tool_use   : 우리 도구 실행 → 결과를 대화에 추가 → 다시 Claude 호출
 *            ├ pause_turn : 웹 검색을 오래 해서 서버가 잠시 멈춤 → 그대로 다시 호출하면 이어서 진행
 *            └ end_turn   : 답변 완료 → 종료
 * </pre>
 *
 * 웹 검색(web_search)과 웹 페이지 읽기(web_fetch)는 Anthropic 서버가 대신 실행하므로
 * 우리 코드에는 실행 로직이 없다. 우리 서버가 실행하는 도구는 {@link AgentTool} 구현체들이다.
 */
@Service
public class TravelAgentService {

    private static final Logger log = LoggerFactory.getLogger(TravelAgentService.class);

    private final ClaudeGateway gateway;
    private final AgentProperties properties;
    private final SystemPromptFactory systemPromptFactory;
    private final ObjectMapper objectMapper;
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public TravelAgentService(ClaudeGateway gateway, AgentProperties properties,
            SystemPromptFactory systemPromptFactory, ObjectMapper objectMapper, List<AgentTool> tools) {
        this.gateway = gateway;
        this.properties = properties;
        this.systemPromptFactory = systemPromptFactory;
        this.objectMapper = objectMapper;
        // 도구 순서가 요청마다 바뀌면 프롬프트 캐시가 깨지므로 이름순으로 고정
        tools.stream()
                .sorted((a, b) -> a.name().compareTo(b.name()))
                .forEach(tool -> this.tools.put(tool.name(), tool));
    }

    /** 사용자 메시지 한 건을 처리한다. 블로킹 메서드이므로 별도 스레드에서 호출할 것. */
    public void chat(Conversation conversation, String userText, AgentEventListener listener) {
        if (!conversation.turnLock().tryLock()) {
            listener.onError("이전 요청을 아직 처리하고 있습니다. 답변이 끝난 뒤 다시 보내 주세요.");
            return;
        }
        StringBuilder answer = new StringBuilder();
        try {
            conversation.append(MessageParam.builder().role(MessageParam.Role.USER).content(userText).build());
            conversation.addTranscript("user", userText);
            runAgentLoop(conversation, listener, answer);
            listener.onDone();
        } catch (RuntimeException e) {
            log.warn("agent turn failed", e);
            listener.onError(toUserMessage(e));
        } finally {
            if (!answer.isEmpty()) {
                conversation.addTranscript("assistant", answer.toString());
            }
            conversation.turnLock().unlock();
        }
    }

    private void runAgentLoop(Conversation conversation, AgentEventListener listener, StringBuilder answer) {
        for (int iteration = 1; iteration <= properties.maxAgentIterations(); iteration++) {
            MessageCreateParams params = buildParams(conversation.messages());
            Message message = gateway.stream(params, new StreamForwarder(listener, objectMapper));

            // 응답 전체(생각 블록, 검색 결과 포함)를 그대로 기록에 붙인다. 텍스트만 뽑아 붙이면 안 된다.
            conversation.append(message.toParam());
            appendText(message, answer);

            StopReason stopReason = message.stopReason().orElse(StopReason.END_TURN);
            if (StopReason.TOOL_USE.equals(stopReason)) {
                conversation.append(runClientTools(message, conversation, listener));
                continue;
            }
            if (StopReason.PAUSE_TURN.equals(stopReason)) {
                // 서버 쪽 도구 루프가 한도에 걸려 잠시 멈춘 것. "계속" 같은 메시지를 추가하지 않고 그대로 재호출한다.
                continue;
            }
            if (StopReason.MAX_TOKENS.equals(stopReason)) {
                listener.onText("\n\n_(답변이 너무 길어 중간에 잘렸습니다. \"계속\"이라고 보내 주세요.)_");
            } else if (StopReason.REFUSAL.equals(stopReason)) {
                listener.onText("\n\n_(이 요청은 처리할 수 없습니다. 다른 방식으로 요청해 주세요.)_");
            }
            return;
        }
        listener.onText("\n\n_(작업 단계가 너무 많아 여기서 멈췄습니다. \"계속\"이라고 보내 주시면 이어서 진행합니다.)_");
    }

    MessageCreateParams buildParams(List<MessageParam> messages) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxTokens())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(systemPromptFactory.build())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                // adaptive thinking: 어려운 판단(예산 배분, 동선 최적화)에서 모델이 알아서 더 깊게 생각한다
                .thinking(ThinkingConfigAdaptive.builder()
                        .display(ThinkingConfigAdaptive.Display.SUMMARIZED)
                        .build())
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.of(properties.effort()))
                        .build())
                .addTool(WebSearchTool20260209.builder().maxUses(properties.maxWebSearches()).build())
                .addTool(WebFetchTool20260209.builder().maxUses(properties.maxWebFetches()).build());
        tools.values().forEach(tool -> builder.addTool(tool.definition()));
        return builder
                // 대화 기록의 마지막 블록까지 자동 캐시 → 긴 대화에서 입력 비용을 크게 줄인다
                .cacheControl(CacheControlEphemeral.builder().build())
                .messages(messages)
                .build();
    }

    /** 응답 안의 tool_use 블록을 모두 실행하고, 결과를 "하나의" user 메시지로 묶어 돌려준다. */
    private MessageParam runClientTools(Message message, Conversation conversation, AgentEventListener listener) {
        List<ContentBlockParam> results = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            block.toolUse().ifPresent(toolUse -> {
                ToolResult result = executeTool(toolUse);
                if (result.uiEvent() != null) {
                    conversation.saveUiData(result.uiEvent().type(), result.uiEvent().data());
                    listener.onUiEvent(result.uiEvent().type(), result.uiEvent().data());
                }
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(toolUse.id())
                        .content(result.content())
                        .isError(result.error())
                        .build()));
            });
        }
        return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(results)
                .build();
    }

    private ToolResult executeTool(ToolUseBlock toolUse) {
        AgentTool tool = tools.get(toolUse.name());
        if (tool == null) {
            return ToolResult.error("알 수 없는 도구입니다: " + toolUse.name());
        }
        try {
            return tool.execute(toolUse._input());
        } catch (RuntimeException e) {
            log.warn("tool {} failed", toolUse.name(), e);
            return ToolResult.error("도구 실행 중 오류: " + e.getMessage());
        }
    }

    private static void appendText(Message message, StringBuilder answer) {
        for (ContentBlock block : message.content()) {
            block.text().ifPresent(text -> {
                if (!answer.isEmpty() && !text.text().isEmpty()) {
                    answer.append("\n\n");
                }
                answer.append(text.text());
            });
        }
    }

    /** SDK 예외를 사용자에게 보여줄 한국어 메시지로 바꾼다. 구체적인 예외부터 검사한다. */
    static String toUserMessage(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof NoCredentialsException
                    || (t instanceof UnauthorizedException && isBlank(System.getenv("ANTHROPIC_API_KEY")))) {
                return "API 키가 설정되지 않았습니다. ANTHROPIC_API_KEY 환경 변수를 설정한 뒤 서버를 다시 시작해 주세요.";
            }
            if (t instanceof UnauthorizedException) {
                return "API 키가 올바르지 않습니다. ANTHROPIC_API_KEY 값을 확인해 주세요.";
            }
            if (t instanceof RateLimitException) {
                return "요청이 너무 많아 잠시 제한되었습니다. 1분 정도 뒤에 다시 시도해 주세요.";
            }
            if (t instanceof AnthropicServiceException service) {
                return "Claude API 오류 (HTTP " + service.statusCode() + "): " + service.getMessage();
            }
            if (t instanceof AnthropicIoException) {
                return "네트워크 오류로 Claude API 에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
            }
        }
        return "알 수 없는 오류가 발생했습니다: " + error.getMessage();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
