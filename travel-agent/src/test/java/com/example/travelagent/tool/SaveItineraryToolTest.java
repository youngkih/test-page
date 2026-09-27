package com.example.travelagent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.anthropic.core.JsonValue;

class SaveItineraryToolTest {

    private final SaveItineraryTool tool = new SaveItineraryTool();

    private static Map<String, Object> item(String start, String end, String category, String mapsUrl,
            String closedDays) {
        return Map.of("start", start, "end", end, "category", category, "title", category + " 일정",
                "place", "장소", "maps_url", mapsUrl, "how", "도보 5분", "kid_notes", "", "closed_days", closedDays);
    }

    private static JsonValue itinerary(List<Map<String, Object>> days) {
        return JsonValue.from(Map.of("title", "후쿠오카", "destination", "Fukuoka",
                "start_date", "2026-10-10", "end_date", "2026-10-13", "summary", "요약",
                "days", days, "checklist", List.of("여권")));
    }

    @Test
    void 규칙을_지킨_일정은_경고없이_저장된다() {
        ToolResult result = tool.execute(itinerary(List.of(Map.of("date", "2026-10-11", "theme", "놀이", "items", List.of(
                item("10:00", "11:30", "kids_play", "", "연중무휴"),
                item("12:00", "13:00", "meal", "https://www.google.com/maps/search/?api=1&query=x", "수요일"))))));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("경고 없음");
        assertThat(result.uiEvent().type()).isEqualTo("itinerary");
    }

    @Test
    void 놀이시간_부족과_지도링크_누락을_경고한다() {
        ToolResult result = tool.execute(itinerary(List.of(Map.of("date", "2026-10-11", "theme", "관광", "items", List.of(
                item("10:00", "10:30", "kids_play", "", "연중무휴"),
                item("12:00", "13:00", "meal", "", "미확인"))))));

        assertThat(result.error()).isFalse();
        assertThat(result.content())
                .contains("아이 놀이 시간(kids_play)이 30분")
                .contains("Google 지도 링크(maps_url)가 없습니다")
                .contains("정기휴무/영업일을 확인하지 않았습니다");
        assertThat(result.uiEvent()).isNotNull();
    }

    @Test
    void 여행기간_밖의_날짜나_잘못된_시간은_저장하지_않는다() {
        ToolResult result = tool.execute(itinerary(List.of(Map.of("date", "2026-10-20", "theme", "?", "items", List.of(
                item("9시", "10:00", "kids_play", "", "연중무휴"))))));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("여행 기간", "HH:mm");
        assertThat(result.uiEvent()).isNull();
    }
}
