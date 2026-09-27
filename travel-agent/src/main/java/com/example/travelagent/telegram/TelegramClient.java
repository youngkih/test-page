package com.example.travelagent.telegram;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Spring {@link RestClient} 로 텔레그램 Bot API 를 직접 호출한다.
 * 쓰는 메서드가 몇 개 안 되므로 외부 라이브러리 없이 구현했다.
 */
public class TelegramClient implements TelegramApi {

    private final RestClient rest;
    private final String token;

    /** @param rest baseUrl 이 {@code https://api.telegram.org/bot<토큰>} 으로 설정된 RestClient */
    TelegramClient(RestClient rest, String token) {
        this.rest = rest;
        this.token = token;
    }

    public static TelegramClient create(TelegramProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(ProxySelector.getDefault())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        // 롱 폴링은 서버가 pollTimeout 동안 응답을 붙잡고 있으므로 그보다 길게 기다려야 한다
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.pollTimeoutSeconds() + 15L));

        String token = properties.botToken() == null ? "" : properties.botToken().strip();
        RestClient rest = RestClient.builder()
                .baseUrl(properties.apiBaseUrl() + "/bot" + token)
                // 본문을 버퍼링해서 Content-Length 헤더를 붙인다 (chunked 전송을 싫어하는 서버/프록시 대비)
                .requestFactory(new BufferingClientHttpRequestFactory(requestFactory))
                .build();
        return new TelegramClient(rest, token);
    }

    @Override
    public User getMe() {
        return call("getMe", Map.of(), new ParameterizedTypeReference<Response<User>>() {
        });
    }

    @Override
    public List<Update> getUpdates(long offset, int timeoutSeconds) {
        return call("getUpdates",
                Map.of("offset", offset, "timeout", timeoutSeconds, "allowed_updates", List.of("message")),
                new ParameterizedTypeReference<Response<List<Update>>>() {
                });
    }

    @Override
    public long sendMessage(long chatId, String text, boolean html) {
        Map<String, Object> body = html
                ? Map.of("chat_id", chatId, "text", text, "parse_mode", "HTML",
                        "link_preview_options", Map.of("is_disabled", true))
                : Map.of("chat_id", chatId, "text", text, "link_preview_options", Map.of("is_disabled", true));
        return call("sendMessage", body, new ParameterizedTypeReference<Response<Message>>() {
        }).messageId();
    }

    @Override
    public void editMessageText(long chatId, long messageId, String text) {
        call("editMessageText", Map.of("chat_id", chatId, "message_id", messageId, "text", text),
                new ParameterizedTypeReference<Response<Object>>() {
                });
    }

    @Override
    public void sendChatAction(long chatId, String action) {
        call("sendChatAction", Map.of("chat_id", chatId, "action", action),
                new ParameterizedTypeReference<Response<Object>>() {
                });
    }

    @Override
    public void sendDocument(long chatId, String fileName, byte[] content, String caption) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("chat_id", String.valueOf(chatId));
        parts.add("caption", caption);
        parts.add("document", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        call("sendDocument", parts, MediaType.MULTIPART_FORM_DATA,
                new ParameterizedTypeReference<Response<Object>>() {
                });
    }

    private <T> T call(String method, Object body, ParameterizedTypeReference<Response<T>> type) {
        return call(method, body, MediaType.APPLICATION_JSON, type);
    }

    private <T> T call(String method, Object body, MediaType contentType,
            ParameterizedTypeReference<Response<T>> type) {
        Response<T> response;
        try {
            response = rest.post()
                    .uri("/" + method)
                    .contentType(contentType)
                    .body(body)
                    .retrieve()
                    // 4xx/5xx 도 예외 대신 본문(ok=false, description)을 읽어서 처리한다
                    .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
                    })
                    .body(type);
        } catch (RestClientException e) {
            throw new TelegramException(method + " 호출 실패: " + redact(e.getMessage()), 0);
        }
        if (response == null) {
            throw new TelegramException(method + " 응답이 비어 있습니다", 0);
        }
        if (!response.ok()) {
            int status = response.errorCode() == null ? 0 : response.errorCode();
            throw new TelegramException(method + " 실패 (" + status + "): " + redact(response.description()), status);
        }
        return response.result();
    }

    /** 오류 메시지에 토큰이 섞여 있으면 가린다. */
    String redact(String message) {
        if (message == null) {
            return "";
        }
        return token.isEmpty() ? message : message.replace(token, "***");
    }
}
