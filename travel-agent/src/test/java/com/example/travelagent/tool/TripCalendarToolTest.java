package com.example.travelagent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.anthropic.core.JsonValue;

class TripCalendarToolTest {

    private final TripCalendarTool tool = new TripCalendarTool(ToolTestSupport.MAPPER);

    @Test
    void 요일과_일본_공휴일을_계산한다() {
        ToolResult result = tool.execute(JsonValue.from(Map.of(
                "start_date", "2026-10-10", "end_date", "2026-10-13", "country", "JP")));

        assertThat(result.error()).isFalse();
        assertThat(result.content())
                .contains("{\"date\":\"2026-10-10\",\"dayOfWeek\":\"토요일\",\"weekend\":true,\"holiday\":null}")
                .contains("{\"date\":\"2026-10-12\",\"dayOfWeek\":\"월요일\",\"weekend\":false,\"holiday\":\"スポーツの日 (체육의 날)\"}")
                .contains("\"2026-10-13\",\"dayOfWeek\":\"화요일\"")
                .doesNotContain("공휴일 데이터가 없습니다");
    }

    @Test
    void 데이터가_없는_기간이면_웹검색을_권한다() {
        ToolResult result = tool.execute(JsonValue.from(Map.of(
                "start_date", "2027-05-01", "end_date", "2027-05-04", "country", "JP")));

        assertThat(result.content()).contains("공휴일 데이터가 없습니다");
    }

    @Test
    void 너무_긴_기간은_거절한다() {
        ToolResult result = tool.execute(JsonValue.from(Map.of(
                "start_date", "2026-10-01", "end_date", "2026-12-01", "country", "JP")));

        assertThat(result.error()).isTrue();
    }
}
