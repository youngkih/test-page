package com.example.travelagent.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** "오늘 날짜"를 테스트에서 고정할 수 있도록 Clock 을 빈으로 둔다. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
