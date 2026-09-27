package com.example.travelagent.tool;

import static com.example.travelagent.tool.ToolSchemas.arrayOf;
import static com.example.travelagent.tool.ToolSchemas.date;
import static com.example.travelagent.tool.ToolSchemas.enumOf;
import static com.example.travelagent.tool.ToolSchemas.integer;
import static com.example.travelagent.tool.ToolSchemas.p;
import static com.example.travelagent.tool.ToolSchemas.props;
import static com.example.travelagent.tool.ToolSchemas.string;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.example.travelagent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 여러 예약 사이트의 "검색 결과 페이지" 링크를 날짜/인원이 채워진 상태로 만들어 준다.
 *
 * <p>대부분의 예약 사이트(Skyscanner, Agoda, Airbnb 등)는 개인 개발자에게 예약 API 를 열어주지 않는다.
 * 그래서 에이전트는 (1) 웹 검색으로 가격을 조사하고 (2) 이 도구로 만든 링크를 사용자에게 주어
 * 사용자가 직접 클릭해서 최종 가격 확인·예약을 하도록 한다. 결제는 절대 에이전트가 하지 않는다.
 *
 * <p>사이트 URL 형식은 예고 없이 바뀔 수 있다. 링크가 깨지면 이 클래스만 고치면 된다.
 */
@Component
public class BookingLinksTool implements AgentTool {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ObjectMapper objectMapper;
    private final String defaultOriginAirport;

    public BookingLinksTool(ObjectMapper objectMapper, AgentProperties properties) {
        this.objectMapper = objectMapper;
        this.defaultOriginAirport = properties.family() != null && properties.family().homeAirport() != null
                ? properties.family().homeAirport()
                : "ICN";
    }

    @Override
    public String name() {
        return "booking_search_links";
    }

    @Override
    public Tool definition() {
        return ToolSchemas.tool(name(),
                """
                여러 예약 사이트(항공: 스카이스캐너·네이버항공·구글플라이트 / 숙소: 아고다·부킹닷컴·호텔스닷컴·익스피디아·에어비앤비 \
                / 액티비티: 클룩·KKday·마이리얼트립·트립어드바이저)의 검색 결과 페이지 링크를 날짜와 인원을 채워 만들어 준다. \
                가격 비교표나 최종 추천안을 사용자에게 줄 때, 사용자가 직접 가격을 재확인하고 예약할 수 있도록 이 링크를 함께 제공할 것. \
                이 도구는 가격을 조회하지 않는다 (가격 조사는 web_search/web_fetch 로).""",
                props(
                        p("category", enumOf("링크 종류", List.of("flight", "stay", "activity"))),
                        p("destination", string("도시/지역 이름. 영문 권장 (예: Fukuoka, Osaka Namba)")),
                        p("destination_airport", string("flight 일 때 도착 공항 IATA 코드 (예: FUK, KIX)")),
                        p("origin_airport", string("flight 일 때 출발 공항 IATA 코드. 생략 시 가족 프로필의 공항")),
                        p("start_date", date("가는 날 / 체크인")),
                        p("end_date", date("오는 날 / 체크아웃")),
                        p("adults", integer("성인 수")),
                        p("child_ages", arrayOf("아이 나이 목록(만 나이). 14개월이면 1", integer("만 나이"))),
                        p("keyword", string("activity 일 때 검색어 (예: 'Fukuoka aquarium'). 생략 시 destination"))),
                List.of("category", "destination", "start_date", "end_date", "adults", "child_ages"));
    }

    record Input(String category, String destination, String destination_airport, String origin_airport,
            String start_date, String end_date, Integer adults, List<Integer> child_ages, String keyword) {
    }

    record Link(String site, String url, String note) {
    }

    @Override
    public ToolResult execute(JsonValue rawInput) {
        Input in;
        LocalDate start;
        LocalDate end;
        try {
            in = rawInput.convert(Input.class);
            start = LocalDate.parse(in.start_date());
            end = LocalDate.parse(in.end_date());
        } catch (RuntimeException e) {
            return ToolResult.error("입력을 해석할 수 없습니다: " + e.getMessage());
        }
        if (in.destination() == null || in.destination().isBlank()) {
            return ToolResult.error("destination 은 필수입니다.");
        }
        if (!end.isAfter(start)) {
            return ToolResult.error("end_date 는 start_date 보다 뒤여야 합니다.");
        }
        int adults = in.adults() == null || in.adults() < 1 ? 1 : in.adults();
        List<Integer> childAges = in.child_ages() == null ? List.of() : in.child_ages();

        List<Link> links = switch (in.category() == null ? "" : in.category()) {
            case "flight" -> {
                if (in.destination_airport() == null || in.destination_airport().isBlank()) {
                    yield null;
                }
                String origin = in.origin_airport() == null || in.origin_airport().isBlank()
                        ? defaultOriginAirport
                        : in.origin_airport();
                yield flightLinks(origin.toUpperCase(Locale.ROOT), in.destination_airport().toUpperCase(Locale.ROOT),
                        start, end, adults, childAges);
            }
            case "stay" -> stayLinks(in.destination(), start, end, adults, childAges);
            case "activity" -> activityLinks(in.keyword() == null || in.keyword().isBlank()
                    ? in.destination()
                    : in.keyword());
            default -> List.of();
        };
        if (links == null) {
            return ToolResult.error("category 가 flight 이면 destination_airport(IATA 코드)가 필요합니다.");
        }
        if (links.isEmpty()) {
            return ToolResult.error("category 는 flight, stay, activity 중 하나여야 합니다.");
        }
        try {
            return ToolResult.ok(objectMapper.writeValueAsString(links));
        } catch (Exception e) {
            return ToolResult.error("결과 직렬화 실패: " + e.getMessage());
        }
    }

    List<Link> flightLinks(String from, String to, LocalDate start, LocalDate end, int adults, List<Integer> childAges) {
        long infants = childAges.stream().filter(age -> age < 2).count();
        long children = childAges.size() - infants;
        String skyscannerChildren = childAges.isEmpty() ? ""
                : "&childrenv2=" + childAges.stream().map(String::valueOf).collect(Collectors.joining("%7C"));

        List<Link> links = new ArrayList<>();
        links.add(new Link("Skyscanner",
                "https://www.skyscanner.co.kr/transport/flights/%s/%s/%s/%s/?adultsv2=%d%s&cabinclass=economy"
                        .formatted(from.toLowerCase(Locale.ROOT), to.toLowerCase(Locale.ROOT),
                                start.format(YYMMDD), end.format(YYMMDD), adults, skyscannerChildren),
                "여러 항공사·OTA 가격을 한 번에 비교"));
        links.add(new Link("네이버 항공권",
                "https://flight.naver.com/flights/international/%s-%s-%s/%s-%s-%s?adult=%d&child=%d&infant=%d&fareType=Y"
                        .formatted(from, to, start.format(YYYYMMDD), to, from, end.format(YYYYMMDD),
                                adults, children, infants),
                "국내 여행사 요금 비교, 유아 요금 확인 쉬움"));
        links.add(new Link("Google Flights",
                "https://www.google.com/travel/flights?hl=ko&q="
                        + encode("Flights from %s to %s on %s through %s".formatted(from, to, start, end)),
                "날짜별 최저가 그래프 확인용"));
        return links;
    }

    List<Link> stayLinks(String destination, LocalDate start, LocalDate end, int adults, List<Integer> childAges) {
        String dest = encode(destination);
        long nights = ChronoUnit.DAYS.between(start, end);
        String ages = childAges.stream().map(String::valueOf).collect(Collectors.joining(","));
        String bookingAges = childAges.stream().map(age -> "&age=" + age).collect(Collectors.joining());
        String expediaChildren = childAges.stream().map(age -> "1_" + age).collect(Collectors.joining(","));

        List<Link> links = new ArrayList<>();
        links.add(new Link("Agoda",
                "https://www.agoda.com/ko-kr/search?textToSearch=%s&checkIn=%s&los=%d&rooms=1&adults=%d&children=%d%s"
                        .formatted(dest, start, nights, adults, childAges.size(),
                                childAges.isEmpty() ? "" : "&childAges=" + encode(ages)),
                "아시아 숙소 최저가가 자주 나옴"));
        links.add(new Link("Booking.com",
                "https://www.booking.com/searchresults.ko.html?ss=%s&checkin=%s&checkout=%s&group_adults=%d&group_children=%d%s&no_rooms=1"
                        .formatted(dest, start, end, adults, childAges.size(), bookingAges),
                "무료 취소 옵션 필터가 편리"));
        links.add(new Link("Hotels.com",
                "https://kr.hotels.com/Hotel-Search?destination=%s&startDate=%s&endDate=%s&adults=%d%s"
                        .formatted(dest, start, end, adults,
                                childAges.isEmpty() ? "" : "&children=" + encode(expediaChildren)),
                "적립(리워드) 확인"));
        links.add(new Link("Expedia",
                "https://www.expedia.co.kr/Hotel-Search?destination=%s&startDate=%s&endDate=%s&adults=%d%s"
                        .formatted(dest, start, end, adults,
                                childAges.isEmpty() ? "" : "&children=" + encode(expediaChildren)),
                "항공+호텔 묶음 할인 확인"));
        links.add(new Link("Airbnb",
                "https://www.airbnb.co.kr/s/%s/homes?checkin=%s&checkout=%s&adults=%d&infants=%d&children=%d"
                        .formatted(dest, start, end, adults,
                                childAges.stream().filter(age -> age < 2).count(),
                                childAges.stream().filter(age -> age >= 2).count()),
                "주방·세탁기 있는 집 (이유식/빨래에 유리)"));
        links.add(new Link("Trip.com",
                "https://kr.trip.com/hotels/list?keyword=%s&checkin=%s&checkout=%s&adult=%d&children=%d%s"
                        .formatted(dest, start.toString().replace("-", "/"), end.toString().replace("-", "/"),
                                adults, childAges.size(), childAges.isEmpty() ? "" : "&ages=" + encode(ages)),
                "중화권/일본 숙소 특가가 종종 있음"));
        return links;
    }

    List<Link> activityLinks(String keyword) {
        String q = encode(keyword);
        return List.of(
                new Link("Klook", "https://www.klook.com/ko/search/result/?query=" + q, "입장권·교통패스 할인"),
                new Link("KKday", "https://www.kkday.com/ko/product/productlist?keyword=" + q, "입장권·투어"),
                new Link("마이리얼트립", "https://www.myrealtrip.com/search?q=" + q, "한국어 가이드 투어"),
                new Link("Tripadvisor", "https://www.tripadvisor.co.kr/Search?q=" + q, "리뷰 확인용"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
