package com.example.travelagent.tool;

import static com.example.travelagent.tool.ToolSchemas.arrayOf;
import static com.example.travelagent.tool.ToolSchemas.date;
import static com.example.travelagent.tool.ToolSchemas.enumOf;
import static com.example.travelagent.tool.ToolSchemas.object;
import static com.example.travelagent.tool.ToolSchemas.p;
import static com.example.travelagent.tool.ToolSchemas.props;
import static com.example.travelagent.tool.ToolSchemas.string;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

/**
 * 최종 여행 일정표를 구조화된 형태로 저장한다. 웹 화면의 "일정표" 탭에 표시된다.
 *
 * <p>단순 저장이 아니라 요구사항을 코드로 검사하는 "가드레일" 역할도 한다.
 * 예: 매일 아이 놀이 시간 1시간 이상, 식당에는 지도 링크 필수.
 * 규칙을 어기면 경고를 돌려주고, 모델은 그 경고를 보고 일정을 스스로 고친다.
 */
@Component
public class SaveItineraryTool implements AgentTool {

    static final List<String> CATEGORIES = List.of(
            "flight", "transport", "lodging", "meal", "kids_play", "shopping", "sightseeing", "rest", "other");

    /** 아이 놀이 최소 시간 (분). 도착일/출발일처럼 이동이 많은 날은 경고만 한다. */
    static final long MIN_KIDS_PLAY_MINUTES = 60;

    @Override
    public String name() {
        return "save_itinerary";
    }

    @Override
    public Tool definition() {
        return ToolSchemas.tool(name(),
                """
                확정된(또는 사용자에게 제안할) 시간대별 여행 일정표를 저장해 화면에 표시한다. \
                반드시 trip_calendar 와 google_maps_links 를 먼저 호출해 요일과 지도 링크를 확인한 뒤 저장할 것. \
                저장 결과로 경고(warnings)가 오면 일정을 수정해서 다시 저장할 것. \
                사용자가 일정 변경을 요청하면 전체 일정을 다시 저장한다 (부분 수정 불가).""",
                props(
                        p("title", string("일정표 제목 (예: 후쿠오카 3박 4일 가족여행)")),
                        p("destination", string("여행지")),
                        p("start_date", date("여행 시작일")),
                        p("end_date", date("여행 종료일")),
                        p("summary", string("일정 전체 요약 2~3문장 (숙소 위치, 이동 전략 등)")),
                        p("days", arrayOf("날짜별 일정", object(
                                props(p("date", date("날짜")),
                                        p("theme", string("그날의 테마 (예: 도착 & 숙소 주변 산책)")),
                                        p("items", arrayOf("시간순 일정 항목", object(
                                                props(p("start", string("시작 시각 HH:mm")),
                                                        p("end", string("종료 시각 HH:mm")),
                                                        p("category", enumOf("항목 종류", CATEGORIES)),
                                                        p("title", string("무엇을 하는지")),
                                                        p("place", string("장소 이름 (없으면 빈 문자열)")),
                                                        p("maps_url", string("google_maps_links 로 만든 링크 (없으면 빈 문자열)")),
                                                        p("how", string("가는 방법/소요 시간, 예약 필요 여부 등")),
                                                        p("kid_notes", string("아이 관련 메모: 유아의자, 수유실, 유모차 가능 여부, 낮잠 등")),
                                                        p("closed_days", string("해당 장소 정기휴무 (확인했으면 기재, 모르면 '미확인')"))),
                                                List.of("start", "end", "category", "title", "place", "maps_url", "how",
                                                        "kid_notes", "closed_days"))))),
                                List.of("date", "theme", "items")))),
                        p("checklist", arrayOf("출발 전 준비물/할 일", string("항목")))),
                List.of("title", "destination", "start_date", "end_date", "summary", "days", "checklist"));
    }

    public record Item(String start, String end, String category, String title, String place, String maps_url,
            String how, String kid_notes, String closed_days) {
    }

    public record Day(String date, String theme, List<Item> items) {
    }

    public record Itinerary(String title, String destination, String start_date, String end_date, String summary,
            List<Day> days, List<String> checklist) {
    }

    @Override
    public ToolResult execute(JsonValue rawInput) {
        Itinerary itinerary;
        try {
            itinerary = rawInput.convert(Itinerary.class);
        } catch (RuntimeException e) {
            return ToolResult.error("입력을 해석할 수 없습니다: " + e.getMessage());
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        validate(itinerary, errors, warnings);
        if (!errors.isEmpty()) {
            return ToolResult.error("일정표를 저장하지 못했습니다. 아래 문제를 고쳐서 다시 호출하세요:\n- "
                    + String.join("\n- ", errors));
        }

        String message = warnings.isEmpty()
                ? "일정표를 저장했습니다. 경고 없음."
                : "일정표를 저장했지만 확인이 필요합니다. 가능하면 수정해서 다시 저장하고, "
                        + "수정이 불가능하면 이유를 사용자에게 설명하세요:\n- " + String.join("\n- ", warnings);
        return ToolResult.ok(message, new ToolResult.UiEvent("itinerary", itinerary));
    }

    void validate(Itinerary itinerary, List<String> errors, List<String> warnings) {
        LocalDate tripStart;
        LocalDate tripEnd;
        try {
            tripStart = LocalDate.parse(itinerary.start_date());
            tripEnd = LocalDate.parse(itinerary.end_date());
        } catch (RuntimeException e) {
            errors.add("start_date/end_date 는 yyyy-MM-dd 형식이어야 합니다.");
            return;
        }
        if (itinerary.days() == null || itinerary.days().isEmpty()) {
            errors.add("days 가 비어 있습니다.");
            return;
        }

        for (int dayIndex = 0; dayIndex < itinerary.days().size(); dayIndex++) {
            Day day = itinerary.days().get(dayIndex);
            LocalDate date;
            try {
                date = LocalDate.parse(day.date());
            } catch (RuntimeException e) {
                errors.add("days[" + dayIndex + "].date 형식 오류: " + day.date());
                continue;
            }
            if (date.isBefore(tripStart) || date.isAfter(tripEnd)) {
                errors.add(day.date() + " 은 여행 기간(" + tripStart + " ~ " + tripEnd + ") 밖입니다.");
            }
            List<Item> items = day.items() == null ? List.of() : day.items();
            if (items.isEmpty()) {
                errors.add(day.date() + " 에 일정 항목이 없습니다.");
                continue;
            }

            long kidsPlayMinutes = 0;
            LocalTime previousStart = null;
            for (Item item : items) {
                String label = day.date() + " " + item.start() + " '" + item.title() + "'";
                LocalTime start;
                LocalTime end;
                try {
                    start = LocalTime.parse(item.start());
                    end = LocalTime.parse(item.end());
                } catch (DateTimeParseException | NullPointerException e) {
                    errors.add(label + ": start/end 는 HH:mm 형식이어야 합니다.");
                    continue;
                }
                if (!CATEGORIES.contains(item.category())) {
                    errors.add(label + ": 알 수 없는 category '" + item.category() + "'");
                }
                if (previousStart != null && start.isBefore(previousStart)) {
                    warnings.add(label + ": 시간순으로 정렬되어 있지 않습니다.");
                }
                previousStart = start;

                if ("kids_play".equals(item.category()) && end.isAfter(start)) {
                    kidsPlayMinutes += Duration.between(start, end).toMinutes();
                }
                if ("meal".equals(item.category()) && isBlank(item.maps_url())) {
                    warnings.add(label + ": 식당에 Google 지도 링크(maps_url)가 없습니다.");
                }
                if (("meal".equals(item.category()) || "kids_play".equals(item.category())
                        || "shopping".equals(item.category()))
                        && (isBlank(item.closed_days()) || item.closed_days().contains("미확인"))) {
                    warnings.add(label + ": 정기휴무/영업일을 확인하지 않았습니다.");
                }
            }

            boolean travelDay = date.equals(tripStart) || date.equals(tripEnd);
            if (kidsPlayMinutes < MIN_KIDS_PLAY_MINUTES) {
                String msg = day.date() + ": 아이 놀이 시간(kids_play)이 " + kidsPlayMinutes + "분입니다. "
                        + "하루 60~120분(놀이터, 키즈카페, 공원 등)을 넣어 주세요.";
                if (travelDay) {
                    msg += " (이동일이라 짧아도 되지만 공항/숙소 근처 놀이 공간을 고려해 보세요.)";
                }
                warnings.add(msg);
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
