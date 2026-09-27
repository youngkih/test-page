package com.example.travelagent.telegram;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TelegramConfig {

    @Bean
    public TelegramApi telegramApi(TelegramProperties properties) {
        return TelegramClient.create(properties);
    }
}
