package com.example.travelagent.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

@Configuration
public class AnthropicConfig {

    /**
     * ANTHROPIC_API_KEY 환경 변수에서 키를 읽는다.
     * {@code @Lazy} 이므로 키가 없어도 서버는 뜨고, 첫 채팅 요청 시점에 오류를 알려준다.
     */
    @Bean
    @Lazy
    public AnthropicClient anthropicClient() {
        return AnthropicOkHttpClient.builder()
                .fromEnv()
                // 웹 검색을 여러 번 하는 긴 턴이 있으므로 넉넉하게
                .timeout(Duration.ofMinutes(15))
                .build();
    }
}
