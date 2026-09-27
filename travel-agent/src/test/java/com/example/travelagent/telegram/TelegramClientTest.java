package com.example.travelagent.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TelegramClientTest {

    private static final String TOKEN = "123456:SECRET-TOKEN";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.telegram.org/bot" + TOKEN);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final TelegramClient client = new TelegramClient(builder.build(), TOKEN);

    @Test
    void getUpdates_응답을_읽는다() {
        server.expect(requestTo("https://api.telegram.org/bot" + TOKEN + "/getUpdates"))
                .andExpect(content().json("{\"offset\":5,\"timeout\":30,\"allowed_updates\":[\"message\"]}"))
                .andRespond(withSuccess("""
                        {"ok":true,"result":[{"update_id":5,"message":{"message_id":1,
                          "from":{"id":42,"is_bot":false,"first_name":"영기"},
                          "chat":{"id":42,"type":"private"},"text":"안녕","date":0}}]}
                        """, MediaType.APPLICATION_JSON));

        var updates = client.getUpdates(5, 30);

        assertThat(updates).singleElement().satisfies(update -> {
            assertThat(update.updateId()).isEqualTo(5);
            assertThat(update.message().from().id()).isEqualTo(42);
            assertThat(update.message().chat().type()).isEqualTo("private");
            assertThat(update.message().text()).isEqualTo("안녕");
        });
    }

    @Test
    void 오류_응답에서_상태코드를_읽고_토큰은_가린다() {
        server.expect(requestTo("https://api.telegram.org/bot" + TOKEN + "/sendMessage"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized " + TOKEN + "\"}"));

        TelegramException error = catchThrowableOfType(TelegramException.class,
                () -> client.sendMessage(1, "hi", false));

        assertThat(error.statusCode()).isEqualTo(401);
        assertThat(error.getMessage()).doesNotContain(TOKEN).contains("***");
        assertThat(error.getCause()).isNull();
    }

    @Test
    void 네트워크_오류_메시지의_URL에서도_토큰을_가린다() {
        assertThat(client.redact("I/O error on POST request for \"https://api.telegram.org/bot" + TOKEN + "/getUpdates\""))
                .doesNotContain(TOKEN);
    }
}
