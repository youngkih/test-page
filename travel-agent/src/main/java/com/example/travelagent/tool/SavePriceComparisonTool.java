package com.example.travelagent.tool;

import static com.example.travelagent.tool.ToolSchemas.arrayOf;
import static com.example.travelagent.tool.ToolSchemas.date;
import static com.example.travelagent.tool.ToolSchemas.enumOf;
import static com.example.travelagent.tool.ToolSchemas.number;
import static com.example.travelagent.tool.ToolSchemas.object;
import static com.example.travelagent.tool.ToolSchemas.p;
import static com.example.travelagent.tool.ToolSchemas.props;
import static com.example.travelagent.tool.ToolSchemas.string;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

/**
 * 여러 사이트에서 조사한 가격을 비교표로 저장한다. 웹 화면의 "가격 비교" 탭에 표시된다.
 *
 * <p>같은 상품(option)끼리 묶어서 최저가를 코드로 계산한다. LLM 에게 숫자 비교를 맡기지 않는 것이 포인트.
 */
@Component
public class SavePriceComparisonTool implements AgentTool {

    @Override
    public String name() {
        return "save_price_comparison";
    }

    @Override
    public Tool definition() {
        return ToolSchemas.tool(name(),
                """
                항공권·숙소·액티비티 가격을 여러 사이트에서 조사한 결과를 비교표로 저장해 화면에 표시한다. \
                같은 상품은 option 이름을 똑같이 적어야 사이트 간 비교가 된다. \
                가격은 3인 가족 전체 총액(세금·수수료 포함, 원화 환산)으로 적고, 환산했다면 notes 에 환율을 적을 것. \
                웹에서 실제로 확인한 가격만 적고, 확인 못 한 값을 추측해서 적지 말 것.""",
                props(
                        p("title", string("비교표 제목 (예: 후쿠오카 숙소 비교 10/10~10/13)")),
                        p("quotes", arrayOf("사이트별 가격", object(
                                props(p("category", enumOf("종류", List.of("flight", "stay", "activity")))
                                        , p("option", string("상품 이름. 사이트가 달라도 같은 상품이면 동일하게 (예: 'Hotel Nikko Fukuoka 디럭스 트윈')"))
                                        , p("site", string("사이트 이름 (예: Agoda)"))
                                        , p("total_price_krw", number("3인 가족 총액 (원)"))
                                        , p("original_price", string("사이트 표기 원래 가격 (예: ¥48,000 / 1박)"))
                                        , p("url", string("가격을 확인한 페이지 또는 booking_search_links 링크"))
                                        , p("checked_at", date("가격 확인 날짜"))
                                        , p("notes", string("무료취소, 조식, 아기침대, 수하물, 유아 요금 등 조건"))),
                                List.of("category", "option", "site", "total_price_krw", "original_price", "url",
                                        "checked_at", "notes")))),
                        p("recommendation", string("최종 추천과 이유 (가격뿐 아니라 아이 동반 편의성 포함)"))),
                List.of("title", "quotes", "recommendation"));
    }

    public record Quote(String category, String option, String site, Double total_price_krw, String original_price,
            String url, String checked_at, String notes) {
    }

    record Input(String title, List<Quote> quotes, String recommendation) {
    }

    /** 상품(option)별 최저가 요약. */
    public record Best(String option, String cheapestSite, double cheapestKrw, double mostExpensiveKrw,
            int siteCount) {
    }

    public record Comparison(String title, List<Quote> quotes, List<Best> bestByOption, String recommendation) {
    }

    @Override
    public ToolResult execute(JsonValue rawInput) {
        Input in;
        try {
            in = rawInput.convert(Input.class);
        } catch (RuntimeException e) {
            return ToolResult.error("입력을 해석할 수 없습니다: " + e.getMessage());
        }
        if (in.quotes() == null || in.quotes().isEmpty()) {
            return ToolResult.error("quotes 가 비어 있습니다.");
        }
        List<String> errors = new ArrayList<>();
        for (Quote quote : in.quotes()) {
            if (quote.total_price_krw() == null || quote.total_price_krw() <= 0) {
                errors.add(quote.site() + " / " + quote.option() + ": total_price_krw 가 0 이하이거나 없습니다.");
            }
            if (quote.option() == null || quote.option().isBlank()
                    || quote.site() == null || quote.site().isBlank() || quote.category() == null) {
                errors.add(quote.site() + " / " + quote.option() + ": category, option, site 는 비어 있을 수 없습니다.");
            }
        }
        if (!errors.isEmpty()) {
            return ToolResult.error("비교표를 저장하지 못했습니다:\n- " + String.join("\n- ", errors));
        }

        List<Quote> sorted = in.quotes().stream()
                .sorted(Comparator.comparing(Quote::category).thenComparing(Quote::option)
                        .thenComparing(Quote::total_price_krw))
                .toList();
        List<Best> best = bestByOption(sorted);

        StringBuilder summary = new StringBuilder("가격 비교표를 저장했습니다. 상품별 최저가:\n");
        for (Best b : best) {
            summary.append("- %s: %s %,.0f원 (사이트 %d곳 비교, 최고가 대비 %,.0f원 저렴)%n"
                    .formatted(b.option(), b.cheapestSite(), b.cheapestKrw(), b.siteCount(),
                            b.mostExpensiveKrw() - b.cheapestKrw()));
        }
        best.stream().filter(b -> b.siteCount() < 2).forEach(b -> summary
                .append("경고: '").append(b.option()).append("' 은 사이트 1곳만 조사했습니다. 가능하면 다른 사이트도 확인하세요.\n"));

        return ToolResult.ok(summary.toString(),
                new ToolResult.UiEvent("comparison", new Comparison(in.title(), sorted, best, in.recommendation())));
    }

    static List<Best> bestByOption(List<Quote> quotes) {
        Map<String, List<Quote>> byOption = new LinkedHashMap<>();
        for (Quote quote : quotes) {
            byOption.computeIfAbsent(quote.option(), key -> new ArrayList<>()).add(quote);
        }
        List<Best> result = new ArrayList<>();
        byOption.forEach((option, list) -> {
            Quote cheapest = list.stream().min(Comparator.comparing(Quote::total_price_krw)).orElseThrow();
            double max = list.stream().mapToDouble(Quote::total_price_krw).max().orElseThrow();
            long sites = list.stream().map(Quote::site).distinct().count();
            result.add(new Best(option, cheapest.site(), cheapest.total_price_krw(), max, (int) sites));
        });
        return result;
    }
}
