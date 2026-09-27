package com.example.travelagent.telegram;

import static org.springframework.web.util.HtmlUtils.htmlEscape;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.travelagent.tool.SaveItineraryTool;
import com.example.travelagent.tool.SavePriceComparisonTool;

/**
 * 일정표·가격 비교표를 폰에서 열어볼 수 있는 HTML 파일로 만든다 (텔레그램 첨부 파일용).
 * 모델이 만든 문자열은 모두 이스케이프하고, 링크는 http(s) 만 허용한다.
 */
@Component
public class ReportRenderer {

    private static final Map<String, String> ICONS = Map.of(
            "flight", "✈️", "transport", "🚃", "lodging", "🏨", "meal", "🍽️", "kids_play", "🛝",
            "shopping", "🛍️", "sightseeing", "📸", "rest", "😴", "other", "📌");

    private static final String STYLE = """
            body{font-family:-apple-system,"Apple SD Gothic Neo","Noto Sans KR",sans-serif;margin:0;padding:16px;\
            line-height:1.5;color:#1d2330;background:#f6f7f9}
            h1{font-size:1.25rem;margin:0 0 4px}h2{font-size:1rem;margin:0;padding:10px 12px;background:#e8f0fe}
            .muted{color:#6b7280}.day{background:#fff;border:1px solid #e3e6eb;border-radius:10px;margin:16px 0;overflow:hidden}
            .item{padding:10px 12px;border-top:1px solid #e3e6eb}.kids_play{background:#e5f5ec}
            .time{color:#6b7280;font-size:.9rem}.title{font-weight:600}.meta{font-size:.9rem;color:#4b5563}
            .warn{color:#9a5b00}a{color:#2f6fed;word-break:break-all}
            table{width:100%;border-collapse:collapse;background:#fff;font-size:.9rem}
            th,td{border-bottom:1px solid #e3e6eb;padding:6px;text-align:left;vertical-align:top}
            td.price{text-align:right;white-space:nowrap}.best td{background:#e5f5ec}
            .rec{background:#e8f0fe;padding:12px;border-radius:10px;margin-top:16px}
            @media (prefers-color-scheme:dark){body{background:#14171c;color:#e7e9ee}.day,table{background:#1d2129;border-color:#2d333d}
            h2,.rec{background:#23304a}.kids_play,.best td{background:#193427}.meta,.muted,.time{color:#9aa1ad}a{color:#6d9bff}}
            """;

    public String itinerary(SaveItineraryTool.Itinerary it) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(esc(it.title())).append("</h1>");
        body.append("<p class=\"muted\">").append(esc(it.summary())).append("</p>");
        for (SaveItineraryTool.Day day : nullSafe(it.days())) {
            body.append("<section class=\"day\"><h2>").append(esc(day.date())).append(" · ")
                    .append(esc(day.theme())).append("</h2>");
            for (SaveItineraryTool.Item item : nullSafe(day.items())) {
                String category = item.category() == null ? "other" : item.category();
                body.append("<div class=\"item ").append(esc(category)).append("\">")
                        .append("<div class=\"time\">").append(esc(item.start())).append("–")
                        .append(esc(item.end())).append("</div>")
                        .append("<div class=\"title\">").append(ICONS.getOrDefault(category, "📌")).append(' ')
                        .append(esc(item.title())).append("</div>");
                if (notBlank(item.place())) {
                    body.append("<div class=\"meta\">📍 ").append(link(item.maps_url(), item.place())).append("</div>");
                }
                meta(body, "🚶", item.how(), "meta");
                meta(body, "👶", item.kid_notes(), "meta");
                meta(body, "🗓️ 휴무:", item.closed_days(), "meta warn");
                body.append("</div>");
            }
            body.append("</section>");
        }
        if (!nullSafe(it.checklist()).isEmpty()) {
            body.append("<h2>✅ 출발 전 체크리스트</h2><ul>");
            it.checklist().forEach(c -> body.append("<li>").append(esc(c)).append("</li>"));
            body.append("</ul>");
        }
        return page(it.title(), body.toString());
    }

    public String comparison(SavePriceComparisonTool.Comparison cmp) {
        StringBuilder body = new StringBuilder();
        body.append("<h1>").append(esc(cmp.title())).append("</h1>");
        body.append("<div style=\"overflow-x:auto\"><table><tr><th>상품</th><th>사이트</th><th>총액(3인)</th>")
                .append("<th>표기 가격</th><th>조건</th><th>확인일</th></tr>");
        for (SavePriceComparisonTool.Quote q : nullSafe(cmp.quotes())) {
            boolean best = nullSafe(cmp.bestByOption()).stream().anyMatch(b -> b.siteCount() > 1
                    && b.option().equals(q.option()) && b.cheapestSite().equals(q.site())
                    && b.cheapestKrw() == q.total_price_krw());
            body.append(best ? "<tr class=\"best\">" : "<tr>")
                    .append("<td>").append(esc(q.option())).append("</td>")
                    .append("<td>").append(link(q.url(), q.site())).append(best ? " 🏆" : "").append("</td>")
                    .append("<td class=\"price\">")
                    .append(String.format(Locale.KOREA, "%,.0f원", q.total_price_krw())).append("</td>")
                    .append("<td>").append(esc(q.original_price())).append("</td>")
                    .append("<td>").append(esc(q.notes())).append("</td>")
                    .append("<td>").append(esc(q.checked_at())).append("</td></tr>");
        }
        body.append("</table></div>");
        if (notBlank(cmp.recommendation())) {
            body.append("<div class=\"rec\"><b>👍 추천:</b> ").append(esc(cmp.recommendation())).append("</div>");
        }
        body.append("<p class=\"muted\">※ 웹에서 조사한 시점의 참고 가격입니다. 예약 전 링크에서 최종 가격을 꼭 확인하세요.</p>");
        return page(cmp.title(), body.toString());
    }

    private static void meta(StringBuilder body, String icon, String text, String cssClass) {
        if (notBlank(text)) {
            body.append("<div class=\"").append(cssClass).append("\">").append(icon).append(' ')
                    .append(esc(text)).append("</div>");
        }
    }

    private static String page(String title, String body) {
        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + esc(title) + "</title><style>" + STYLE + "</style></head><body>"
                + body + "</body></html>";
    }

    static String link(String url, String label) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return esc(label);
        }
        return "<a href=\"" + esc(url) + "\">" + esc(label) + "</a>";
    }

    private static String esc(String text) {
        return text == null ? "" : htmlEscape(text, "UTF-8");
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
