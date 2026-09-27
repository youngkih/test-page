package com.example.travelagent.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.yml 의 telegram.* 설정.
 *
 * @param botToken           @BotFather 에서 받은 봇 토큰. 비어 있으면 텔레그램 기능이 꺼진다
 * @param allowedUserId      응답할 텔레그램 사용자 ID (본인). 비어 있으면 "설정 모드"로 동작한다
 * @param pollTimeoutSeconds 롱 폴링 대기 시간 (초)
 * @param apiBaseUrl         텔레그램 Bot API 주소 (테스트에서 바꿔 끼우기 위해 설정으로 뺐다)
 */
@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(
        String botToken,
        Long allowedUserId,
        @DefaultValue("30") int pollTimeoutSeconds,
        @DefaultValue("https://api.telegram.org") String apiBaseUrl) {

    public boolean enabled() {
        return botToken != null && !botToken.isBlank();
    }
}
