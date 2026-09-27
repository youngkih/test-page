package com.example.travelagent.telegram;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 롱 폴링 루프. 맥미니가 텔레그램 서버에 먼저 연결해서 새 메시지를 가져오는 방식이라
 * 공유기 포트를 열거나 공개 주소를 만들 필요가 없다 (바깥에서 맥미니로 들어오는 연결이 없음).
 */
@Component
public class TelegramPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramPoller.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(1);

    private final TelegramApi api;
    private final TelegramBot bot;
    private final TelegramProperties properties;
    private volatile boolean running;
    private Thread thread;

    public TelegramPoller(TelegramApi api, TelegramBot bot, TelegramProperties properties) {
        this.api = api;
        this.bot = bot;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.enabled()) {
            log.info("TELEGRAM_BOT_TOKEN 이 없어 텔레그램 봇을 시작하지 않습니다.");
            return;
        }
        try {
            TelegramApi.User me = api.getMe();
            log.info("텔레그램 봇 @{} 시작", me.username());
        } catch (TelegramException e) {
            log.error("텔레그램 봇을 시작할 수 없습니다. 토큰을 확인하세요: {}", e.getMessage());
            return;
        }
        if (properties.allowedUserId() == null) {
            log.warn("TELEGRAM_ALLOWED_USER_ID 가 없어 설정 모드로 동작합니다. 봇에게 아무 메시지나 보내 ID 를 확인하세요.");
        }
        running = true;
        thread = Thread.ofVirtual().name("telegram-poller").start(this::loop);
    }

    private void loop() {
        long offset = 0;
        Duration backoff = Duration.ofSeconds(1);
        while (running) {
            try {
                List<TelegramApi.Update> updates = api.getUpdates(offset, properties.pollTimeoutSeconds());
                for (TelegramApi.Update update : updates) {
                    // 처리 전에 offset 을 올려서, 한 메시지 처리 오류로 같은 메시지를 무한 반복하지 않게 한다
                    offset = update.updateId() + 1;
                    try {
                        bot.handle(update);
                    } catch (RuntimeException e) {
                        log.error("텔레그램 메시지 처리 실패 ({})", e.getClass().getSimpleName());
                    }
                }
                backoff = Duration.ofSeconds(1);
            } catch (TelegramException e) {
                if (e.statusCode() == 401) {
                    log.error("봇 토큰이 올바르지 않거나 폐기되었습니다. 폴링을 멈춥니다.");
                    running = false;
                    return;
                }
                if (e.statusCode() == 409) {
                    log.error("다른 곳에서 같은 봇을 실행 중입니다 (409 Conflict). 중복 실행을 확인하세요.");
                } else {
                    log.warn("텔레그램 폴링 오류, {}초 후 재시도: {}", backoff.toSeconds(), e.getMessage());
                }
                if (!sleep(backoff)) {
                    return;
                }
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
