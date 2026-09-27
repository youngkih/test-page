package com.example.travelagent.telegram;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 이 앱이 쓰는 텔레그램 Bot API 메서드만 모은 인터페이스.
 * 문서: https://core.telegram.org/bots/api
 */
public interface TelegramApi {

    /** 봇 자신의 정보. 토큰이 올바른지 확인할 때 쓴다. */
    User getMe();

    /** 롱 폴링: 새 메시지가 올 때까지 최대 timeoutSeconds 초 기다렸다가 돌려준다. */
    List<Update> getUpdates(long offset, int timeoutSeconds);

    /** @param html true 이면 parse_mode=HTML 로 보낸다. @return 보낸 메시지 ID */
    long sendMessage(long chatId, String text, boolean html);

    void editMessageText(long chatId, long messageId, String text);

    /** "입력 중..." 표시. action 예: typing, upload_document */
    void sendChatAction(long chatId, String action);

    void sendDocument(long chatId, String fileName, byte[] content, String caption);

    // ---- 텔레그램 JSON 형식 (필요한 필드만) ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response<T>(boolean ok, T result, String description, @JsonProperty("error_code") Integer errorCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Update(@JsonProperty("update_id") long updateId, Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(@JsonProperty("message_id") long messageId, User from, Chat chat, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record User(long id, @JsonProperty("is_bot") boolean isBot, @JsonProperty("first_name") String firstName,
            String username) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Chat(long id, String type) {
    }
}
