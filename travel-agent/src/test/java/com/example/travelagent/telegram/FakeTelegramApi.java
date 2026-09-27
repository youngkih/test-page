package com.example.travelagent.telegram;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 호출 내역을 기록하는 가짜 텔레그램 API. */
class FakeTelegramApi implements TelegramApi {

    record Sent(long chatId, String text, boolean html) {
    }

    record Doc(long chatId, String fileName, String content, String caption) {
    }

    final List<Sent> sent = new ArrayList<>();
    final List<String> edits = new ArrayList<>();
    final List<Doc> documents = new ArrayList<>();
    /** true 이면 HTML 메시지를 400 으로 거절한다 (텔레그램 HTML 파싱 실패 흉내). */
    boolean rejectHtml;
    private long nextMessageId = 100;

    @Override
    public User getMe() {
        return new User(1, true, "bot", "test_bot");
    }

    @Override
    public List<Update> getUpdates(long offset, int timeoutSeconds) {
        return List.of();
    }

    @Override
    public long sendMessage(long chatId, String text, boolean html) {
        if (html && rejectHtml) {
            throw new TelegramException("can't parse entities", 400);
        }
        sent.add(new Sent(chatId, text, html));
        return nextMessageId++;
    }

    @Override
    public void editMessageText(long chatId, long messageId, String text) {
        edits.add(text);
    }

    @Override
    public void sendChatAction(long chatId, String action) {
    }

    @Override
    public void sendDocument(long chatId, String fileName, byte[] content, String caption) {
        documents.add(new Doc(chatId, fileName, new String(content, StandardCharsets.UTF_8), caption));
    }
}
