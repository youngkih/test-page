package com.example.travelagent.tool;

import static com.example.travelagent.tool.ToolSchemas.date;
import static com.example.travelagent.tool.ToolSchemas.enumOf;
import static com.example.travelagent.tool.ToolSchemas.p;
import static com.example.travelagent.tool.ToolSchemas.props;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 여행 날짜별 요일과 현지 공휴일을 알려준다.
 *
 * <p>LLM 은 "2026-10-14 가 무슨 요일인지" 같은 날짜 계산을 틀리기 쉽다.
 * 식당 정기휴무(예: 수요일 휴무)를 피하려면 요일이 정확해야 하므로, 계산은 코드가 한다.
 */
@Component
public class TripCalendarTool implements AgentTool {

    /** 일본 공휴일 (대체휴일 포함). 매년 초 내각부 발표 기준으로 갱신 필요. */
    static final Map<LocalDate, String> JAPAN_HOLIDAYS = Map.ofEntries(
            Map.entry(LocalDate.of(2026, 1, 1), "元日 (설날)"),
            Map.entry(LocalDate.of(2026, 1, 12), "成人の日 (성인의 날)"),
            Map.entry(LocalDate.of(2026, 2, 11), "建国記念の日 (건국기념일)"),
            Map.entry(LocalDate.of(2026, 2, 23), "天皇誕生日 (천황탄생일)"),
            Map.entry(LocalDate.of(2026, 3, 20), "春分の日 (춘분)"),
            Map.entry(LocalDate.of(2026, 4, 29), "昭和の日 (쇼와의 날)"),
            Map.entry(LocalDate.of(2026, 5, 3), "憲法記念日 (헌법기념일)"),
            Map.entry(LocalDate.of(2026, 5, 4), "みどりの日 (녹색의 날)"),
            Map.entry(LocalDate.of(2026, 5, 5), "こどもの日 (어린이날)"),
            Map.entry(LocalDate.of(2026, 5, 6), "振替休日 (대체휴일)"),
            Map.entry(LocalDate.of(2026, 7, 20), "海の日 (바다의 날)"),
            Map.entry(LocalDate.of(2026, 8, 11), "山の日 (산의 날)"),
            Map.entry(LocalDate.of(2026, 9, 21), "敬老の日 (경로의 날)"),
            Map.entry(LocalDate.of(2026, 9, 22), "国民の休日 (국민의 휴일)"),
            Map.entry(LocalDate.of(2026, 9, 23), "秋分の日 (추분)"),
            Map.entry(LocalDate.of(2026, 10, 12), "スポーツの日 (체육의 날)"),
            Map.entry(LocalDate.of(2026, 11, 3), "文化の日 (문화의 날)"),
            Map.entry(LocalDate.of(2026, 11, 23), "勤労感謝の日 (근로감사의 날)"),
            Map.entry(LocalDate.of(2027, 1, 1), "元日 (설날)"),
            Map.entry(LocalDate.of(2027, 1, 11), "成人の日 (성인의 날)"),
            Map.entry(LocalDate.of(2027, 2, 11), "建国記念の日 (건국기념일)"),
            Map.entry(LocalDate.of(2027, 2, 23), "天皇誕生日 (천황탄생일)"));

    private static final int MAX_DAYS = 31;

    private final ObjectMapper objectMapper;

    public TripCalendarTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "trip_calendar";
    }

    @Override
    public Tool definition() {
        return ToolSchemas.tool(name(),
                """
                여행 기간의 날짜별 요일, 주말 여부, 일본 공휴일을 정확히 계산해 준다. \
                식당·가게의 정기휴무일과 방문 요일을 대조하기 전에, 그리고 일정표를 만들기 전에 반드시 호출할 것. \
                요일을 머릿속으로 계산하지 말 것.""",
                props(
                        p("start_date", date("여행 시작일")),
                        p("end_date", date("여행 종료일")),
                        p("country", enumOf("공휴일 기준 국가", List.of("JP", "OTHER")))),
                List.of("start_date", "end_date", "country"));
    }

    record Input(String start_date, String end_date, String country) {
    }

    record Day(String date, String dayOfWeek, boolean weekend, String holiday) {
    }

    @Override
    public ToolResult execute(JsonValue rawInput) {
        LocalDate start;
        LocalDate end;
        Input in;
        try {
            in = rawInput.convert(Input.class);
            start = LocalDate.parse(in.start_date());
            end = LocalDate.parse(in.end_date());
        } catch (RuntimeException e) {
            return ToolResult.error("입력을 해석할 수 없습니다 (날짜는 yyyy-MM-dd): " + e.getMessage());
        }
        if (end.isBefore(start)) {
            return ToolResult.error("end_date 가 start_date 보다 앞설 수 없습니다.");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            return ToolResult.error("최대 " + MAX_DAYS + "일까지만 조회할 수 있습니다.");
        }

        boolean japan = "JP".equals(in.country());
        List<Day> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            days.add(new Day(d.toString(),
                    dow.getDisplayName(TextStyle.FULL, Locale.KOREAN),
                    dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY,
                    japan ? JAPAN_HOLIDAYS.get(d) : null));
        }
        try {
            String json = objectMapper.writeValueAsString(days);
            String note = japan && !isCovered(start, end)
                    ? "\n참고: 이 기간의 일본 공휴일 데이터가 없습니다. 공휴일 여부는 웹 검색으로 확인하세요."
                    : "";
            return ToolResult.ok(json + note);
        } catch (Exception e) {
            return ToolResult.error("결과 직렬화 실패: " + e.getMessage());
        }
    }

    private static boolean isCovered(LocalDate start, LocalDate end) {
        LocalDate first = LocalDate.of(2026, 1, 1);
        LocalDate last = LocalDate.of(2027, 2, 28);
        return !start.isBefore(first) && !end.isAfter(last);
    }
}
