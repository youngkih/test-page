package com.example.travelagent.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.yml 의 travel-agent.* 설정.
 *
 * @param model              사용할 Claude 모델 ID
 * @param effort             사고 깊이 (low | medium | high | xhigh | max)
 * @param maxTokens          응답 1회당 최대 출력 토큰
 * @param maxWebSearches     요청 1회당 웹 검색 최대 횟수 (비용 제어)
 * @param maxWebFetches      요청 1회당 웹 페이지 읽기 최대 횟수 (비용 제어)
 * @param maxAgentIterations 에이전트 루프(도구 호출 ↔ 모델) 최대 반복 횟수 (무한 루프 방지)
 * @param family             여행자(가족) 프로필 - 시스템 프롬프트에 주입된다
 */
@ConfigurationProperties(prefix = "travel-agent")
public record AgentProperties(
        @DefaultValue("claude-opus-5") String model,
        @DefaultValue("high") String effort,
        @DefaultValue("64000") long maxTokens,
        @DefaultValue("15") long maxWebSearches,
        @DefaultValue("20") long maxWebFetches,
        @DefaultValue("25") int maxAgentIterations,
        Family family) {

    public record Family(
            @DefaultValue("서울") String homeCity,
            @DefaultValue("ICN") String homeAirport,
            @DefaultValue("한국어") String language,
            List<Member> members,
            List<String> notes) {
    }

    public record Member(String role, String ageDescription, Integer ageYears) {
    }
}
