package com.example.travelagent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.anthropic.core.JsonValue;

class BookingLinksToolTest {

    private final BookingLinksTool tool = new BookingLinksTool(ToolTestSupport.MAPPER, ToolTestSupport.properties());

    @Test
    void 항공_링크에_날짜와_유아_인원이_들어간다() {
        ToolResult result = tool.execute(JsonValue.from(Map.of(
                "category", "flight", "destination", "Fukuoka", "destination_airport", "fuk",
                "start_date", "2026-10-10", "end_date", "2026-10-13",
                "adults", 2, "child_ages", List.of(1))));

        assertThat(result.error()).isFalse();
        assertThat(result.content())
                .contains("skyscanner.co.kr/transport/flights/icn/fuk/261010/261013/?adultsv2=2&childrenv2=1")
                .contains("flight.naver.com/flights/international/ICN-FUK-20261010/FUK-ICN-20261013?adult=2&child=0&infant=1")
                .contains("google.com/travel/flights");
    }

    @Test
    void 숙소_링크는_여러_사이트를_만든다() {
        ToolResult result = tool.execute(JsonValue.from(Map.of(
                "category", "stay", "destination", "Fukuoka Tenjin",
                "start_date", "2026-10-10", "end_date", "2026-10-13",
                "adults", 2, "child_ages", List.of(1))));

        assertThat(result.error()).isFalse();
        assertThat(result.content())
                .contains("agoda.com", "los=3", "booking.com", "group_children=1", "&age=1",
                        "hotels.com", "expedia.co.kr", "airbnb.co.kr", "infants=1", "trip.com")
                .contains("Fukuoka+Tenjin");
    }

    @Test
    void 항공인데_공항코드가_없으면_오류() {
        ToolResult result = tool.execute(JsonValue.from(Map.of(
                "category", "flight", "destination", "Fukuoka",
                "start_date", "2026-10-10", "end_date", "2026-10-13",
                "adults", 2, "child_ages", List.of(1))));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("destination_airport");
    }

    @Test
    void 날짜가_거꾸로면_오류() {
        ToolResult result = tool.execute(JsonValue.from(Map.of(
                "category", "stay", "destination", "Fukuoka",
                "start_date", "2026-10-13", "end_date", "2026-10-10",
                "adults", 2, "child_ages", List.of())));

        assertThat(result.error()).isTrue();
    }
}
