package com.example.travelagent.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.example.travelagent.config.AgentProperties;

/** prompts/system-prompt.md 템플릿에 가족 프로필과 오늘 날짜를 채워 넣는다. */
@Component
public class SystemPromptFactory {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final String template;
    private final AgentProperties properties;
    private final Clock clock;

    public SystemPromptFactory(AgentProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        try {
            this.template = new ClassPathResource("prompts/system-prompt.md")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("시스템 프롬프트를 읽을 수 없습니다", e);
        }
    }

    public String build() {
        // 날짜만 넣는다 (시각을 넣으면 매 요청마다 프롬프트가 바뀌어 캐시가 깨진다)
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        String todayText = today.format(DateTimeFormatter.ISO_LOCAL_DATE) + " ("
                + today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN) + ")";
        return template
                .replace("{{FAMILY_PROFILE}}", familyProfile())
                .replace("{{TODAY}}", todayText);
    }

    String familyProfile() {
        AgentProperties.Family family = properties.family();
        if (family == null) {
            return "- (프로필 없음: 사용자에게 가족 구성을 물어보세요)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- 거주지: ").append(family.homeCity()).append('\n');
        sb.append("- 기본 출발 공항: ").append(family.homeAirport()).append('\n');
        sb.append("- 사용 언어: ").append(family.language()).append('\n');
        sb.append("- 구성원:\n");
        for (AgentProperties.Member member : nullSafe(family.members())) {
            sb.append("  - ").append(member.role()).append(": ").append(member.ageDescription()).append('\n');
        }
        for (String note : nullSafe(family.notes())) {
            sb.append("- ").append(note).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
