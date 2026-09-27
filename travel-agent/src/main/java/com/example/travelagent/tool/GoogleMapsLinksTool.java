package com.example.travelagent.tool;

import static com.example.travelagent.tool.ToolSchemas.arrayOf;
import static com.example.travelagent.tool.ToolSchemas.enumOf;
import static com.example.travelagent.tool.ToolSchemas.object;
import static com.example.travelagent.tool.ToolSchemas.p;
import static com.example.travelagent.tool.ToolSchemas.props;
import static com.example.travelagent.tool.ToolSchemas.string;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.stereotype.Component;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Google Maps 공식 URL 규칙(Maps URLs, api=1)으로 장소 검색 링크와 길찾기 링크를 만든다.
 * API 키가 필요 없고, 모바일에서 누르면 구글 지도 앱으로 바로 열린다.
 */
@Component
public class GoogleMapsLinksTool implements AgentTool {

    private final ObjectMapper objectMapper;

    public GoogleMapsLinksTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "google_maps_links";
    }

    @Override
    public Tool definition() {
        return ToolSchemas.tool(name(),
                """
                식당·놀이터·쇼핑몰 등 장소의 Google 지도 링크와, 장소 간 길찾기 링크를 만든다. \
                사용자에게 장소를 추천하거나 일정표를 저장하기 전에 반드시 이 도구로 링크를 만들어 함께 제공할 것. \
                링크 정확도를 위해 query 에는 '가게 이름 + 지점 + 도시'처럼 구체적으로 적을 것 (예: 'Ichiran Tenjin Nishi-dori Fukuoka').""",
                props(
                        p("places", arrayOf("지도 링크가 필요한 장소 목록", object(
                                props(p("name", string("화면에 보일 이름 (한국어 가능)")),
                                        p("query", string("지도 검색어. 가게 이름+지점+도시, 또는 주소"))),
                                List.of("name", "query")))),
                        p("routes", arrayOf("길찾기 링크가 필요한 구간 (없으면 빈 배열)", object(
                                props(p("from", string("출발지 검색어")),
                                        p("to", string("도착지 검색어")),
                                        p("mode", enumOf("이동 수단", List.of("transit", "walking", "driving")))),
                                List.of("from", "to", "mode"))))),
                List.of("places", "routes"));
    }

    record Place(String name, String query) {
    }

    record Route(String from, String to, String mode) {
    }

    record Input(List<Place> places, List<Route> routes) {
    }

    record PlaceLink(String name, String mapsUrl) {
    }

    record RouteLink(String from, String to, String mode, String directionsUrl) {
    }

    record Output(List<PlaceLink> places, List<RouteLink> routes) {
    }

    @Override
    public ToolResult execute(JsonValue rawInput) {
        Input in;
        try {
            in = rawInput.convert(Input.class);
        } catch (RuntimeException e) {
            return ToolResult.error("입력을 해석할 수 없습니다: " + e.getMessage());
        }
        List<Place> places = in.places() == null ? List.of() : in.places();
        List<Route> routes = in.routes() == null ? List.of() : in.routes();
        if (places.isEmpty() && routes.isEmpty()) {
            return ToolResult.error("places 또는 routes 중 하나 이상은 비어 있지 않아야 합니다.");
        }
        for (Place place : places) {
            if (place.query() == null || place.query().isBlank()) {
                return ToolResult.error("places[].query 는 비어 있을 수 없습니다: " + place.name());
            }
        }

        Output output = new Output(
                places.stream().map(place -> new PlaceLink(place.name(), placeUrl(place.query()))).toList(),
                routes.stream().map(route -> new RouteLink(route.from(), route.to(), route.mode(),
                        directionsUrl(route.from(), route.to(), route.mode()))).toList());
        try {
            return ToolResult.ok(objectMapper.writeValueAsString(output));
        } catch (Exception e) {
            return ToolResult.error("결과 직렬화 실패: " + e.getMessage());
        }
    }

    public static String placeUrl(String query) {
        return "https://www.google.com/maps/search/?api=1&query=" + encode(query);
    }

    static String directionsUrl(String from, String to, String mode) {
        String travelMode = switch (mode == null ? "" : mode) {
            case "walking", "driving" -> mode;
            default -> "transit";
        };
        return "https://www.google.com/maps/dir/?api=1&origin=%s&destination=%s&travelmode=%s"
                .formatted(encode(from), encode(to), travelMode);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
