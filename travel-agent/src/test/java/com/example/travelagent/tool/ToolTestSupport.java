package com.example.travelagent.tool;

import java.util.List;

import com.example.travelagent.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

final class ToolTestSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();

    static AgentProperties properties() {
        return new AgentProperties("claude-opus-5", "high", 64000, 15, 20, 25,
                new AgentProperties.Family("서울", "ICN", "한국어",
                        List.of(new AgentProperties.Member("아들", "14개월", 1)), List.of()));
    }

    private ToolTestSupport() {
    }
}
