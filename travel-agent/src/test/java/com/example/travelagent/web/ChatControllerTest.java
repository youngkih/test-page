package com.example.travelagent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 스프링 컨텍스트 전체가 API 키 없이도 뜨는지, 세션 API 가 동작하는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class ChatControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 세션을_만들고_조회한다() throws Exception {
        String body = mockMvc.perform(post("/api/sessions"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sessionId = body.replaceAll(".*\"sessionId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.transcript").isEmpty());
    }

    @Test
    void 없는_세션은_404() throws Exception {
        mockMvc.perform(get("/api/sessions/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 빈_메시지는_400() throws Exception {
        String body = mockMvc.perform(post("/api/sessions")).andReturn().getResponse().getContentAsString();
        String sessionId = body.replaceAll(".*\"sessionId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/sessions/" + sessionId + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
}
