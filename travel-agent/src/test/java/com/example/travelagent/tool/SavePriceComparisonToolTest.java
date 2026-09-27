package com.example.travelagent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.anthropic.core.JsonValue;

class SavePriceComparisonToolTest {

    private final SavePriceComparisonTool tool = new SavePriceComparisonTool();

    private static Map<String, Object> quote(String option, String site, double price) {
        return Map.of("category", "stay", "option", option, "site", site, "total_price_krw", price,
                "original_price", "¥", "url", "https://example.com", "checked_at", "2026-09-27", "notes", "");
    }

    @Test
    void 상품별_최저가_사이트를_계산한다() {
        ToolResult result = tool.execute(JsonValue.from(Map.of(
                "title", "숙소 비교",
                "quotes", List.of(
                        quote("호텔 A", "Agoda", 620_000),
                        quote("호텔 A", "Booking.com", 598_000),
                        quote("호텔 A", "Expedia", 655_000),
                        quote("호텔 B", "Agoda", 540_000)),
                "recommendation", "호텔 A")));

        assertThat(result.error()).isFalse();
        assertThat(result.content())
                .contains("호텔 A: Booking.com 598,000원 (사이트 3곳 비교, 최고가 대비 57,000원 저렴)")
                .contains("'호텔 B' 은 사이트 1곳만 조사했습니다");

        var comparison = (SavePriceComparisonTool.Comparison) result.uiEvent().data();
        assertThat(comparison.quotes()).extracting(SavePriceComparisonTool.Quote::site)
                .containsExactly("Booking.com", "Agoda", "Expedia", "Agoda");
    }

    @Test
    void 가격이_없으면_저장하지_않는다() {
        ToolResult result = tool.execute(JsonValue.from(Map.of(
                "title", "비교",
                "quotes", List.of(quote("호텔 A", "Agoda", 0)),
                "recommendation", "")));

        assertThat(result.error()).isTrue();
    }
}
