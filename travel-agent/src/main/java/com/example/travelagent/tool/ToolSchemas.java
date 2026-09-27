package com.example.travelagent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

/** JSON 스키마를 Map 으로 간결하게 적기 위한 헬퍼. */
final class ToolSchemas {

    private ToolSchemas() {
    }

    static Tool tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        properties.forEach((key, schema) -> props.putAdditionalProperty(key, JsonValue.from(schema)));

        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(props.build())
                        .required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                // 큰 입력(일정표 등)을 생성되는 대로 스트리밍 받는다. 대신 입력 검증은 우리 몫.
                .eagerInputStreaming(true)
                .build();
    }

    static Map<String, Object> string(String description) {
        return ordered("type", "string", "description", description);
    }

    static Map<String, Object> date(String description) {
        return ordered("type", "string", "description", description + " (yyyy-MM-dd)",
                "pattern", "^\\d{4}-\\d{2}-\\d{2}$");
    }

    static Map<String, Object> integer(String description) {
        return ordered("type", "integer", "description", description);
    }

    static Map<String, Object> number(String description) {
        return ordered("type", "number", "description", description);
    }

    static Map<String, Object> enumOf(String description, List<String> values) {
        return ordered("type", "string", "description", description, "enum", values);
    }

    static Map<String, Object> arrayOf(String description, Map<String, Object> items) {
        return ordered("type", "array", "description", description, "items", items);
    }

    static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return ordered("type", "object", "properties", properties, "required", required,
                "additionalProperties", false);
    }

    /**
     * 키 순서가 고정된 Map. Map.of 는 JVM 을 재시작할 때마다 순회 순서가 바뀔 수 있어
     * 도구 정의 JSON 이 달라지고, 그러면 프롬프트 캐시가 깨진다.
     */
    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @SafeVarargs
    static Map<String, Object> props(Map.Entry<String, Object>... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : entries) {
            map.put(e.getKey(), e.getValue());
        }
        return map;
    }

    static Map.Entry<String, Object> p(String name, Map<String, Object> schema) {
        return Map.entry(name, schema);
    }
}
